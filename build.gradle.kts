plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

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
    website.set("https://github.com/xpenatan/PublishPlugin")
    vcsUrl.set("https://github.com/xpenatan/PublishPlugin.git")

    plugins {
        create("xpenatanPublish") {
            id = "com.github.xpenatan.publish"
            implementationClass = "com.github.xpenatan.publish.PublishPlugin"
            displayName = "Xpenatan Maven Publish Plugin"
            description = "Prepares and publishes snapshot and release repositories for Maven Central."
            tags.set(listOf("publishing", "maven-central", "snapshot", "release"))
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
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Xpenatan Maven Publish Plugin")
            description.set("Reusable snapshot and release publishing workflow for Maven Central.")
            url.set("https://github.com/xpenatan/PublishPlugin")
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
                connection.set("scm:git:https://github.com/xpenatan/PublishPlugin.git")
                developerConnection.set("scm:git:ssh://git@github.com/xpenatan/PublishPlugin.git")
                url.set("https://github.com/xpenatan/PublishPlugin")
            }
        }
    }
}
