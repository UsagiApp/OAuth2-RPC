plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "com.discord"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.json)
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
            url = uri("https://maven.pkg.github.com/UsagiApp/OAuth2-RPC")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
