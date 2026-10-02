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
plugins {
    kotlin("jvm") apply false
}
val metadataMode = providers.gradleProperty("metadataMode").get()
require(metadataMode in setOf("gradle", "pom"))
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$metadataMode"))
    configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(providers.gradleProperty("libraryRepository").get())
                    metadataSources {
                        if (metadataMode == "gradle") {
                            gradleMetadata()
                        } else {
                            mavenPom()
                            ignoreGradleMetadataRedirection()
                        }
                    }
                }
            }
            filter { includeGroup("io.github.ketraterm") }
        }
        mavenCentral()
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(25) }
    // The only library dependency: missing exports must not be repaired by the fixture.
    dependencies { add("implementation", "io.github.ketraterm:ketraterm-$name:${providers.gradleProperty("libraryVersion").get()}") }
    val smoke =
        tasks.register<JavaExec>("smoke") {
            dependsOn("classes")
            classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
            mainClass.set("consumer.KotlinConsumerKt")
            if (project.name == "ui-swing") systemProperty("java.awt.headless", "true")
            javaLauncher.set(
                project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                },
            )
        }
    tasks.named("check") { dependsOn(smoke) }
}
