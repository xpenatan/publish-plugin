import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
}

group = providers.gradleProperty("group").get()

val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()
fun isTaskRequested(vararg names: String) = names.any(requestedTaskNames::contains)

val easyPublishingTaskGroup = "easy-publishing"
val publicEasyPublishingTasks = setOf(
    "prepareSnapshot",
    "prepareRelease",
    "publishSnapshot",
    "publishRelease"
)
val prepareSnapshotRequested = isTaskRequested("prepareSnapshot")
val releaseRequested = isTaskRequested(
    "prepareRelease",
    "publishRelease",
    "uploadToMavenCentral"
) || providers.gradleProperty("release").map(String::toBoolean).getOrElse(false)
val baseVersion = providers.gradleProperty("version").get().removeSuffix("-SNAPSHOT")
version = if(releaseRequested) baseVersion else "-SNAPSHOT"
val publicationVersion = version.toString()

val snapshotDeployDirectory = layout.buildDirectory.dir("snapshot-deploy")
val releaseDeployDirectory = layout.buildDirectory.dir("staging-deploy")
val releaseBundle = layout.buildDirectory.file("staging-deploy.zip")

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

gradlePlugin {
    website.set("https://github.com/xpenatan/easy-publishing")
    vcsUrl.set("https://github.com/xpenatan/easy-publishing.git")

    plugins {
        create("easyPublishing") {
            id = "com.github.xpenatan.easy-publishing"
            implementationClass = "com.github.xpenatan.easypublishing.EasyPublishingPlugin"
            displayName = "EasyPublishing"
            description = "Prepares and publishes snapshots and releases to Maven repositories and Maven Central."
            tags.set(listOf("publishing", "maven", "maven-central", "snapshot", "release"))
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

publishing {
    repositories {
        maven {
            name = "sonatype"
            url = when {
                releaseRequested -> uri(releaseDeployDirectory)
                prepareSnapshotRequested -> uri(snapshotDeployDirectory)
                else -> uri("https://central.sonatype.com/repository/maven-snapshots/")
            }

            if(!releaseRequested && !prepareSnapshotRequested) {
                credentials {
                    username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME").orNull
                    password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD").orNull
                }
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("EasyPublishing")
            description.set("Reusable snapshot and release publishing for Maven repositories and Maven Central.")
            url.set("https://github.com/xpenatan/easy-publishing")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("Xpe")
                    name.set("Natan")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/xpenatan/easy-publishing.git")
                developerConnection.set("scm:git:ssh://git@github.com/xpenatan/easy-publishing.git")
                url.set("https://github.com/xpenatan/easy-publishing")
            }
        }
    }
}

val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
if(!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
    signing {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

val cleanSnapshotDeploy = tasks.register<Delete>("cleanSnapshotDeploy") {
    delete(snapshotDeployDirectory)
}

val cleanReleaseDeploy = tasks.register<Delete>("cleanReleaseDeploy") {
    delete(releaseDeployDirectory)
    delete(releaseBundle)
}

val sonatypePublishTasks = tasks.withType<PublishToMavenRepository>()

sonatypePublishTasks.configureEach {
    when {
        releaseRequested -> dependsOn(cleanReleaseDeploy)
        prepareSnapshotRequested -> dependsOn(cleanSnapshotDeploy)
    }
}

tasks.register<JavaExec>("prepareSnapshot") {
    group = easyPublishingTaskGroup
    description = "Prepares the plugin snapshot in build/snapshot-deploy without uploading it."
    dependsOn(sonatypePublishTasks)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.github.xpenatan.easypublishing.SnapshotRepositoryNormalizer")
    args(snapshotDeployDirectory.get().asFile.absolutePath)
}

tasks.register("publishSnapshot") {
    group = easyPublishingTaskGroup
    description = "Publishes the plugin and its marker to the Sonatype snapshot repository."
    dependsOn(sonatypePublishTasks)
}

val prepareRelease = tasks.register<Zip>("prepareRelease") {
    group = easyPublishingTaskGroup
    description = "Prepares a Maven Central release bundle without uploading it."
    dependsOn(sonatypePublishTasks)
    from(releaseDeployDirectory)
    archiveFileName.set("staging-deploy.zip")
    destinationDirectory.set(layout.buildDirectory)
}

val uploadToMavenCentral = tasks.register("uploadToMavenCentral") {
    description = "Uploads the prepared release bundle to the Sonatype Central Portal."
    dependsOn(prepareRelease)
    inputs.file(releaseBundle)
    notCompatibleWithConfigurationCache("The release upload performs an authenticated HTTP side effect")

    doLast {
        val bundle = releaseBundle.get().asFile
        check(bundle.isFile && bundle.canRead()) { "Release bundle is missing or unreadable: $bundle" }

        val username = System.getenv("CENTRAL_PORTAL_USERNAME")
            ?: error("CENTRAL_PORTAL_USERNAME environment variable is not set")
        val password = System.getenv("CENTRAL_PORTAL_PASSWORD")
            ?: error("CENTRAL_PORTAL_PASSWORD environment variable is not set")
        check(!System.getenv("SIGNING_KEY").isNullOrBlank()) {
            "SIGNING_KEY environment variable is not set"
        }
        check(!System.getenv("SIGNING_PASSWORD").isNullOrBlank()) {
            "SIGNING_PASSWORD environment variable is not set"
        }

        val token = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8)
        )
        val boundary = "----easy-publishing-${UUID.randomUUID()}"
        val prefix = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val suffix = "\r\n--$boundary--\r\n"
        val deploymentName = URLEncoder.encode(
            "easy-publishing-$publicationVersion",
            StandardCharsets.UTF_8
        )
        val request = HttpRequest.newBuilder(
            URI.create("https://central.sonatype.com/api/v1/publisher/upload?name=$deploymentName")
        )
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prefix),
                HttpRequest.BodyPublishers.ofFile(bundle.toPath()),
                HttpRequest.BodyPublishers.ofString(suffix)
            ))
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())

        check(response.statusCode() in 200..299) {
            "Central Portal upload failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        logger.lifecycle("Central Portal accepted deployment {}.", response.body().trim())
    }
}

tasks.register("publishRelease") {
    group = easyPublishingTaskGroup
    description = "Prepares and uploads a signed release to the Sonatype Central Portal."
    dependsOn(uploadToMavenCentral)
}

gradle.projectsEvaluated {
    tasks.configureEach {
        when {
            name in publicEasyPublishingTasks -> group = easyPublishingTaskGroup
            group == "publishing" -> group = null
        }
    }
}
