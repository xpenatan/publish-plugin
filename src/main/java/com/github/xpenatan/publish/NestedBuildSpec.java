package com.github.xpenatan.publish;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** A separately invoked Gradle build whose artifacts belong in the same deployment. */
public abstract class NestedBuildSpec implements Named {
    private final String name;

    @Inject
    public NestedBuildSpec(String name, ObjectFactory objects) {
        this.name = name;
        getPrepareSnapshotTask().convention("prepareSnapshot");
        getPublishSnapshotTask().convention("publishSnapshot");
        getPrepareReleaseTask().convention("prepareRelease");
        getSnapshotDirectory().convention(getDirectory().dir("build/snapshot-deploy"));
        getReleaseDirectory().convention(getDirectory().dir("build/staging-deploy"));
    }

    @Override
    public String getName() {
        return name;
    }

    public abstract DirectoryProperty getDirectory();

    public abstract DirectoryProperty getSnapshotDirectory();

    public abstract DirectoryProperty getReleaseDirectory();

    public abstract Property<String> getPrepareSnapshotTask();

    public abstract Property<String> getPublishSnapshotTask();

    public abstract Property<String> getPrepareReleaseTask();
}

