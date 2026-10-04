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
import java.security.MessageDigest

plugins {
    id("java-base")
    kotlin("jvm") apply false
}

fun sha256(file: File): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

val metadataMode = providers.gradleProperty("metadataMode").get()
require(metadataMode in setOf("gradle", "pom"))
val runtimeKotlinVersion = providers.gradleProperty("runtimeKotlinVersion")
val runtimeName = runtimeKotlinVersion.getOrElse("current")
allprojects {
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
}
val bomOnly = configurations.create("bomOnly")
val alignedLibraries = configurations.create("alignedLibraries")
val headlessSession = configurations.create("headlessSession")
listOf(bomOnly, alignedLibraries, headlessSession).forEach {
    it.isCanBeConsumed = false
    it.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
val supportedLibraries =
    providers
        .gradleProperty("supportedLibraryNames")
        .get()
        .split(",")
        .toSet()
val libraryVersion = providers.gradleProperty("libraryVersion").get()
dependencies {
    add(bomOnly.name, platform("io.github.ketraterm:ketraterm-bom:$libraryVersion"))
    add(alignedLibraries.name, platform("io.github.ketraterm:ketraterm-bom:$libraryVersion"))
    supportedLibraries.forEach { add(alignedLibraries.name, "io.github.ketraterm:$it") }
    add(headlessSession.name, platform("io.github.ketraterm:ketraterm-bom:$libraryVersion"))
    add(headlessSession.name, "io.github.ketraterm:ketraterm-headless")
}
val verifyBom =
    tasks.register("verifyBom") {
        inputs.files(bomOnly, alignedLibraries, headlessSession)
        doLast {
            check(bomOnly.files.isEmpty()) { "The BOM pulled runtime libraries without a consumer selecting them" }
            val libraries =
                alignedLibraries.incoming.resolutionResult.allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .filter { it.group == "io.github.ketraterm" && it.module != "ketraterm-bom" }
            check(libraries.map { it.module }.toSet() == supportedLibraries)
            check(libraries.all { it.version == libraryVersion }) { "The BOM did not align versionless library dependencies" }
            val headlessLibraries =
                headlessSession.incoming.resolutionResult.allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .filter { it.group == "io.github.ketraterm" && it.module != "ketraterm-bom" }
            check(
                headlessLibraries.map { it.module }.toSet() ==
                    setOf(
                        "ketraterm-protocol",
                        "ketraterm-parser",
                        "ketraterm-core",
                        "ketraterm-host",
                        "ketraterm-input",
                        "ketraterm-render-api",
                        "ketraterm-render-cache",
                        "ketraterm-transport-api",
                        "ketraterm-session",
                        "ketraterm-headless",
                    ),
            ) { "A single session dependency must supply the headless pipeline without optional integrations" }
        }
    }
subprojects {
    val consumerName = name
    apply(plugin = "org.jetbrains.kotlin.jvm")
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$metadataMode/${providers.gradleProperty("kotlinVersion").get()}"))
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(25) }
    // The only library dependency: missing exports must not be repaired by the fixture.
    val libraryName =
        when (name) {
            "host-spi" -> "headless"
            "ui-swing" -> "swing"
            else -> name
        }
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
        dependsOn(verifyBom)
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
