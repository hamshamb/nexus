plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
}

group = "dev.nexus"
version = rootProject.findProperty("nexusVersion") as String

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(rootProject.findProperty("javaVersion") as String))
    }
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("nexus") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runs {
        // A second client with its own run directory, for the two-client bridge test.
        create("guestClient") {
            client()
            configName = "Guest Client"
            runDir = "run-guest"
        }

        // Forward -Pnexus.* Gradle properties (devtest driver, backend URL) to the game JVM so the
        // NexusDevTest driver can be steered from the command line.
        configureEach {
            project.properties.forEach { (key, value) ->
                if (key.startsWith("nexus.") && value != null) {
                    vmArg("-D$key=$value")
                }
            }
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    implementation("net.fabricmc:fabric-loader:${libs.versions.fabric.loader.get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${libs.versions.fabric.api.get()}")

    // Nexus modules. Netty and Gson are not re-declared here: these modules pin the
    // exact versions Minecraft 26.2 already bundles, so the game supplies them.
    implementation(project(":core"))
    implementation(project(":transport-api"))
    implementation(project(":transport-tcp"))
    implementation(project(":session-protocol"))
    implementation(project(":session-client"))

    // Bundled into the released jar so players install one file.
    include(project(":core"))
    include(project(":transport-api"))
    include(project(":transport-tcp"))
    include(project(":session-protocol"))
    include(project(":session-client"))
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set((rootProject.findProperty("javaVersion") as String).toInt())
    options.encoding = "UTF-8"
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_nexus" }
    }
}
