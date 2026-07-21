package com.github.xpenatan.publish;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates publication versions without retaining Gradle model objects at execution time. */
@DisableCachingByDefault(because = "Version validation has no output")
public abstract class ValidatePublicationsTask extends DefaultTask {
    @Input
    public abstract MapProperty<String, String> getPublications();

    @Input
    public abstract Property<Boolean> getRelease();

    @TaskAction
    public void validateVersions() {
        Map<String, String> publications = getPublications().get();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> entry : publications.entrySet()) {
            String version = entry.getValue();
            boolean snapshot = version != null && version.endsWith("-SNAPSHOT");
            if (version == null || version.isBlank() || "unspecified".equals(version)) {
                errors.add(entry.getKey() + " has no version");
            }
            else if (getRelease().get() && snapshot) {
                errors.add(entry.getKey() + " uses snapshot version " + version);
            }
            else if (!getRelease().get() && !snapshot) {
                errors.add(entry.getKey() + " uses release version " + version);
            }
        }

        if (publications.isEmpty()) {
            errors.add("no Maven publications were found in the configured modules");
        }
        if (!errors.isEmpty()) {
            throw new GradleException(
                "Cannot prepare " + (getRelease().get() ? "release" : "snapshot") + ":\n - "
                    + String.join("\n - ", errors)
            );
        }
    }
}

