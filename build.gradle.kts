plugins {
    base
}

// Shared configuration for the plain-JVM modules. The Minecraft module configures
// itself separately because Loom owns its source sets and dependencies.
subprojects {
    if (name == "minecraft-fabric") return@subprojects

    apply(plugin = "java-library")

    group = "dev.nexus"
    version = rootProject.findProperty("nexusVersion") as String

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(
                JavaLanguageVersion.of(
                    rootProject.findProperty("javaVersion") as String
                )
            )
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        val libs = rootProject.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
        "testImplementation"(libs.findBundle("test").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all,-serial,-processing")
    }
}
