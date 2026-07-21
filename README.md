# EasyPublishing

EasyPublishing is a Gradle plugin for preparing and publishing Maven snapshots and releases. It supports multi-module projects, nested Gradle builds, Maven POM metadata, source and Javadoc artifacts, signing, local deployment preparation, any Maven-compatible repository, and the Maven Central Portal.

## Usage

Add the Sonatype snapshot repository to `settings.gradle.kts` so Gradle can resolve the plugin marker:

```kotlin
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Apply and configure the plugin in the root `build.gradle.kts`:

```kotlin
plugins {
    id("com.github.xpenatan.easy-publishing") version "-SNAPSHOT"
}

val releaseRequested = rootProject.extra["easyPublishing.releaseRequested"] as Boolean
val libraryVersion = "1.0.0"

allprojects {
    group = "com.example.library"
    version = if(releaseRequested) libraryVersion else "$libraryVersion-SNAPSHOT"
}

easyPublishing {
    modules(":core", ":desktop")

    pomName.set("Example Library")
    pomDescription.set("An example Java library")
    projectUrl.set("https://github.com/example/example-library")

    developerId.set("developer")
    developerName.set("Developer Name")

    scmUrl.set("https://github.com/example/example-library")
    scmConnection.set("scm:git:https://github.com/example/example-library.git")
    scmDeveloperConnection.set("scm:git:ssh://git@github.com/example/example-library.git")
}
```

`modules` accepts either project paths directly or an existing list. Dynamic builds can use
`modules(publishingModules)` without Kotlin spread or array conversion syntax.

For a single-project build, omit `modules`; the root project is selected automatically.

```shell
./gradlew prepareSnapshot  # Create build/snapshot-deploy
./gradlew publishSnapshot  # Publish the snapshot
./gradlew prepareRelease   # Create build/staging-deploy.zip
./gradlew publishRelease   # Publish the release to the configured provider
```

The default Maven Central workflow uses `CENTRAL_PORTAL_USERNAME`,
`CENTRAL_PORTAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD` from the environment.

## Other Maven Repositories

Snapshots can be sent to any Maven-compatible repository by changing
`snapshotRepositoryUrl`. To publish releases directly instead of using the Maven Central
Portal bundle API, set `releaseRepositoryUrl`:

```kotlin
easyPublishing {
    snapshotRepositoryUrl.set("https://packages.example.com/maven/snapshots")
    releaseRepositoryUrl.set("https://packages.example.com/maven/releases")

    usernameEnvironmentVariable.set("MAVEN_REPOSITORY_USERNAME")
    passwordEnvironmentVariable.set("MAVEN_REPOSITORY_TOKEN")
}
```

The token is supplied as the repository password, which works with standard
username/password, deploy-token, and personal-access-token Maven repositories. Public and
local `file:` repositories do not require credentials. For an explicitly trusted plain-HTTP
repository, also set `allowInsecureProtocol.set(true)`.

When `releaseRepositoryUrl` is configured, `publishRelease` uploads the POM, JARs, Gradle
metadata, signatures, and checksums directly through Gradle's Maven publisher. If it is not
configured, `publishRelease` keeps the default Maven Central Portal ZIP workflow.
`prepareRelease` always creates `build/staging-deploy.zip` without uploading anything.

The same settings can be supplied on the command line or in `gradle.properties`, which is
especially useful for nested builds:

```properties
easyPublishing.releaseRepositoryUrl=https://packages.example.com/maven/releases
easyPublishing.usernameEnvironmentVariable=MAVEN_REPOSITORY_USERNAME
easyPublishing.passwordEnvironmentVariable=MAVEN_REPOSITORY_TOKEN
```
