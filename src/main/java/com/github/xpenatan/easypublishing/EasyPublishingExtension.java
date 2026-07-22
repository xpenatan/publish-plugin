package com.github.xpenatan.easypublishing;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;

/** Configuration for the {@code com.github.xpenatan.easy-publishing} plugin. */
public abstract class EasyPublishingExtension {
    private final NamedDomainObjectContainer<NestedBuildSpec> nestedBuilds;

    @Inject
    public EasyPublishingExtension(Project project, ObjectFactory objects) {
        nestedBuilds = objects.domainObjectContainer(
            NestedBuildSpec.class,
            name -> objects.newInstance(NestedBuildSpec.class, name)
        );

        getPomName().convention(project.provider(project::getName));
        getGroupId().convention(
            project.getProviders().gradleProperty("easyPublishing.groupId").orElse("")
        );
        getReleaseVersion().convention(
            project.getProviders().gradleProperty("easyPublishing.releaseVersion").orElse("")
        );
        getSnapshotVersion().convention(
            project.getProviders().gradleProperty("easyPublishing.snapshotVersion").orElse("")
        );
        getPomDescription().convention(project.provider(() -> {
            String description = project.getDescription();
            return description == null ? project.getName() : description;
        }));
        getProjectUrl().convention("");
        getScmUrl().convention("");
        getScmConnection().convention("");
        getScmDeveloperConnection().convention("");
        getLicenseName().convention("The Apache License, Version 2.0");
        getLicenseUrl().convention("https://www.apache.org/licenses/LICENSE-2.0.txt");
        getDeveloperId().convention("");
        getDeveloperName().convention("");
        getDeveloperEmail().convention("");

        getSnapshotRepositoryUrl().convention(
            project.getProviders().gradleProperty("easyPublishing.snapshotRepositoryUrl").orElse("")
        );
        getReleaseRepositoryUrl().convention(
            project.getProviders().gradleProperty("easyPublishing.releaseRepositoryUrl").orElse("")
        );
        getUsername().convention("");
        getPassword().convention("");
        getSigningKey().convention("");
        getSigningPassword().convention("");
        getAllowInsecureProtocol().convention(
            project.getProviders().gradleProperty("easyPublishing.allowInsecureProtocol")
                .map(Boolean::parseBoolean)
                .orElse(false)
        );
        getAutomaticRelease().convention(false);
        getRequireSigningForUpload().convention(true);
        getAddJavaDocumentationArtifacts().convention(true);

        getSnapshotDirectory().convention(project.getLayout().getBuildDirectory().dir("snapshot-deploy"));
        getReleaseDirectory().convention(project.getLayout().getBuildDirectory().dir("staging-deploy"));
        getReleaseBundle().convention(project.getLayout().getBuildDirectory().file("staging-deploy.zip"));
        getDeploymentName().convention(project.provider(
            () -> getPomName().get() + "-" + getReleaseVersion().get()
        ));
    }

    /** Selects projects that contain Maven publications. Defaults to the root project. */
    public void modules(String... projectPaths) {
        getModules().set(Arrays.asList(projectPaths));
    }

    /** Selects projects from an existing collection, which is convenient for dynamic Kotlin DSL lists. */
    public void modules(Iterable<String> projectPaths) {
        ArrayList<String> paths = new ArrayList<>();
        projectPaths.forEach(paths::add);
        getModules().set(paths);
    }

    public void nestedBuild(String name, Action<? super NestedBuildSpec> action) {
        action.execute(nestedBuilds.maybeCreate(name));
    }

    public abstract ListProperty<String> getModules();

    public NamedDomainObjectContainer<NestedBuildSpec> getNestedBuilds() {
        return nestedBuilds;
    }

    public abstract Property<String> getPomName();

    /** Required Maven group ID assigned to every selected publication project. */
    public abstract Property<String> getGroupId();

    /** Required release version. Must not end with {@code -SNAPSHOT}. */
    public abstract Property<String> getReleaseVersion();

    /** Required snapshot version. Supports {@code -SNAPSHOT} and {@code <version>-SNAPSHOT}. */
    public abstract Property<String> getSnapshotVersion();

    public abstract Property<String> getPomDescription();

    public abstract Property<String> getProjectUrl();

    public abstract Property<String> getScmUrl();

    public abstract Property<String> getScmConnection();

    public abstract Property<String> getScmDeveloperConnection();

    public abstract Property<String> getLicenseName();

    public abstract Property<String> getLicenseUrl();

    public abstract Property<String> getDeveloperId();

    public abstract Property<String> getDeveloperName();

    public abstract Property<String> getDeveloperEmail();

    /** Required by publishSnapshot. No repository provider is selected by default. */
    public abstract Property<String> getSnapshotRepositoryUrl();

    /** Required by publishRelease. Maven Central URLs use its Portal API; other URLs use Maven publishing. */
    public abstract Property<String> getReleaseRepositoryUrl();

    /** Repository username value. The build decides how this value is supplied. */
    public abstract Property<String> getUsername();

    /** Repository password or token value. The build decides how this value is supplied. */
    public abstract Property<String> getPassword();

    /** ASCII-armored in-memory PGP private key value. */
    public abstract Property<String> getSigningKey();

    /** Password for the configured in-memory PGP private key. */
    public abstract Property<String> getSigningPassword();

    public abstract Property<Boolean> getAllowInsecureProtocol();

    public abstract Property<Boolean> getAutomaticRelease();

    public abstract Property<Boolean> getRequireSigningForUpload();

    public abstract Property<Boolean> getAddJavaDocumentationArtifacts();

    public abstract DirectoryProperty getSnapshotDirectory();

    public abstract DirectoryProperty getReleaseDirectory();

    public abstract RegularFileProperty getReleaseBundle();

    public abstract Property<String> getDeploymentName();
}
