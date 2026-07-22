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

val libraryVersion = "1.0.0"

easyPublishing {
    modules(":core", ":desktop")

    groupId.set("com.example.library")
    releaseVersion.set(libraryVersion)
    snapshotVersion.set("$libraryVersion-SNAPSHOT")

    snapshotRepositoryUrl.set("https://central.sonatype.com/repository/maven-snapshots/")
    releaseRepositoryUrl.set("https://central.sonatype.com")
    username.set(providers.environmentVariable("CENTRAL_PORTAL_USERNAME"))
    password.set(providers.environmentVariable("CENTRAL_PORTAL_PASSWORD"))
    signingKey.set(providers.environmentVariable("SIGNING_KEY"))
    signingPassword.set(providers.environmentVariable("SIGNING_PASSWORD"))

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
EasyPublishing assigns `groupId` and the selected lifecycle version to the selected projects; no root
`allprojects` coordinate block is needed.

`releaseVersion` is always the release version and must not end with `-SNAPSHOT`.
`snapshotVersion` is always the snapshot version. Both snapshot forms are supported:

```kotlin
releaseVersion.set("1.2.3")
snapshotVersion.set("-SNAPSHOT")
// or
snapshotVersion.set("1.2.3-SNAPSHOT")
```

EasyPublishing selects `snapshotVersion` for snapshot tasks and `releaseVersion` for release tasks, so
build scripts do not need conditional version logic. Requesting snapshot and release publishing
tasks together in one Gradle invocation is rejected because one project cannot have both versions
at once.

For a single-project build, omit `modules`; the root project is selected automatically.

```shell
./gradlew prepareSnapshot  # Create build/snapshot-deploy
./gradlew publishSnapshot  # Publish the snapshot
./gradlew prepareRelease   # Create build/staging-deploy
./gradlew publishRelease   # Publish the release to the configured provider
```

EasyPublishing does not select Sonatype or any other provider by default. For Maven Central,
configure `snapshotRepositoryUrl` and `releaseRepositoryUrl` explicitly. Credential and signing
properties contain their actual values; EasyPublishing does not inspect environment variables.
The example asks Gradle's Provider API to read `CENTRAL_PORTAL_USERNAME`,
`CENTRAL_PORTAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD` and pass their values to the
plugin.

## Other Maven Repositories

Snapshots can be sent to any Maven-compatible repository by changing
`snapshotRepositoryUrl`. To publish releases directly instead of using the Maven Central
Portal bundle API, set `releaseRepositoryUrl`:

```kotlin
easyPublishing {
    snapshotRepositoryUrl.set("https://packages.example.com/maven/snapshots")
    releaseRepositoryUrl.set("https://packages.example.com/maven/releases")

    username.set(providers.environmentVariable("MAVEN_REPOSITORY_USERNAME"))
    password.set(providers.environmentVariable("MAVEN_REPOSITORY_TOKEN"))
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
`prepareRelease` creates the `build/staging-deploy` repository directory without uploading or
creating an archive. For Maven Central only, `publishRelease` creates
`build/staging-deploy.zip` internally immediately before uploading it. Direct Maven-repository
releases do not create a ZIP.

`publishSnapshot` requires `snapshotRepositoryUrl`. The local-only `prepareSnapshot` and
`prepareRelease` tasks do not require a remote provider.

Gradle can supply credential values from any provider. For example, a build can use Gradle
properties instead of environment variables:

```kotlin
easyPublishing {
    username.set(providers.gradleProperty("publishingUsername"))
    password.set(providers.gradleProperty("publishingPassword"))
    signingKey.set(providers.gradleProperty("publishingSigningKey"))
    signingPassword.set(providers.gradleProperty("publishingSigningPassword"))
}
```

Repository coordinates and URLs also have `easyPublishing.*` Gradle-property conventions:

```properties
easyPublishing.groupId=com.example.library
easyPublishing.releaseVersion=1.2.3
easyPublishing.snapshotVersion=1.2.3-SNAPSHOT
easyPublishing.snapshotRepositoryUrl=https://packages.example.com/maven/snapshots
easyPublishing.releaseRepositoryUrl=https://packages.example.com/maven/releases
```

Each nested build configures its own credential providers. EasyPublishing propagates coordinates
and repository settings to nested builds, but does not add its credential or signing values to
the nested GradleBuild project-property map.
