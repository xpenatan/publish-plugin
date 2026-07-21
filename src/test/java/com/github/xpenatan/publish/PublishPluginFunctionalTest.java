package com.github.xpenatan.publish;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishPluginFunctionalTest {
    @TempDir
    File projectDir;

    @Test
    void exposesOnlyFourPublishPluginTasks() throws IOException {
        writeProject("-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "settings.gradle").toPath(),
            "rootProject.name = 'sample-lib'\ninclude 'unmanaged-publication'\n"
        );
        File unmanagedPublication = new File(projectDir, "unmanaged-publication");
        Files.createDirectories(unmanagedPublication.toPath());
        Files.writeString(new File(unmanagedPublication, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
                id 'maven-publish'
            }

            publishing {
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
            """);

        String output = runner("tasks").build().getOutput();

        assertTrue(output.contains("Publish-plugin tasks"));
        assertTrue(output.contains("prepareSnapshot - "));
        assertTrue(output.contains("prepareRelease - "));
        assertTrue(output.contains("publishSnapshot - "));
        assertTrue(output.contains("publishRelease - "));
        assertFalse(output.contains("Publishing tasks"));
        assertFalse(output.contains("prepareSnapshotDeploy"));
        assertFalse(output.contains("prepareReleaseDeploy"));
        assertFalse(output.contains("assembleReleaseRepository"));
        assertFalse(output.contains("validateSnapshotPublication"));
        assertFalse(output.contains("generatePomFileForMavenPublication"));
        assertFalse(output.contains("publishMavenPublicationToXpePublishRepository"));
    }

    @Test
    void preparesSnapshotRepositoryAndPom() throws IOException {
        writeProject("1.2.3-SNAPSHOT");

        BuildResult result = runner("prepareSnapshot").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareSnapshot").getOutcome());
        File artifactDirectory = new File(
            projectDir,
            "build/snapshot-deploy/com/example/sample-lib/1.2.3-SNAPSHOT"
        );
        assertNotNull(findFile(artifactDirectory, name ->
            name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc")
        ));
        assertNotNull(findFile(artifactDirectory, name -> name.equals("sample-lib-1.2.3-SNAPSHOT.jar")));
        assertNotNull(findFile(artifactDirectory, name -> name.endsWith("-sources.jar")));
        assertNotNull(findFile(artifactDirectory, name -> name.endsWith("-javadoc.jar")));
        assertFalse(new File(projectDir, "build/publish-plugin").exists());

        String pom = Files.readString(findFile(artifactDirectory, name -> name.endsWith(".pom")).toPath());
        assertTrue(pom.contains("<name>Sample Library</name>"));
        assertTrue(pom.contains("<url>https://github.com/example/sample</url>"));
        assertTrue(pom.contains("<name>Example Developer</name>"));
    }

    @Test
    void preparesLiteralSnapshotVersion() throws IOException {
        writeProject("-SNAPSHOT");

        BuildResult result = runner("prepareSnapshot").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareSnapshot").getOutcome());
        File artifactDirectory = new File(
            projectDir,
            "build/snapshot-deploy/com/example/sample-lib/-SNAPSHOT"
        );
        assertNotNull(findFile(artifactDirectory, name -> name.equals("sample-lib--SNAPSHOT.jar")));
        assertNotNull(findFile(artifactDirectory, name -> name.equals("sample-lib--SNAPSHOT.pom")));
        assertFalse(new File(projectDir, "build/publish-plugin").exists());
    }

    @Test
    void preparesReleaseBundle() throws IOException {
        writeProject("1.2.3");

        BuildResult result = runner("prepareRelease").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareRelease").getOutcome());
        assertFalse(new File(projectDir, "build/publish-plugin").exists());
        File bundle = new File(projectDir, "build/staging-deploy.zip");
        assertTrue(bundle.isFile());
        try (ZipFile zip = new ZipFile(bundle)) {
            assertNotNull(zip.getEntry("com/example/sample-lib/1.2.3/sample-lib-1.2.3.jar"));
            assertNotNull(zip.getEntry("com/example/sample-lib/1.2.3/sample-lib-1.2.3.pom"));
        }
    }

    @Test
    void rejectsReleaseVersionForSnapshotPreparation() throws IOException {
        writeProject("1.2.3");

        BuildResult result = runner("prepareSnapshot").buildAndFail();

        assertTrue(result.getOutput().contains("Cannot prepare snapshot"));
        assertTrue(result.getOutput().contains("uses release version 1.2.3"));
        assertFalse(new File(projectDir, "build/snapshot-deploy").exists());
    }

    @Test
    void configuresSelectedSubprojectBeforeItsPublishingBlockRuns() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), """
            rootProject.name = 'multi-project'
            include 'library'
            """);
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'com.github.xpenatan.publish'
            }

            xpePublishing {
                modules ':library'
                pomName = 'Selected Library'
            }
            """);
        File libraryDirectory = new File(projectDir, "library");
        Files.createDirectories(libraryDirectory.toPath());
        Files.writeString(new File(libraryDirectory, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
            }

            group = 'com.example.multi'
            version = rootProject.ext['xpePublishing.releaseRequested'] ? '2.0.0' : '2.0.0-SNAPSHOT'

            publishing {
                publications {
                    custom(MavenPublication) {
                        artifactId = 'custom-artifact'
                        from components.java
                    }
                }
            }
            """);
        File source = new File(libraryDirectory, "src/main/java/example/Library.java");
        Files.createDirectories(source.getParentFile().toPath());
        Files.writeString(source.toPath(), "package example; public class Library {}\n");

        BuildResult snapshot = runner("prepareSnapshot").build();
        assertEquals(TaskOutcome.SUCCESS, snapshot.task(":prepareSnapshot").getOutcome());
        File multiSnapshot = new File(
            projectDir,
            "build/snapshot-deploy/com/example/multi/custom-artifact/2.0.0-SNAPSHOT"
        );
        assertNotNull(findFile(multiSnapshot, name ->
            name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc")
        ));

        BuildResult release = runner("prepareRelease").build();
        assertEquals(TaskOutcome.SUCCESS, release.task(":prepareRelease").getOutcome());
        try (ZipFile zip = new ZipFile(new File(projectDir, "build/staging-deploy.zip"))) {
            assertNotNull(zip.getEntry(
                "com/example/multi/custom-artifact/2.0.0/custom-artifact-2.0.0.jar"
            ));
        }
    }

    @Test
    void mergesArtifactsFromNestedGradleBuild() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'outer'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'com.github.xpenatan.publish'
            }

            xpePublishing {
                nestedBuild('tool') {
                    directory = layout.projectDirectory.dir('tool')
                    snapshotDirectory = layout.projectDirectory.dir('tool/out/snapshot')
                    releaseDirectory = layout.projectDirectory.dir('tool/out/release')
                    prepareSnapshotTask = 'makeSnapshot'
                    prepareReleaseTask = 'makeRelease'
                }
            }
            """);

        File tool = new File(projectDir, "tool");
        Files.createDirectories(tool.toPath());
        Files.writeString(new File(tool, "settings.gradle").toPath(), "rootProject.name = 'tool'\n");
        Files.writeString(new File(tool, "build.gradle").toPath(), """
            tasks.register('makeSnapshot', Sync) {
                from layout.projectDirectory.dir('snapshot-source')
                into layout.projectDirectory.dir('out/snapshot')
            }
            tasks.register('makeRelease', Sync) {
                from layout.projectDirectory.dir('release-source')
                into layout.projectDirectory.dir('out/release')
            }
            """);
        File snapshotArtifact = new File(tool, "snapshot-source/com/example/tool/1.0-SNAPSHOT/tool.jar");
        File releaseArtifact = new File(tool, "release-source/com/example/tool/1.0/tool.jar");
        Files.createDirectories(snapshotArtifact.getParentFile().toPath());
        Files.createDirectories(releaseArtifact.getParentFile().toPath());
        Files.writeString(snapshotArtifact.toPath(), "snapshot");
        Files.writeString(releaseArtifact.toPath(), "release");

        BuildResult snapshot = runner("prepareSnapshot").build();
        assertEquals(TaskOutcome.SUCCESS, snapshot.task(":prepareToolSnapshot").getOutcome());
        assertTrue(new File(
            projectDir,
            "build/snapshot-deploy/com/example/tool/1.0-SNAPSHOT/tool.jar"
        ).isFile());

        BuildResult release = runner("prepareRelease").build();
        assertEquals(TaskOutcome.SUCCESS, release.task(":prepareToolRelease").getOutcome());
        try (ZipFile zip = new ZipFile(new File(projectDir, "build/staging-deploy.zip"))) {
            assertNotNull(zip.getEntry("com/example/tool/1.0/tool.jar"));
        }
    }

    @Test
    void supportsKotlinDslConfiguration() throws IOException {
        Files.writeString(
            new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"kotlin-lib\"\n"
        );
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), """
            plugins {
                `java-library`
                id("com.github.xpenatan.publish")
            }

            group = "com.example.kotlin"
            version = "3.0.0-SNAPSHOT"

            xpePublishing {
                pomName.set("Kotlin DSL Library")
                pomDescription.set("Configured from Kotlin DSL")
                projectUrl.set("https://github.com/example/kotlin-lib")
                developerId.set("developer")
                developerName.set("Developer")
            }
            """);
        File source = new File(projectDir, "src/main/java/example/KotlinDslLibrary.java");
        Files.createDirectories(source.getParentFile().toPath());
        Files.writeString(source.toPath(), "package example; public class KotlinDslLibrary {}\n");

        BuildResult result = runner("prepareSnapshot").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareSnapshot").getOutcome());
        assertTrue(new File(
            projectDir,
            "build/snapshot-deploy/com/example/kotlin/kotlin-lib/3.0.0-SNAPSHOT"
        ).isDirectory());
    }

    private GradleRunner runner(String... arguments) {
        List<String> runnerArguments = new ArrayList<>(Arrays.asList(arguments));
        runnerArguments.add("--configuration-cache");
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(runnerArguments)
            .forwardOutput();
    }

    private static File findFile(File directory, Predicate<String> predicate) {
        File[] matches = directory.listFiles(file -> file.isFile() && predicate.test(file.getName()));
        return matches == null || matches.length == 0 ? null : matches[0];
    }

    private void writeProject(String version) throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'sample-lib'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
                id 'com.github.xpenatan.publish'
            }

            group = 'com.example'
            version = '%s'

            xpePublishing {
                pomName = 'Sample Library'
                pomDescription = 'A functional-test library'
                projectUrl = 'https://github.com/example/sample'
                developerId = 'example'
                developerName = 'Example Developer'
                scmUrl = 'https://github.com/example/sample'
                scmConnection = 'scm:git:https://github.com/example/sample.git'
            }
            """.formatted(version));

        File source = new File(projectDir, "src/main/java/com/example/Sample.java");
        Files.createDirectories(source.getParentFile().toPath());
        Files.writeString(source.toPath(), "package com.example; public class Sample {}\n");
    }
}
