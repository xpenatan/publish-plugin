package com.github.xpenatan.publish;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPom;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.GradleBuild;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.plugins.signing.SigningExtension;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Reusable snapshot and Maven Central release publishing workflow. */
public class PublishPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "publishPlugin";
    public static final String RELEASE_REQUESTED_EXTRA = "publishPlugin.releaseRequested";
    private static final String TASK_GROUP = "publish-plugin";
    private static final String REPOSITORY_NAME = "xpePublish";
    private static final Set<String> PUBLIC_TASK_NAMES = Set.of(
        "prepareSnapshot",
        "prepareRelease",
        "publishSnapshot",
        "publishRelease"
    );

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("com.github.xpenatan.publish must be applied to the root project");
        }

        boolean releaseRequested = isReleaseRequested(project);
        boolean localSnapshotRequested = isAnyTaskRequested(project, "prepareSnapshot");
        project.getExtensions().getExtraProperties().set(RELEASE_REQUESTED_EXTRA, releaseRequested);

        PublishExtension extension = project.getExtensions().create(
            EXTENSION_NAME,
            PublishExtension.class,
            project
        );

        TaskProvider<Delete> cleanSnapshotRoot = project.getTasks().register(
            "cleanSnapshotPublishingStaging",
            Delete.class,
            task -> task.delete(extension.getSnapshotDirectory())
        );
        TaskProvider<Delete> cleanReleaseRoot = project.getTasks().register(
            "cleanReleasePublishingStaging",
            Delete.class,
            task -> {
                task.delete(extension.getReleaseDirectory());
                task.delete(extension.getReleaseBundle());
            }
        );

        TaskProvider<ValidatePublicationsTask> validateSnapshot = validationTask(
            project,
            "validateSnapshotPublication",
            false
        );
        TaskProvider<ValidatePublicationsTask> validateRelease = validationTask(
            project,
            "validateReleasePublication",
            true
        );

        TaskProvider<PrepareRepositoryTask> prepareSnapshot = project.getTasks().register(
            "prepareSnapshot",
            PrepareRepositoryTask.class,
            task -> {
                task.setGroup(TASK_GROUP);
                task.setDescription("Prepares a clean local Maven repository containing all snapshot publications.");
                task.getDestinationDirectory().set(extension.getSnapshotDirectory());
                task.getNormalizeSnapshots().set(true);
                task.dependsOn(cleanSnapshotRoot);
            }
        );

        TaskProvider<PrepareRepositoryTask> assembleRelease = project.getTasks().register(
            "assembleReleaseRepository",
            PrepareRepositoryTask.class,
            task -> {
                task.setDescription("Assembles the staged Maven release repository.");
                task.getDestinationDirectory().set(extension.getReleaseDirectory());
                task.getNormalizeSnapshots().set(false);
                task.dependsOn(cleanReleaseRoot);
            }
        );

        TaskProvider<Zip> prepareRelease = project.getTasks().register("prepareRelease", Zip.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Prepares and zips a Maven Central release bundle without uploading it.");
            task.dependsOn(assembleRelease);
            task.from(extension.getReleaseDirectory());
        });

        TaskProvider<Task> publishSnapshot = project.getTasks().register("publishSnapshot", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Publishes all snapshot publications to the configured snapshot repository.");
        });

        TaskProvider<UploadToCentralTask> uploadRelease = project.getTasks().register(
            "uploadReleaseToMavenCentral",
            UploadToCentralTask.class,
            task -> {
                task.setDescription("Uploads the prepared release bundle to the Central Publisher Portal.");
                task.dependsOn(prepareRelease);
                task.getBundleFile().set(extension.getReleaseBundle());
                task.getCentralPortalUrl().set(extension.getCentralPortalUrl());
                task.getDeploymentName().set(extension.getDeploymentName());
                task.getAutomaticRelease().set(extension.getAutomaticRelease());
                task.getUsernameEnvironmentVariable().set(extension.getUsernameEnvironmentVariable());
                task.getPasswordEnvironmentVariable().set(extension.getPasswordEnvironmentVariable());
                task.getSigningKeyEnvironmentVariable().set(extension.getSigningKeyEnvironmentVariable());
                task.getSigningPasswordEnvironmentVariable().set(extension.getSigningPasswordEnvironmentVariable());
                task.getRequireSigning().set(extension.getRequireSigningForUpload());
            }
        );

        TaskProvider<Task> publishRelease = project.getTasks().register("publishRelease", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Prepares and uploads a release bundle to the Central Publisher Portal.");
            task.dependsOn(uploadRelease);
        });

        project.afterEvaluate(ignored -> {
            configureReleaseArchive(prepareRelease, extension);
            Set<Project> publicationProjects = resolvePublicationProjects(project, extension);
            for (Project publicationProject : publicationProjects) {
                if (publicationProject == project || publicationProject.getState().getExecuted()) {
                    publicationProject.getPluginManager().apply("maven-publish");
                    publicationProject.getPluginManager().apply("signing");
                    configurePublicationProject(
                        publicationProject,
                        project,
                        extension,
                        releaseRequested,
                        localSnapshotRequested,
                        validateSnapshot,
                        validateRelease
                    );
                }
                else {
                    publicationProject.afterEvaluate(child -> configurePublicationProject(
                        child,
                        project,
                        extension,
                        releaseRequested,
                        localSnapshotRequested,
                        validateSnapshot,
                        validateRelease
                    ));
                    // Apply after registering our listener so publications and repositories exist
                    // before maven-publish finalizes its tasks at the end of project evaluation.
                    publicationProject.getPluginManager().apply("maven-publish");
                    publicationProject.getPluginManager().apply("signing");
                }
            }

            configureNestedBuilds(
                project,
                extension,
                prepareSnapshot,
                assembleRelease,
                publishSnapshot
            );

            wirePublicationTasks(
                project,
                publicationProjects,
                releaseRequested,
                localSnapshotRequested,
                cleanSnapshotRoot,
                cleanReleaseRoot,
                validateSnapshot,
                validateRelease,
                prepareSnapshot,
                assembleRelease,
                publishSnapshot
            );
        });

        project.getGradle().projectsEvaluated(ignored -> hideNonPublicPublishingTasks(project));

        // Apply after registering our root listener for the same lifecycle ordering used above.
        project.getPluginManager().apply("maven-publish");
        project.getPluginManager().apply("signing");
    }

    /** Can be used by version logic before publications are configured. */
    public static boolean isReleaseRequested(Project project) {
        String property = stringProperty(project, "publishPlugin.release");
        return Boolean.parseBoolean(property) || isAnyTaskRequested(
            project,
            "prepareRelease",
            "publishRelease",
            "uploadReleaseToMavenCentral"
        );
    }

    private static TaskProvider<ValidatePublicationsTask> validationTask(
        Project root,
        String name,
        boolean release
    ) {
        return root.getTasks().register(name, ValidatePublicationsTask.class, task -> {
            task.setDescription("Validates " + (release ? "release" : "snapshot") + " publication versions.");
            task.getRelease().set(release);
            task.getPublications().convention(Collections.emptyMap());
        });
    }

    private static Set<Project> resolvePublicationProjects(Project root, PublishExtension extension) {
        List<String> paths = extension.getModules().getOrElse(Collections.emptyList());
        if (paths.isEmpty()) {
            paths = Collections.singletonList(root.getPath());
        }

        Set<Project> projects = new LinkedHashSet<>();
        for (String path : paths) {
            Project selected = root.findProject(path);
            if (selected == null) {
                throw new GradleException("Publishing module does not exist: " + path);
            }
            projects.add(selected);
        }
        return projects;
    }

    private static void hideNonPublicPublishingTasks(Project root) {
        for (Project project : root.getAllprojects()) {
            project.getTasks().configureEach(task -> {
                if (project == root && PUBLIC_TASK_NAMES.contains(task.getName())) {
                    task.setGroup(TASK_GROUP);
                }
                else if ("publishing".equals(task.getGroup())) {
                    task.setGroup(null);
                }
            });
        }
    }

    private static void configurePublicationProject(
        Project project,
        Project root,
        PublishExtension extension,
        boolean releaseRequested,
        boolean localSnapshotRequested,
        TaskProvider<ValidatePublicationsTask> validateSnapshot,
        TaskProvider<ValidatePublicationsTask> validateRelease
    ) {
        configureJavaArtifacts(project, extension);
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);

        if (extension.getCreateJavaPublications().get()
            && publishing.getPublications().withType(MavenPublication.class).isEmpty()) {
            SoftwareComponent javaComponent = project.getComponents().findByName("java");
            if (javaComponent != null) {
                publishing.getPublications().create("maven", MavenPublication.class, publication -> {
                    publication.from(javaComponent);
                });
            }
        }

        publishing.getPublications().withType(MavenPublication.class).configureEach(
            publication -> configurePom(publication.getPom(), extension)
        );
        for (MavenPublication publication : publishing.getPublications().withType(MavenPublication.class)) {
            String identity = project.getPath() + ":" + publication.getName();
            String version = publication.getVersion();
            validateSnapshot.configure(task -> task.getPublications().put(identity, version));
            validateRelease.configure(task -> task.getPublications().put(identity, version));
        }

        RepositoryHandler repositories = publishing.getRepositories();
        MavenArtifactRepository repository = (MavenArtifactRepository) repositories.findByName(REPOSITORY_NAME);
        if (repository == null) {
            repository = repositories.maven(repo -> repo.setName(REPOSITORY_NAME));
        }
        if (releaseRequested) {
            repository.setUrl(extension.getReleaseDirectory());
        }
        else if (localSnapshotRequested) {
            repository.setUrl(extension.getSnapshotDirectory());
        }
        else {
            repository.setUrl(extension.getSnapshotRepositoryUrl());
            String username = System.getenv(extension.getUsernameEnvironmentVariable().get());
            String password = System.getenv(extension.getPasswordEnvironmentVariable().get());
            if (notBlank(username) && notBlank(password)) {
                String finalUsername = username;
                String finalPassword = password;
                repository.credentials(credentials -> {
                    credentials.setUsername(finalUsername);
                    credentials.setPassword(finalPassword);
                });
            }
        }

        configureSigning(project, publishing, extension);
    }

    private static void configureJavaArtifacts(Project project, PublishExtension extension) {
        project.getPlugins().withId("java", ignored -> {
            if (extension.getAddJavaDocumentationArtifacts().get()) {
                JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
                java.withSourcesJar();
                java.withJavadocJar();
            }
            project.getTasks().withType(Javadoc.class).configureEach(task -> {
                task.getOptions().setEncoding("UTF-8");
                if (task.getOptions() instanceof StandardJavadocDocletOptions options) {
                    options.addStringOption("Xdoclint:none", "-quiet");
                }
            });
        });
    }

    private static void configurePom(MavenPom pom, PublishExtension extension) {
        setIfNotBlank(pom.getName(), extension.getPomName().getOrElse(""));
        setIfNotBlank(pom.getDescription(), extension.getPomDescription().getOrElse(""));
        setIfNotBlank(pom.getUrl(), extension.getProjectUrl().getOrElse(""));

        if (notBlank(extension.getLicenseName().getOrElse(""))
            && notBlank(extension.getLicenseUrl().getOrElse(""))) {
            pom.licenses(licenses -> licenses.license(license -> {
                license.getName().set(extension.getLicenseName());
                license.getUrl().set(extension.getLicenseUrl());
            }));
        }
        if (notBlank(extension.getDeveloperId().getOrElse(""))
            || notBlank(extension.getDeveloperName().getOrElse(""))) {
            pom.developers(developers -> developers.developer(developer -> {
                setIfNotBlank(developer.getId(), extension.getDeveloperId().getOrElse(""));
                setIfNotBlank(developer.getName(), extension.getDeveloperName().getOrElse(""));
                setIfNotBlank(developer.getEmail(), extension.getDeveloperEmail().getOrElse(""));
            }));
        }
        if (notBlank(extension.getScmUrl().getOrElse(""))
            || notBlank(extension.getScmConnection().getOrElse(""))
            || notBlank(extension.getScmDeveloperConnection().getOrElse(""))) {
            pom.scm(scm -> {
                setIfNotBlank(scm.getUrl(), extension.getScmUrl().getOrElse(""));
                setIfNotBlank(scm.getConnection(), extension.getScmConnection().getOrElse(""));
                setIfNotBlank(
                    scm.getDeveloperConnection(),
                    extension.getScmDeveloperConnection().getOrElse("")
                );
            });
        }
    }

    private static void configureSigning(
        Project project,
        PublishingExtension publishing,
        PublishExtension extension
    ) {
        String key = System.getenv(extension.getSigningKeyEnvironmentVariable().get());
        String password = System.getenv(extension.getSigningPasswordEnvironmentVariable().get());
        if (notBlank(key) && notBlank(password)) {
            SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
            signing.useInMemoryPgpKeys(key, password);
            signing.sign(publishing.getPublications());
        }
    }

    private static void configureNestedBuilds(
        Project root,
        PublishExtension extension,
        TaskProvider<PrepareRepositoryTask> prepareSnapshot,
        TaskProvider<PrepareRepositoryTask> assembleRelease,
        TaskProvider<Task> publishSnapshot
    ) {
        for (NestedBuildSpec nested : extension.getNestedBuilds()) {
            if (!nested.getDirectory().isPresent()) {
                throw new GradleException("Nested build '" + nested.getName() + "' must define directory");
            }
            String suffix = toTaskSuffix(nested.getName());
            TaskProvider<GradleBuild> nestedSnapshot = nestedBuildTask(
                root,
                "prepare" + suffix + "Snapshot",
                nested,
                nested.getPrepareSnapshotTask().get(),
                "Prepares snapshot artifacts from nested build '" + nested.getName() + "'."
            );
            TaskProvider<GradleBuild> nestedRelease = nestedBuildTask(
                root,
                "prepare" + suffix + "Release",
                nested,
                nested.getPrepareReleaseTask().get(),
                "Prepares release artifacts from nested build '" + nested.getName() + "'."
            );
            TaskProvider<GradleBuild> nestedPublishSnapshot = nestedBuildTask(
                root,
                "publish" + suffix + "Snapshot",
                nested,
                nested.getPublishSnapshotTask().get(),
                "Publishes snapshot artifacts from nested build '" + nested.getName() + "'."
            );

            prepareSnapshot.configure(task -> {
                task.dependsOn(nestedSnapshot);
                task.getSourceDirectories().from(nested.getSnapshotDirectory());
            });
            assembleRelease.configure(task -> {
                task.dependsOn(nestedRelease);
                task.getSourceDirectories().from(nested.getReleaseDirectory());
            });
            publishSnapshot.configure(task -> task.dependsOn(nestedPublishSnapshot));
        }
    }

    private static TaskProvider<GradleBuild> nestedBuildTask(
        Project root,
        String taskName,
        NestedBuildSpec nested,
        String requestedTask,
        String description
    ) {
        return root.getTasks().register(taskName, GradleBuild.class, task -> {
            task.setDescription(description);
            task.setDir(nested.getDirectory().get().getAsFile());
            task.setTasks(Collections.singletonList(requestedTask));
        });
    }

    private static void wirePublicationTasks(
        Project root,
        Set<Project> publicationProjects,
        boolean releaseRequested,
        boolean localSnapshotRequested,
        TaskProvider<Delete> cleanSnapshotRoot,
        TaskProvider<Delete> cleanReleaseRoot,
        TaskProvider<ValidatePublicationsTask> validateSnapshot,
        TaskProvider<ValidatePublicationsTask> validateRelease,
        TaskProvider<PrepareRepositoryTask> prepareSnapshot,
        TaskProvider<PrepareRepositoryTask> assembleRelease,
        TaskProvider<Task> publishSnapshot
    ) {
        for (Project project : publicationProjects) {
            var publicationTasks = project.getTasks().withType(PublishToMavenRepository.class);
            publicationTasks.configureEach(task -> {
                if (releaseRequested) {
                    task.dependsOn(cleanReleaseRoot, validateRelease);
                }
                else if (localSnapshotRequested) {
                    task.dependsOn(cleanSnapshotRoot, validateSnapshot);
                }
                else {
                    task.dependsOn(validateSnapshot);
                }
            });
            if (releaseRequested) {
                assembleRelease.configure(aggregate -> aggregate.dependsOn(publicationTasks));
            }
            else if (localSnapshotRequested) {
                prepareSnapshot.configure(aggregate -> aggregate.dependsOn(publicationTasks));
            }
            else {
                publishSnapshot.configure(aggregate -> aggregate.dependsOn(publicationTasks));
            }
        }
    }

    private static void configureReleaseArchive(
        TaskProvider<Zip> prepareRelease,
        PublishExtension extension
    ) {
        File bundle = extension.getReleaseBundle().get().getAsFile();
        prepareRelease.configure(task -> {
            task.getDestinationDirectory().set(bundle.getParentFile());
            task.getArchiveFileName().set(bundle.getName());
        });
    }

    private static boolean isAnyTaskRequested(Project project, String... names) {
        Set<String> requested = project.getGradle().getStartParameter().getTaskNames().stream()
            .map(name -> name.substring(name.lastIndexOf(':') + 1))
            .collect(Collectors.toSet());
        return Arrays.stream(names).anyMatch(requested::contains);
    }

    private static String stringProperty(Project project, String name) {
        Object value = project.findProperty(name);
        return value == null ? "" : value.toString();
    }

    private static String toTaskSuffix(String name) {
        String[] parts = name.split("[^A-Za-z0-9]+");
        return Arrays.stream(parts)
            .filter(part -> !part.isEmpty())
            .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
            .collect(Collectors.joining());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void setIfNotBlank(org.gradle.api.provider.Property<String> property, String value) {
        if (notBlank(value)) {
            property.set(value);
        }
    }
}
