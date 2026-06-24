plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("com.diffplug.spotless") version "8.6.0"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
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
    dokka(project(":jvterm-ssh"))
    dokka(project(":jvterm-workspace"))
}

val versionFile = rootProject.file("VERSION")
val baseVersion = if (versionFile.exists()) {
    versionFile.readText().trim()
} else {
    "0.1.0"
}
val isRelease = System.getenv("RELEASE") == "true"
val projectVersion = if (isRelease) baseVersion else "$baseVersion-SNAPSHOT"

subprojects {
    group = "io.github.jvterm"
    version = projectVersion

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (name != "jvterm-benchmarks" && name != "jvterm-app") {
            plugins.apply("com.vanniktech.maven.publish")

            extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                publishToMavenCentral(automaticRelease = true)
                signAllPublications()

                pom {
                    name.set(project.name)
                    description.set("JvTerm terminal emulator library - subproject ${project.name}")
                    url.set("https://github.com/jvterm/JvTerm")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("gsargsyan")
                            name.set("Gagik Sargsyan")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/jvterm/JvTerm.git")
                        developerConnection.set("scm:git:ssh://github.com/jvterm/JvTerm.git")
                        url.set("https://github.com/jvterm/JvTerm")
                    }
                }
            }
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



