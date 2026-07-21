# PublishPlugin

PublishPlugin is a Gradle plugin for preparing and publishing Maven snapshots and releases. It supports multi-module projects, nested Gradle builds, Maven POM metadata, source and Javadoc artifacts, signing, local deployment preparation, and publishing through the Maven Central Portal.

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
    id("com.github.xpenatan.publish") version "-SNAPSHOT"
}

val releaseRequested = rootProject.extra["publishPlugin.releaseRequested"] as Boolean
val libraryVersion = "1.0.0"

allprojects {
    group = "com.example.library"
    version = if(releaseRequested) libraryVersion else "$libraryVersion-SNAPSHOT"
}

publishPlugin {
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

For a single-project build, omit `modules`; the root project is selected automatically.

```shell
./gradlew prepareSnapshot  # Create build/snapshot-deploy
./gradlew publishSnapshot  # Publish the snapshot
./gradlew prepareRelease   # Create build/staging-deploy.zip
./gradlew publishRelease   # Upload the release bundle
```

Publishing uses `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD` from the environment.
