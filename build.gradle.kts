/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("com.diffplug.spotless") version "8.10.3"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

extra["kotlinxCoroutinesVersion"] = "1.11.0"

// One boundary for Maven publication, public ABI checks and aggregated API documentation.
val publishedLibraryNames =
    setOf(
        "ketraterm-protocol",
        "ketraterm-parser",
        "ketraterm-core",
        "ketraterm-host",
        "ketraterm-input",
        "ketraterm-completion",
        "ketraterm-completion-host",
        "ketraterm-render-api",
        "ketraterm-render-cache",
        "ketraterm-transport-api",
        "ketraterm-session",
        "ketraterm-shell-integration",
        "ketraterm-ui-swing",
        "ketraterm-ui-swing-host",
        "ketraterm-pty",
    )
extra["publishedLibraryNames"] = publishedLibraryNames
val publishedEntryPointNames = setOf("ketraterm-headless", "ketraterm-swing")
val publishedDependencyNames = publishedLibraryNames + publishedEntryPointNames
extra["publishedDependencyNames"] = publishedDependencyNames
extra["publishedEntryPointNames"] = publishedEntryPointNames

val publicationRepository = layout.buildDirectory.dir("library-publication-repository")
val cleanLibraryPublicationRepository =
    tasks.register<Delete>("cleanLibraryPublicationRepository") {
        delete(publicationRepository)
    }

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.3.1")
    }
}
val prepareLibraryPublicationRepository =
    tasks.register("prepareLibraryPublicationRepository") {
        group = "publishing"
        description = "Publishes all supported artifacts to a build-local Maven repository."
    }
val publicationChecks =
    tasks.register("publicationChecks") {
        group = "verification"
        description = "Checks formatting, tests, public ABI, consumers and packaged Maven artifacts."
        dependsOn("spotlessCheck", ":ketraterm-testkit:publishedConsumerTest", ":ketraterm-testkit:publicationVerificationTest")
    }

repositories {
    mavenCentral()
}

dependencies {
    publishedLibraryNames.forEach { dokka(project(":$it")) }
}

val baseVersion =
    providers
        .fileContents(layout.projectDirectory.file("VERSION"))
        .asText
        .get()
        .trim()
require(baseVersion.isNotEmpty() && !baseVersion.endsWith("-SNAPSHOT")) { "VERSION must contain the base release version" }
val isRelease = providers.environmentVariable("RELEASE").getOrElse("false") == "true"
val projectVersion = if (isRelease) baseVersion else "$baseVersion-SNAPSHOT"

subprojects {
    group = "io.github.ketraterm"
    version = projectVersion

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        publicationChecks.configure { dependsOn(tasks.named("test")) }
        if (name in publishedLibraryNames) {

            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
                explicitApi()
                compilerOptions {
                    languageVersion.set(KotlinVersion.KOTLIN_2_4)
                    apiVersion.set(KotlinVersion.KOTLIN_2_4)
                    jvmDefault.set(JvmDefaultMode.ENABLE)
                }
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation {
                    binariesSource.set(BinariesSource.MAVEN_PUBLICATIONS)
                }
            }

            publicationChecks.configure { dependsOn(tasks.named("checkKotlinAbi")) }
        }
    }

    if (name in publishedDependencyNames || name == "ketraterm-bom") {
        plugins.apply("com.vanniktech.maven.publish")
        val localPublicationTask = "$path:publishAllPublicationsToPublicationValidationRepository"
        prepareLibraryPublicationRepository.configure { dependsOn(localPublicationTask) }

        if (name in publishedEntryPointNames) {
            plugins.withId("java-library") {
                extensions.configure<JavaPluginExtension> {
                    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
                }
                // Dependency bundles publish their Java dependency variants without empty jars.
                listOf("apiElements", "runtimeElements").forEach { variant ->
                    configurations.named(variant) { outgoing.artifacts.clear() }
                }
                tasks.named("jar") { enabled = false }
                extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                    configure(
                        com.vanniktech.maven.publish.JavaLibrary(
                            javadocJar =
                                com.vanniktech.maven.publish.JavadocJar
                                    .None(),
                            sourcesJar =
                                com.vanniktech.maven.publish.SourcesJar
                                    .None(),
                        ),
                    )
                }
                extensions.configure<PublishingExtension> {
                    publications.withType<MavenPublication>().configureEach { pom.packaging = "pom" }
                }
            }
        }

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            pom {
                name.set(project.name)
                description.set(
                    when (project.name) {
                        "ketraterm-bom" -> "Version alignment for KetraTerm libraries and dependency entry points"
                        "ketraterm-headless" -> "KetraTerm headless terminal pipeline and session dependencies"
                        "ketraterm-swing" -> "KetraTerm embedded Swing terminal dependencies"
                        else -> "KetraTerm terminal library: ${project.name}"
                    },
                )
                url.set("https://github.com/ketraterm/KetraTerm")
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
                    connection.set("scm:git:https://github.com/ketraterm/KetraTerm.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ketraterm/KetraTerm.git")
                    url.set("https://github.com/ketraterm/KetraTerm")
                }
            }
        }

        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "publicationValidation"
                url = publicationRepository.get().asFile.toURI()
            }
        }
        tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToPublicationValidationRepository")) {
                dependsOn(cleanLibraryPublicationRepository)
            } else {
                dependsOn(publicationChecks)
            }
        }
        tasks
            .matching {
                it.name in setOf("publishToMavenCentral", "publishAndReleaseToMavenCentral", "prepareMavenCentralPublishing")
            }.configureEach {
                dependsOn(publicationChecks)
            }
    }

    plugins.apply("com.diffplug.spotless")
    publicationChecks.configure { dependsOn(tasks.named("spotlessCheck")) }
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
