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

easyPublishing {
    modules(":core", ":desktop")

    groupId.set("com.example.library")
    version.set(if(releaseRequested) libraryVersion else "$libraryVersion-SNAPSHOT")

    snapshotRepositoryUrl.set("https://central.sonatype.com/repository/maven-snapshots/")
    releaseRepositoryUrl.set("https://central.sonatype.com")
    usernameEnvironmentVariable.set("CENTRAL_PORTAL_USERNAME")
    passwordEnvironmentVariable.set("CENTRAL_PORTAL_PASSWORD")
    signingKeyEnvironmentVariable.set("SIGNING_KEY")
    signingPasswordEnvironmentVariable.set("SIGNING_PASSWORD")

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
EasyPublishing assigns `groupId` and `version` to the selected projects; no root
`allprojects` coordinate block is needed.

`version` is the exact publication version. Both snapshot forms are supported:

```kotlin
version.set("-SNAPSHOT")
// or
version.set("1.2.3-SNAPSHOT")
```

Use a non-snapshot value such as `version.set("1.2.3")` for a release. The example above
switches automatically based on whether a snapshot or release task was requested. Replace
`"$libraryVersion-SNAPSHOT"` with `"-SNAPSHOT"` there to use the literal snapshot form.

For a single-project build, omit `modules`; the root project is selected automatically.

```shell
./gradlew prepareSnapshot  # Create build/snapshot-deploy
./gradlew publishSnapshot  # Publish the snapshot
./gradlew prepareRelease   # Create build/staging-deploy.zip
./gradlew publishRelease   # Publish the release to the configured provider
```

EasyPublishing does not select Sonatype or any other provider by default. For Maven Central,
configure `snapshotRepositoryUrl` and `releaseRepositoryUrl` explicitly, together with the names
of the environment variables that contain the credentials and signing material. The example
above reads `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD`, `SIGNING_KEY`, and
`SIGNING_PASSWORD` from the environment.

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

`publishRelease` requires `releaseRepositoryUrl`. When it points to
`https://central.sonatype.com`, EasyPublishing uses the Maven Central Portal ZIP workflow.
Every other release URL is treated as a Maven-compatible repository and receives the POM,
JARs, Gradle metadata, signatures, and checksums directly through Gradle's Maven publisher.
`prepareRelease` always creates `build/staging-deploy.zip` without uploading anything.

`publishSnapshot` requires `snapshotRepositoryUrl`. The local-only `prepareSnapshot` and
`prepareRelease` tasks do not require a remote provider.

The same settings can be supplied on the command line or in `gradle.properties`, which is
especially useful for nested builds:

```properties
easyPublishing.groupId=com.example.library
easyPublishing.version=1.2.3-SNAPSHOT
easyPublishing.snapshotRepositoryUrl=https://packages.example.com/maven/snapshots
easyPublishing.releaseRepositoryUrl=https://packages.example.com/maven/releases
easyPublishing.usernameEnvironmentVariable=MAVEN_REPOSITORY_USERNAME
easyPublishing.passwordEnvironmentVariable=MAVEN_REPOSITORY_TOKEN
```
