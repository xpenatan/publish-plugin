package com.github.xpenatan.publish;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.Arrays;

/** Configuration for the {@code com.github.xpenatan.publish} plugin. */
public abstract class PublishExtension {
    private final NamedDomainObjectContainer<NestedBuildSpec> nestedBuilds;

    @Inject
    public PublishExtension(Project project, ObjectFactory objects) {
        nestedBuilds = objects.domainObjectContainer(
            NestedBuildSpec.class,
            name -> objects.newInstance(NestedBuildSpec.class, name)
        );

        getPomName().convention(project.provider(project::getName));
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

        getSnapshotRepositoryUrl().convention("https://central.sonatype.com/repository/maven-snapshots/");
        getCentralPortalUrl().convention("https://central.sonatype.com");
        getUsernameEnvironmentVariable().convention("CENTRAL_PORTAL_USERNAME");
        getPasswordEnvironmentVariable().convention("CENTRAL_PORTAL_PASSWORD");
        getSigningKeyEnvironmentVariable().convention("SIGNING_KEY");
        getSigningPasswordEnvironmentVariable().convention("SIGNING_PASSWORD");
        getAutomaticRelease().convention(false);
        getRequireSigningForUpload().convention(true);
        getCreateJavaPublications().convention(true);
        getAddJavaDocumentationArtifacts().convention(true);

        getSnapshotDirectory().convention(project.getLayout().getBuildDirectory().dir("snapshot-deploy"));
        getReleaseDirectory().convention(project.getLayout().getBuildDirectory().dir("staging-deploy"));
        getReleaseBundle().convention(project.getLayout().getBuildDirectory().file("staging-deploy.zip"));
        getDeploymentName().convention(project.provider(
            () -> getPomName().get() + "-" + project.getVersion()
        ));
    }

    /** Selects projects that contain Maven publications. Defaults to the root project. */
    public void modules(String... projectPaths) {
        getModules().set(Arrays.asList(projectPaths));
    }

    public void nestedBuild(String name, Action<? super NestedBuildSpec> action) {
        action.execute(nestedBuilds.maybeCreate(name));
    }

    public abstract ListProperty<String> getModules();

    public NamedDomainObjectContainer<NestedBuildSpec> getNestedBuilds() {
        return nestedBuilds;
    }

    public abstract Property<String> getPomName();

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

    public abstract Property<String> getSnapshotRepositoryUrl();

    public abstract Property<String> getCentralPortalUrl();

    public abstract Property<String> getUsernameEnvironmentVariable();

    public abstract Property<String> getPasswordEnvironmentVariable();

    public abstract Property<String> getSigningKeyEnvironmentVariable();

    public abstract Property<String> getSigningPasswordEnvironmentVariable();

    public abstract Property<Boolean> getAutomaticRelease();

    public abstract Property<Boolean> getRequireSigningForUpload();

    public abstract Property<Boolean> getCreateJavaPublications();

    public abstract Property<Boolean> getAddJavaDocumentationArtifacts();

    public abstract DirectoryProperty getSnapshotDirectory();

    public abstract DirectoryProperty getReleaseDirectory();

    public abstract RegularFileProperty getReleaseBundle();

    public abstract Property<String> getDeploymentName();
}

