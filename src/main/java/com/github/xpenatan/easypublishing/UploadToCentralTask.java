package com.github.xpenatan.easypublishing;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/** Uploads an assembled Maven repository bundle to the Central Publisher Portal. */
@DisableCachingByDefault(because = "Uploading a deployment is an external side effect")
public abstract class UploadToCentralTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBundleFile();

    @Input
    public abstract Property<String> getReleaseRepositoryUrl();

    @Input
    public abstract Property<String> getDeploymentName();

    @Input
    public abstract Property<Boolean> getAutomaticRelease();

    @Input
    public abstract Property<String> getUsernameEnvironmentVariable();

    @Input
    public abstract Property<String> getPasswordEnvironmentVariable();

    @Input
    public abstract Property<String> getSigningKeyEnvironmentVariable();

    @Input
    public abstract Property<String> getSigningPasswordEnvironmentVariable();

    @Input
    public abstract Property<Boolean> getRequireSigning();

    @Internal
    protected String getUsername() {
        String name = getUsernameEnvironmentVariable().getOrElse("");
        return name.isBlank() ? null : System.getenv(name);
    }

    @Internal
    protected String getPassword() {
        String name = getPasswordEnvironmentVariable().getOrElse("");
        return name.isBlank() ? null : System.getenv(name);
    }

    @TaskAction
    public void upload() throws Exception {
        File bundle = getBundleFile().get().getAsFile();
        if (!bundle.isFile() || !bundle.canRead()) {
            throw new GradleException("Release bundle is missing or unreadable: " + bundle);
        }

        String releaseRepositoryUrl = requireConfigured(
            getReleaseRepositoryUrl().getOrElse(""),
            "easyPublishing.releaseRepositoryUrl"
        );
        String usernameVariable = requireConfigured(
            getUsernameEnvironmentVariable().getOrElse(""),
            "easyPublishing.usernameEnvironmentVariable"
        );
        String passwordVariable = requireConfigured(
            getPasswordEnvironmentVariable().getOrElse(""),
            "easyPublishing.passwordEnvironmentVariable"
        );
        String username = requireEnvironmentVariable(usernameVariable);
        String password = requireEnvironmentVariable(passwordVariable);
        if (getRequireSigning().get()) {
            requireEnvironmentVariable(requireConfigured(
                getSigningKeyEnvironmentVariable().getOrElse(""),
                "easyPublishing.signingKeyEnvironmentVariable"
            ));
            requireEnvironmentVariable(requireConfigured(
                getSigningPasswordEnvironmentVariable().getOrElse(""),
                "easyPublishing.signingPasswordEnvironmentVariable"
            ));
        }

        String publishingType = getAutomaticRelease().get() ? "AUTOMATIC" : "USER_MANAGED";
        String baseUrl = releaseRepositoryUrl.replaceAll("/+$", "");
        String endpoint = baseUrl + "/api/v1/publisher/upload?name="
            + URLEncoder.encode(getDeploymentName().get(), StandardCharsets.UTF_8)
            + "&publishingType=" + publishingType;

        String boundary = "----easy-publishing-" + UUID.randomUUID();
        String prefix = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"bundle\"; filename=\""
            + bundle.getName().replace("\"", "") + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        String token = Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prefix),
                HttpRequest.BodyPublishers.ofFile(bundle.toPath()),
                HttpRequest.BodyPublishers.ofString(suffix)
            ))
            .build();

        HttpResponse<String> response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GradleException(
                "Central Portal upload failed with HTTP " + response.statusCode() + ": " + response.body()
            );
        }

        getLogger().lifecycle(
            "Central Portal accepted deployment {} (publishing type: {}).",
            response.body().trim(),
            publishingType
        );
    }

    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new GradleException(name + " environment variable is not set");
        }
        return value;
    }

    private static String requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new GradleException(propertyName + " must be configured");
        }
        return value;
    }
}
