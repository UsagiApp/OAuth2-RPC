plugins {
    kotlin("jvm")
    application
}

group = "com.discord"
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":library"))
}

application {
    mainClass.set("com.discord.oauth2rpc.MainKt")
}
