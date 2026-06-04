plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "com.discord"
version = System.getenv("LIB_VERSION") ?: "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.json:json:20240303")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            groupId = "com.discord"
            artifactId = "discord-oauth2-rpc"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sang765/Discord-OAuth2-RPC")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
