package com.github.xpenatan.easypublishing;

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

/** Reusable snapshot and release publishing workflow for Maven-compatible repositories. */
public class EasyPublishingPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "easyPublishing";
    public static final String RELEASE_REQUESTED_EXTRA = "easyPublishing.releaseRequested";
    private static final String TASK_GROUP = "easy-publishing";
    private static final String REPOSITORY_NAME = "easyPublishing";
    private static final String RELEASE_REPOSITORY_NAME = "easyPublishingRelease";
    private static final String PUBLISH_TASK_SUFFIX = "ToEasyPublishingRepository";
    private static final String RELEASE_PUBLISH_TASK_SUFFIX = "ToEasyPublishingReleaseRepository";
    private static final String RELEASE_REPOSITORY_URL_PROPERTY = "easyPublishing.releaseRepositoryUrl";
    private static final String USERNAME_ENVIRONMENT_PROPERTY =
        "easyPublishing.usernameEnvironmentVariable";
    private static final String PASSWORD_ENVIRONMENT_PROPERTY =
        "easyPublishing.passwordEnvironmentVariable";
    private static final String SIGNING_KEY_ENVIRONMENT_PROPERTY =
        "easyPublishing.signingKeyEnvironmentVariable";
    private static final String SIGNING_PASSWORD_ENVIRONMENT_PROPERTY =
        "easyPublishing.signingPasswordEnvironmentVariable";
    private static final String ALLOW_INSECURE_PROTOCOL_PROPERTY =
        "easyPublishing.allowInsecureProtocol";
    private static final Set<String> PUBLIC_TASK_NAMES = Set.of(
        "prepareSnapshot",
        "prepareRelease",
        "publishSnapshot",
        "publishRelease"
    );

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("com.github.xpenatan.easy-publishing must be applied to the root project");
        }

        boolean releaseRequested = isReleaseRequested(project);
        boolean localSnapshotRequested = isAnyTaskRequested(project, "prepareSnapshot");
        project.getExtensions().getExtraProperties().set(RELEASE_REQUESTED_EXTRA, releaseRequested);

        EasyPublishingExtension extension = project.getExtensions().create(
            EXTENSION_NAME,
            EasyPublishingExtension.class,
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
            task.setDescription("Prepares and zips a local Maven release repository without uploading it.");
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
            task.setDescription("Publishes all release publications to the configured provider.");
        });

        project.afterEvaluate(ignored -> {
            configureReleaseArchive(prepareRelease, extension);
            boolean directRelease = notBlank(extension.getReleaseRepositoryUrl().getOrElse(""));
            if (directRelease) {
                publishRelease.configure(task -> task.setDescription(
                    "Publishes all release publications to the configured Maven repository."
                ));
            }
            else {
                publishRelease.configure(task -> {
                    task.setDescription("Prepares and uploads a release bundle to the Central Publisher Portal.");
                    task.dependsOn(uploadRelease);
                });
            }
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
                publishSnapshot,
                publishRelease,
                directRelease
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
                publishSnapshot,
                publishRelease
            );
        });

        project.getGradle().projectsEvaluated(ignored -> hideNonPublicPublishingTasks(project));

        // Apply after registering our root listener for the same lifecycle ordering used above.
        project.getPluginManager().apply("maven-publish");
        project.getPluginManager().apply("signing");
    }

    /** Can be used by version logic before publications are configured. */
    public static boolean isReleaseRequested(Project project) {
        String property = stringProperty(project, "easyPublishing.release");
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

    private static Set<Project> resolvePublicationProjects(Project root, EasyPublishingExtension extension) {
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
        EasyPublishingExtension extension,
        boolean releaseRequested,
        boolean localSnapshotRequested,
        TaskProvider<ValidatePublicationsTask> validateSnapshot,
        TaskProvider<ValidatePublicationsTask> validateRelease
    ) {
        configureJavaArtifacts(project, extension);
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);

        boolean gradlePluginProject = project.getPlugins().hasPlugin("java-gradle-plugin");
        if (!gradlePluginProject
            && publishing.getPublications().withType(MavenPublication.class).isEmpty()) {
            SoftwareComponent javaComponent = project.getComponents().findByName("java");
            if (javaComponent != null) {
                publishing.getPublications().create("maven", MavenPublication.class, publication -> {
                    publication.from(javaComponent);
                });
            }
        }

        publishing.getPublications().withType(MavenPublication.class).configureEach(publication -> {
            configurePom(publication.getPom(), extension);
            String identity = project.getPath() + ":" + publication.getName();
            String version = publication.getVersion();
            validateSnapshot.configure(task -> task.getPublications().put(identity, version));
            validateRelease.configure(task -> task.getPublications().put(identity, version));
        });

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
            configureRemoteRepository(repository, extension.getSnapshotRepositoryUrl().get(), extension);
        }

        if (releaseRequested && notBlank(extension.getReleaseRepositoryUrl().getOrElse(""))) {
            MavenArtifactRepository releaseRepository =
                (MavenArtifactRepository) repositories.findByName(RELEASE_REPOSITORY_NAME);
            if (releaseRepository == null) {
                releaseRepository = repositories.maven(repo -> repo.setName(RELEASE_REPOSITORY_NAME));
            }
            configureRemoteRepository(
                releaseRepository,
                extension.getReleaseRepositoryUrl().get(),
                extension
            );
        }

        configureSigning(project, publishing, extension);
    }

    private static void configureJavaArtifacts(Project project, EasyPublishingExtension extension) {
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

    private static void configurePom(MavenPom pom, EasyPublishingExtension extension) {
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
        EasyPublishingExtension extension
    ) {
        String key = System.getenv(extension.getSigningKeyEnvironmentVariable().get());
        String password = System.getenv(extension.getSigningPasswordEnvironmentVariable().get());
        if (notBlank(key) && notBlank(password)) {
            SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
            signing.useInMemoryPgpKeys(key, password);
            signing.sign(publishing.getPublications());
        }
    }

    private static void configureRemoteRepository(
        MavenArtifactRepository repository,
        String url,
        EasyPublishingExtension extension
    ) {
        repository.setUrl(url);
        repository.setAllowInsecureProtocol(extension.getAllowInsecureProtocol().get());

        String username = System.getenv(extension.getUsernameEnvironmentVariable().get());
        String password = System.getenv(extension.getPasswordEnvironmentVariable().get());
        if (notBlank(username) && notBlank(password)) {
            repository.credentials(credentials -> {
                credentials.setUsername(username);
                credentials.setPassword(password);
            });
        }
    }

    private static void configureNestedBuilds(
        Project root,
        EasyPublishingExtension extension,
        TaskProvider<PrepareRepositoryTask> prepareSnapshot,
        TaskProvider<PrepareRepositoryTask> assembleRelease,
        TaskProvider<Task> publishSnapshot,
        TaskProvider<Task> publishRelease,
        boolean directRelease
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
            if (directRelease) {
                TaskProvider<GradleBuild> nestedPublishRelease = nestedBuildTask(
                    root,
                    "publish" + suffix + "Release",
                    nested,
                    nested.getPublishReleaseTask().get(),
                    "Publishes release artifacts from nested build '" + nested.getName() + "'."
                );
                nestedPublishRelease.configure(task ->
                    configureNestedReleaseProperties(task, extension)
                );
                publishRelease.configure(task -> task.dependsOn(nestedPublishRelease));
            }

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

    private static void configureNestedReleaseProperties(
        GradleBuild task,
        EasyPublishingExtension extension
    ) {
        var properties = new java.util.LinkedHashMap<>(task.getStartParameter().getProjectProperties());
        properties.put(RELEASE_REPOSITORY_URL_PROPERTY, extension.getReleaseRepositoryUrl().get());
        properties.put(USERNAME_ENVIRONMENT_PROPERTY, extension.getUsernameEnvironmentVariable().get());
        properties.put(PASSWORD_ENVIRONMENT_PROPERTY, extension.getPasswordEnvironmentVariable().get());
        properties.put(SIGNING_KEY_ENVIRONMENT_PROPERTY, extension.getSigningKeyEnvironmentVariable().get());
        properties.put(
            SIGNING_PASSWORD_ENVIRONMENT_PROPERTY,
            extension.getSigningPasswordEnvironmentVariable().get()
        );
        properties.put(
            ALLOW_INSECURE_PROTOCOL_PROPERTY,
            extension.getAllowInsecureProtocol().get().toString()
        );
        task.getStartParameter().setProjectProperties(properties);
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
        TaskProvider<Task> publishSnapshot,
        TaskProvider<Task> publishRelease
    ) {
        for (Project project : publicationProjects) {
            var publicationTasks = project.getTasks().withType(PublishToMavenRepository.class);
            var stagingTasks = publicationTasks.matching(
                task -> task.getName().endsWith(PUBLISH_TASK_SUFFIX)
            );
            var directReleaseTasks = publicationTasks.matching(
                task -> task.getName().endsWith(RELEASE_PUBLISH_TASK_SUFFIX)
            );

            if (releaseRequested) {
                stagingTasks.configureEach(task -> task.dependsOn(cleanReleaseRoot, validateRelease));
                directReleaseTasks.configureEach(task -> task.dependsOn(validateRelease));
                assembleRelease.configure(aggregate -> aggregate.dependsOn(stagingTasks));
                publishRelease.configure(aggregate -> aggregate.dependsOn(directReleaseTasks));
            }
            else if (localSnapshotRequested) {
                stagingTasks.configureEach(task -> task.dependsOn(cleanSnapshotRoot, validateSnapshot));
                prepareSnapshot.configure(aggregate -> aggregate.dependsOn(stagingTasks));
            }
            else {
                stagingTasks.configureEach(task -> task.dependsOn(validateSnapshot));
                publishSnapshot.configure(aggregate -> aggregate.dependsOn(stagingTasks));
            }
        }
    }

    private static void configureReleaseArchive(
        TaskProvider<Zip> prepareRelease,
        EasyPublishingExtension extension
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
