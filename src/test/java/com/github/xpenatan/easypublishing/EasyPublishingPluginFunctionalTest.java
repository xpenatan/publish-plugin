package com.github.xpenatan.easypublishing;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasyPublishingPluginFunctionalTest {
    @TempDir
    File projectDir;

    @Test
    void exposesOnlyFourEasyPublishingTasks() throws IOException {
        writeProject("1.2.3", "-SNAPSHOT");
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

        assertTrue(output.contains("Easy-publishing tasks"));
        assertTrue(output.contains("prepareSnapshot - "));
        assertTrue(output.contains("prepareRelease - "));
        assertTrue(output.contains("publishSnapshot - "));
        assertTrue(output.contains("publishRelease - "));
        assertFalse(output.contains("Publishing tasks"));
        assertFalse(output.contains("prepareSnapshotDeploy"));
        assertFalse(output.contains("prepareReleaseDeploy"));
        assertFalse(output.contains("assembleReleaseRepository"));
        assertFalse(output.contains("zipReleaseBundle"));
        assertFalse(output.contains("validateSnapshotPublication"));
        assertFalse(output.contains("generatePomFileForMavenPublication"));
        assertFalse(output.contains("publishMavenPublicationToEasyPublishingRepository"));
    }

    @Test
    void requiresPublishingGroupId() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'sample'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
                id 'com.github.xpenatan.easy-publishing'
            }

            easyPublishing {
                releaseVersion = '1.2.3'
                snapshotVersion = '-SNAPSHOT'
            }
            """);

        BuildResult result = runner("tasks").buildAndFail();

        assertTrue(result.getOutput().contains("easyPublishing.groupId must be configured"));
    }

    @Test
    void requiresPublishingReleaseVersion() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'sample'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
                id 'com.github.xpenatan.easy-publishing'
            }

            easyPublishing {
                groupId = 'com.example'
                snapshotVersion = '-SNAPSHOT'
            }
            """);

        BuildResult result = runner("tasks").buildAndFail();

        assertTrue(result.getOutput().contains("easyPublishing.releaseVersion must be configured"));
    }

    @Test
    void requiresPublishingSnapshotVersion() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'sample'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
                id 'com.github.xpenatan.easy-publishing'
            }

            easyPublishing {
                groupId = 'com.example'
                releaseVersion = '1.2.3'
            }
            """);

        BuildResult result = runner("tasks").buildAndFail();

        assertTrue(result.getOutput().contains("easyPublishing.snapshotVersion must be configured"));
    }

    @Test
    void preparesSnapshotRepositoryAndPom() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");

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
        assertFalse(new File(projectDir, "build/easy-publishing").exists());

        String pom = Files.readString(findFile(artifactDirectory, name -> name.endsWith(".pom")).toPath());
        assertTrue(pom.contains("<name>Sample Library</name>"));
        assertTrue(pom.contains("<url>https://github.com/example/sample</url>"));
        assertTrue(pom.contains("<name>Example Developer</name>"));
    }

    @Test
    void preparesLiteralSnapshotVersion() throws IOException {
        writeProject("1.2.3", "-SNAPSHOT");

        BuildResult result = runner("prepareSnapshot").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareSnapshot").getOutcome());
        File artifactDirectory = new File(
            projectDir,
            "build/snapshot-deploy/com/example/sample-lib/-SNAPSHOT"
        );
        assertNotNull(findFile(artifactDirectory, name -> name.equals("sample-lib--SNAPSHOT.jar")));
        assertNotNull(findFile(artifactDirectory, name -> name.equals("sample-lib--SNAPSHOT.pom")));
        assertFalse(new File(projectDir, "build/easy-publishing").exists());
    }

    @Test
    void preparesReleaseRepositoryWithoutCreatingBundle() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");

        BuildResult result = runner("prepareRelease").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareRelease").getOutcome());
        assertFalse(new File(projectDir, "build/easy-publishing").exists());
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/sample-lib/1.2.3/sample-lib-1.2.3.jar"
        ).isFile());
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/sample-lib/1.2.3/sample-lib-1.2.3.pom"
        ).isFile());
        assertFalse(new File(projectDir, "build/staging-deploy.zip").exists());
    }

    @Test
    void publishesSnapshotToConfiguredMavenRepository() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "build.gradle").toPath(),
            """

            easyPublishing {
                snapshotRepositoryUrl = layout.projectDirectory.dir('remote-snapshots')
                    .asFile.toURI().toString()
            }
            """,
            java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult result = runner("publishSnapshot").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":publishSnapshot").getOutcome());
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":publishMavenPublicationToEasyPublishingRepository").getOutcome()
        );
        File artifactDirectory = new File(
            projectDir,
            "remote-snapshots/com/example/sample-lib/1.2.3-SNAPSHOT"
        );
        assertNotNull(findFile(artifactDirectory, name ->
            name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc")
        ));
        assertNotNull(findFile(artifactDirectory, name -> name.endsWith(".pom")));
    }

    @Test
    void rejectsSnapshotPublishingWithoutRepository() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");

        BuildResult result = runner("publishSnapshot").buildAndFail();

        assertTrue(result.getOutput().contains(
            "publishSnapshot requires easyPublishing.snapshotRepositoryUrl"
        ));
    }

    @Test
    void publishesReleaseToConfiguredMavenRepository() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "build.gradle").toPath(),
            """

            easyPublishing {
                releaseRepositoryUrl = layout.projectDirectory.dir('remote-releases')
                    .asFile.toURI().toString()
            }
            """,
            java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult prepared = runner("prepareRelease").build();
        assertEquals(TaskOutcome.SUCCESS, prepared.task(":prepareRelease").getOutcome());
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/sample-lib/1.2.3/sample-lib-1.2.3.jar"
        ).isFile());
        assertFalse(new File(projectDir, "build/staging-deploy.zip").exists());

        BuildResult published = runner("publishRelease").build();
        assertEquals(TaskOutcome.SUCCESS, published.task(":publishRelease").getOutcome());
        assertEquals(
            TaskOutcome.SUCCESS,
            published.task(":publishMavenPublicationToEasyPublishingReleaseRepository").getOutcome()
        );
        assertTrue(published.task(":uploadReleaseToMavenCentral") == null);
        assertTrue(published.task(":zipReleaseBundle") == null);
        assertFalse(new File(projectDir, "build/staging-deploy.zip").exists());
        assertTrue(new File(
            projectDir,
            "remote-releases/com/example/sample-lib/1.2.3/sample-lib-1.2.3.jar"
        ).isFile());
        assertTrue(new File(
            projectDir,
            "remote-releases/com/example/sample-lib/1.2.3/sample-lib-1.2.3.pom"
        ).isFile());
    }

    @Test
    void rejectsReleasePublishingWithoutProvider() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");

        BuildResult result = runner("publishRelease", "--dry-run").buildAndFail();

        assertTrue(result.getOutput().contains(
            "publishRelease requires easyPublishing.releaseRepositoryUrl"
        ));
    }

    @Test
    void usesExplicitlyConfiguredCentralPortal() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "build.gradle").toPath(),
            """

            easyPublishing {
                releaseRepositoryUrl = 'https://central.sonatype.com'
                username.set(providers.provider { 'test-user' })
                password.set(providers.provider { 'test-password' })
                requireSigningForUpload = false
            }
            """,
            java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult result = runner("publishRelease", "--dry-run").build();

        assertTrue(result.getOutput().contains(":prepareRelease SKIPPED"));
        assertTrue(result.getOutput().contains(":zipReleaseBundle SKIPPED"));
        assertTrue(result.getOutput().contains(":uploadReleaseToMavenCentral SKIPPED"));
        assertTrue(result.getOutput().contains(":publishRelease SKIPPED"));
        assertFalse(result.getOutput().contains("ToEasyPublishingReleaseRepository"));
    }

    @Test
    void requiresDirectSigningValuesForCentralPortalUpload() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "build.gradle").toPath(),
            """

            easyPublishing {
                releaseRepositoryUrl = 'https://central.sonatype.com'
                username = 'test-user'
                password = 'test-password'
            }
            """,
            java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult result = runner("publishRelease").buildAndFail();

        assertTrue(result.getOutput().contains("easyPublishing.signingKey must be configured"));
        assertFalse(result.getOutput().contains("environment variable"));
        assertEquals(TaskOutcome.SUCCESS, result.task(":zipReleaseBundle").getOutcome());
        assertTrue(new File(projectDir, "build/staging-deploy.zip").isFile());
    }

    @Test
    void rejectsSnapshotVersionWithoutSnapshotSuffix() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "build.gradle").toPath(),
            """

            easyPublishing {
                snapshotVersion = '1.2.3'
            }
            """,
            java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult result = runner("prepareSnapshot").buildAndFail();

        assertTrue(result.getOutput().contains(
            "easyPublishing.snapshotVersion must end with -SNAPSHOT"
        ));
        assertFalse(new File(projectDir, "build/snapshot-deploy").exists());
    }

    @Test
    void rejectsSnapshotValueAsReleaseVersion() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");
        Files.writeString(
            new File(projectDir, "build.gradle").toPath(),
            """

            easyPublishing {
                releaseVersion = '1.2.3-SNAPSHOT'
            }
            """,
            java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult result = runner("prepareRelease").buildAndFail();

        assertTrue(result.getOutput().contains(
            "easyPublishing.releaseVersion must not end with -SNAPSHOT"
        ));
        assertFalse(new File(projectDir, "build/staging-deploy").exists());
    }

    @Test
    void rejectsMixedSnapshotAndReleaseLifecycles() throws IOException {
        writeProject("1.2.3", "1.2.3-SNAPSHOT");

        BuildResult result = runner("prepareSnapshot", "prepareRelease").buildAndFail();

        assertTrue(result.getOutput().contains(
            "Snapshot and release publishing tasks cannot be requested in the same Gradle invocation"
        ));
    }

    @Test
    void configuresSelectedSubprojectBeforeItsPublishingBlockRuns() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), """
            rootProject.name = 'multi-project'
            include 'library'
            """);
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'com.github.xpenatan.easy-publishing'
            }

            easyPublishing {
                modules ':library'
                groupId = 'com.example.multi'
                releaseVersion = '2.0.0'
                snapshotVersion = '2.0.0-SNAPSHOT'
                pomName = 'Selected Library'
            }
            """);
        File libraryDirectory = new File(projectDir, "library");
        Files.createDirectories(libraryDirectory.toPath());
        Files.writeString(new File(libraryDirectory, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
            }

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
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/multi/custom-artifact/2.0.0/custom-artifact-2.0.0.jar"
        ).isFile());
        assertFalse(new File(projectDir, "build/staging-deploy.zip").exists());
    }

    @Test
    void mergesArtifactsFromNestedGradleBuild() throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'outer'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'com.github.xpenatan.easy-publishing'
            }

            easyPublishing {
                groupId = 'com.example.outer'
                releaseVersion = '1.0.0'
                snapshotVersion = '1.0.0-SNAPSHOT'
                releaseRepositoryUrl = layout.projectDirectory.dir('direct-release-repository')
                    .asFile.toURI().toString()
                nestedBuild('tool') {
                    directory = layout.projectDirectory.dir('tool')
                    snapshotDirectory = layout.projectDirectory.dir('tool/out/snapshot')
                    releaseDirectory = layout.projectDirectory.dir('tool/out/release')
                    prepareSnapshotTask = 'makeSnapshot'
                    prepareReleaseTask = 'makeRelease'
                    publishReleaseTask = 'shipRelease'
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
            tasks.register('shipRelease') {
                def marker = layout.projectDirectory.file('out/published-release-url.txt')
                outputs.file(marker)
                doLast {
                    marker.asFile.parentFile.mkdirs()
                    marker.asFile.text = providers.gradleProperty(
                        'easyPublishing.releaseRepositoryUrl'
                    ).get()
                }
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
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/tool/1.0/tool.jar"
        ).isFile());
        assertFalse(new File(projectDir, "build/staging-deploy.zip").exists());

        BuildResult published = runner("publishRelease").build();
        assertEquals(TaskOutcome.SUCCESS, published.task(":publishToolRelease").getOutcome());
        assertEquals(
            new File(projectDir, "direct-release-repository").toURI().toString(),
            Files.readString(new File(tool, "out/published-release-url.txt").toPath())
        );
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
                id("com.github.xpenatan.easy-publishing")
            }

            val publishingModules = listOf(":")

            easyPublishing {
                modules(publishingModules)
                groupId.set("com.example.kotlin")
                releaseVersion.set("3.0.0")
                snapshotVersion.set("3.0.0-SNAPSHOT")
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

    @Test
    void configuresGradlePluginPublicationsAutomatically() throws IOException {
        Files.writeString(
            new File(projectDir, "settings.gradle.kts").toPath(),
            "rootProject.name = \"sample-gradle-plugin\"\n"
        );
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), """
            plugins {
                `java-gradle-plugin`
                id("com.github.xpenatan.easy-publishing")
            }

            gradlePlugin {
                plugins {
                    create("sample") {
                        id = "com.example.sample"
                        implementationClass = "example.SamplePlugin"
                    }
                }
            }

            easyPublishing {
                groupId.set("com.example")
                releaseVersion.set("1.0.0")
                snapshotVersion.set("1.0.0-SNAPSHOT")
                pomName.set("Sample Gradle plugin")
                releaseRepositoryUrl.set(
                    layout.projectDirectory.dir("remote-releases").asFile.toURI().toString()
                )
            }
            """);
        File source = new File(projectDir, "src/main/java/example/SamplePlugin.java");
        Files.createDirectories(source.getParentFile().toPath());
        Files.writeString(source.toPath(), """
            package example;
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            public class SamplePlugin implements Plugin<Project> {
                @Override public void apply(Project project) {}
            }
            """);

        BuildResult snapshot = runner("prepareSnapshot").build();
        assertEquals(TaskOutcome.SUCCESS, snapshot.task(":prepareSnapshot").getOutcome());
        assertTrue(new File(
            projectDir,
            "build/snapshot-deploy/com/example/sample-gradle-plugin/1.0.0-SNAPSHOT"
        ).isDirectory());
        assertTrue(new File(
            projectDir,
            "build/snapshot-deploy/com/example/sample/com.example.sample.gradle.plugin/1.0.0-SNAPSHOT"
        ).isDirectory());

        BuildResult release = runner("prepareRelease").build();
        assertEquals(TaskOutcome.SUCCESS, release.task(":prepareRelease").getOutcome());
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/sample-gradle-plugin/1.0.0/"
                + "sample-gradle-plugin-1.0.0.jar"
        ).isFile());
        assertTrue(new File(
            projectDir,
            "build/staging-deploy/com/example/sample/com.example.sample.gradle.plugin/1.0.0/"
                + "com.example.sample.gradle.plugin-1.0.0.pom"
        ).isFile());
        assertFalse(new File(projectDir, "build/staging-deploy.zip").exists());

        BuildResult published = runner("publishRelease").build();
        assertEquals(TaskOutcome.SUCCESS, published.task(":publishRelease").getOutcome());
        assertTrue(new File(
            projectDir,
            "remote-releases/com/example/sample-gradle-plugin/1.0.0/"
                + "sample-gradle-plugin-1.0.0.jar"
        ).isFile());
        assertTrue(new File(
            projectDir,
            "remote-releases/com/example/sample/com.example.sample.gradle.plugin/1.0.0/"
                + "com.example.sample.gradle.plugin-1.0.0.pom"
        ).isFile());
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

    private void writeProject(String releaseVersion, String snapshotVersion) throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'sample-lib'\n");
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
            plugins {
                id 'java-library'
                id 'com.github.xpenatan.easy-publishing'
            }

            easyPublishing {
                groupId = 'com.example'
                releaseVersion = '%s'
                snapshotVersion = '%s'
                pomName = 'Sample Library'
                pomDescription = 'A functional-test library'
                projectUrl = 'https://github.com/example/sample'
                developerId = 'example'
                developerName = 'Example Developer'
                scmUrl = 'https://github.com/example/sample'
                scmConnection = 'scm:git:https://github.com/example/sample.git'
            }
            """.formatted(releaseVersion, snapshotVersion));

        File source = new File(projectDir, "src/main/java/com/example/Sample.java");
        Files.createDirectories(source.getParentFile().toPath());
        Files.writeString(source.toPath(), "package com.example; public class Sample {}\n");
    }
}
