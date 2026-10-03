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
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("jvm") apply false
}

fun sha256(file: File): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

val metadataMode = providers.gradleProperty("metadataMode").get()
require(metadataMode in setOf("gradle", "pom"))
val runtimeKotlinVersion = providers.gradleProperty("runtimeKotlinVersion")
val runtimeName = runtimeKotlinVersion.getOrElse("current")
subprojects {
    val consumerName = name
    apply(plugin = "org.jetbrains.kotlin.jvm")
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$metadataMode/${providers.gradleProperty("kotlinVersion").get()}"))
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
    val libraryName = if (name == "host-spi") "host" else name
    dependencies { add("implementation", "io.github.ketraterm:ketraterm-$libraryName:${providers.gradleProperty("libraryVersion").get()}") }
    runtimeKotlinVersion.orNull?.let { version ->
        configurations.named("runtimeClasspath") {
            resolutionStrategy.force(
                "org.jetbrains.kotlin:kotlin-stdlib:$version",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk7:$version",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$version",
            )
        }
    }
    val smoke =
        tasks.register<JavaExec>("smoke") {
            dependsOn("classes")
            classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
            mainClass.set("consumer.KotlinConsumerKt")
            if (project.name in setOf("ui-swing", "ui-swing-host")) systemProperty("java.awt.headless", "true")
            javaLauncher.set(
                project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                },
            )
        }
    tasks.named("check") { dependsOn(smoke) }

    // Resolution has no dependency on classes: an upgrade must never recompile its old client.
    tasks.register("prepareCompiledClientRuntime") {
        val runtime = configurations.named("runtimeClasspath")
        val destination = rootProject.layout.projectDirectory.file("upgrade-classpaths/$metadataMode/$runtimeName/$consumerName.txt")
        inputs.files(runtime)
        outputs.file(destination)
        doLast {
            destination.asFile.apply {
                parentFile.mkdirs()
                writeText(
                    runtime
                        .get()
                        .files
                        .sortedBy(File::getName)
                        .joinToString("\n") { it.absolutePath } + "\n",
                )
            }
        }
    }

    val baselineJar =
        tasks.register<Jar>("compiledClientBaselineJar") {
            dependsOn("classes")
            from(project.extensions.getByType<SourceSetContainer>()["main"].output)
            destinationDirectory.set(rootProject.layout.projectDirectory.dir("baseline"))
            archiveFileName.set("$consumerName.jar")
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }
    val baselineArtifacts =
        configurations
            .getByName("compileClasspath")
            .incoming.artifacts.resolvedArtifacts
    val baselineSources = fileTree("src/main")
    val fixtureDirectory = projectDir
    val provenanceDirectory = rootProject.layout.projectDirectory.dir("baseline")
    val baselineRevision = providers.gradleProperty("baselineRevision")
    val baselineLibraryVersion = providers.gradleProperty("libraryVersion")
    val baselineKotlinVersion = providers.gradleProperty("kotlinVersion")
    val baselineCompiler =
        extensions.getByType<JavaToolchainService>().compilerFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    tasks.register("recordCompiledClientBaseline") {
        dependsOn(baselineJar)
        doLast {
            val jar =
                baselineJar
                    .get()
                    .archiveFile
                    .get()
                    .asFile
            val artifacts = baselineArtifacts.get()
            val sources = baselineSources.files.sortedBy { it.relativeTo(fixtureDirectory).invariantSeparatorsPath }
            val compiler = baselineCompiler.get()
            provenanceDirectory.file("$consumerName.provenance").asFile.writeText(
                buildString {
                    appendLine("format=1")
                    appendLine("basis=reviewed-working-tree")
                    appendLine("baseRevision=${baselineRevision.get()}")
                    appendLine("libraryVersion=${baselineLibraryVersion.get()}")
                    appendLine("kotlinVersion=${baselineKotlinVersion.get()}")
                    appendLine("jvmTarget=25")
                    appendLine("jdkVersion=${compiler.metadata.javaRuntimeVersion}")
                    appendLine("jdkVendor=${compiler.metadata.vendor}")
                    appendLine("clientSha256=${sha256(jar)}")
                    for (artifact in artifacts.sortedBy { it.id.componentIdentifier.displayName }) {
                        appendLine("artifact=${artifact.id.componentIdentifier.displayName} sha256=${sha256(artifact.file)}")
                    }
                    for (source in sources) {
                        appendLine("source=${source.relativeTo(fixtureDirectory).invariantSeparatorsPath} sha256=${sha256(source)}")
                    }
                },
            )
        }
    }
}

val verifyPublicationBoundary =
    tasks.register("verifyPublicationBoundary") {
        val requestedVersion = providers.gradleProperty("libraryVersion")
        val repository = providers.gradleProperty("libraryRepository").map { file(uri(it)) }
        val runtimes = subprojects.associate { it.name to it.configurations.named("runtimeClasspath") }
        inputs.property("libraryVersion", requestedVersion)
        inputs.dir(repository)
        inputs.files(runtimes.values)
        doLast {
            val version = requestedVersion.get()
            val productArtifacts =
                setOf(
                    "ketraterm-workspace",
                    "ketraterm-completion-persistence",
                    "ketraterm-app",
                    "ketraterm-testkit",
                    "ketraterm-benchmarks",
                    "ketraterm-intellij-plugin",
                )
            for (artifact in productArtifacts) {
                check(!repository.get().resolve("io/github/ketraterm/$artifact/$version").exists()) {
                    "Product artifact $artifact:$version leaked into the public Maven repository"
                }
            }
            for ((consumer, runtime) in runtimes) {
                val modules =
                    runtime
                        .get()
                        .incoming.resolutionResult.allComponents
                        .mapNotNull { it.id as? ModuleComponentIdentifier }
                for (module in modules) {
                    if (module.group == "io.github.ketraterm") {
                        check(module.module !in productArtifacts) { "$consumer pulled product artifact $module" }
                        check(module.version == version) { "$consumer mixed library versions: expected $version, got $module" }
                    }
                    if (consumer == "parser" || consumer == "core") {
                        check(
                            !module.module.startsWith("ketraterm-ui-") &&
                                module.module != "ketraterm-pty" &&
                                module.group != "org.jetbrains.pty4j" &&
                                module.group != "net.java.dev.jna" &&
                                module.module != "kotlinx-coroutines-swing",
                        ) { "Headless $consumer pulled UI or native hosting dependency $module" }
                    }
                    if (consumer == "ui-swing") {
                        check(
                            module.module !in
                                setOf(
                                    "ketraterm-pty",
                                    "ketraterm-shell-integration",
                                    "ketraterm-ui-swing-host",
                                    "ketraterm-completion",
                                    "ketraterm-completion-host",
                                ) &&
                                module.group != "org.jetbrains.pty4j" &&
                                module.group != "net.java.dev.jna",
                        ) { "Base Swing terminal pulled optional host integration $module" }
                    }
                }
            }
        }
    }
subprojects { tasks.named("check") { dependsOn(verifyPublicationBoundary) } }

tasks.register("prepareCompiledClientRuntime") {
    dependsOn(subprojects.map { it.tasks.named("prepareCompiledClientRuntime") })
}
tasks.register("recordCompiledClientBaseline") {
    dependsOn(subprojects.map { it.tasks.named("recordCompiledClientBaseline") })
}
