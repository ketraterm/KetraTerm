plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("com.diffplug.spotless") version "8.6.0"
    id("org.jetbrains.dokka") version "2.0.0"
}

repositories {
    mavenCentral()
}

dependencies {
    dokka(project(":jvterm-protocol"))
    dokka(project(":jvterm-parser"))
    dokka(project(":jvterm-core"))
    dokka(project(":jvterm-host"))
    dokka(project(":jvterm-input"))
    dokka(project(":jvterm-render-api"))
    dokka(project(":jvterm-render-cache"))
    dokka(project(":jvterm-transport-api"))
    dokka(project(":jvterm-session"))
    dokka(project(":jvterm-ui-swing"))
    dokka(project(":jvterm-testkit"))
    dokka(project(":jvterm-pty"))
    dokka(project(":jvterm-workspace"))
}

subprojects {
    group = "io.github.jvterm"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    plugins.apply("com.diffplug.spotless")
    plugins.apply("org.jetbrains.dokka")

    plugins.withId("org.jetbrains.dokka") {
        if (file("Module.md").exists()) {
            extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
                dokkaPublications.configureEach {
                    includes.from("Module.md")
                }
            }
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.3.1")
            licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.3.1")
        }
    }
}


