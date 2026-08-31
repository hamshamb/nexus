pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 25 toolchain Minecraft 26.2 requires, so contributors
    // do not have to install it by hand.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nexus"

include("core")
include("transport-api")
include("transport-tcp")
include("session-protocol")
include("session-client")
include("backend")
include("minecraft-fabric")
