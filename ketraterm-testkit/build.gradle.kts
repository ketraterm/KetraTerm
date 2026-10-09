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
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation(project(":ketraterm-core"))
    implementation(project(":ketraterm-host"))
    implementation(project(":ketraterm-parser"))
    implementation(project(":ketraterm-transport-api"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
}

val xtermOracleDirectory = rootProject.layout.projectDirectory.dir("tools/xterm-oracle")
val npmExecutable = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

fun Test.configureGeneratedDifferentialCampaign(defaultCases: Int) {
    systemProperty(
        "ketraterm.generatedDifferential.cases",
        providers.gradleProperty("xtermDifferentialCases").getOrElse(defaultCases.toString()),
    )
    systemProperty(
        "ketraterm.generatedDifferential.startIndex",
        providers.gradleProperty("xtermDifferentialStartIndex").getOrElse("0"),
    )
    systemProperty(
        "ketraterm.generatedDifferential.commitSha",
        providers
            .gradleProperty("xtermDifferentialCommitSha")
            .orElse(providers.environmentVariable("GITHUB_SHA"))
            .getOrElse("unknown"),
    )
    systemProperty(
        "ketraterm.generatedDifferential.artifacts",
        providers
            .gradleProperty("xtermDifferentialArtifactDirectory")
            .getOrElse(
                layout.buildDirectory
                    .dir("reports/xterm-differential")
                    .get()
                    .asFile.absolutePath,
            ),
    )
}

val installXtermOracle =
    tasks.register<Exec>("installXtermOracle") {
        group = "verification"
        description = "Installs the version-pinned xterm.js differential oracle."
        workingDir(xtermOracleDirectory)
        commandLine(npmExecutable, "ci", "--ignore-scripts")
        inputs.files(
            xtermOracleDirectory.file("package.json"),
            xtermOracleDirectory.file("package-lock.json"),
        )
        outputs.dir(xtermOracleDirectory.dir("node_modules"))
    }

val testXtermOracle =
    tasks.register<Exec>("testXtermOracle") {
        group = "verification"
        description = "Runs the process-isolated xterm.js oracle unit tests."
        dependsOn(installXtermOracle)
        workingDir(xtermOracleDirectory)
        commandLine(npmExecutable, "test")
    }

tasks.register<Test>("xtermDifferentialTest") {
    group = "verification"
    description = "Runs KetraTerm against the pinned xterm.js differential oracle."
    dependsOn(installXtermOracle, testXtermOracle, tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*Xterm*OracleTest")
    systemProperty("ketraterm.xtermOracle.required", "true")
    systemProperty("ketraterm.xtermOracle.node", "node")
    systemProperty(
        "ketraterm.xtermOracle.script",
        xtermOracleDirectory.file("oracle.mjs").asFile.absolutePath,
    )
    systemProperty("ketraterm.xtermOracle.workingDirectory", xtermOracleDirectory.asFile.absolutePath)
    configureGeneratedDifferentialCampaign(defaultCases = 2000)
}

fun registerGeneratedDifferentialProfile(
    taskName: String,
    descriptionText: String,
    defaultCases: Int,
) = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    dependsOn(installXtermOracle, testXtermOracle, tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*XtermGeneratedDifferentialOracleTest")
    systemProperty("ketraterm.xtermOracle.required", "true")
    systemProperty("ketraterm.xtermOracle.node", "node")
    systemProperty("ketraterm.xtermOracle.script", xtermOracleDirectory.file("oracle.mjs").asFile.absolutePath)
    systemProperty("ketraterm.xtermOracle.workingDirectory", xtermOracleDirectory.asFile.absolutePath)
    configureGeneratedDifferentialCampaign(defaultCases)
}

registerGeneratedDifferentialProfile(
    taskName = "xtermDifferentialSmokeTest",
    descriptionText = "Runs 100 deterministic generated xterm.js differential cases.",
    defaultCases = 100,
)

registerGeneratedDifferentialProfile(
    taskName = "xtermDifferentialNightlyTest",
    descriptionText = "Runs 100,000 deterministic generated xterm.js differential cases.",
    defaultCases = 100_000,
)

registerGeneratedDifferentialProfile(
    taskName = "xtermDifferentialReleaseAudit",
    descriptionText = "Runs 500,000 deterministic generated xterm.js differential cases.",
    defaultCases = 500_000,
)

fun registerResizeReflowInvariantProfile(
    taskName: String,
    descriptionText: String,
    defaultCases: Int,
) = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    dependsOn(tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*TerminalResizeReflowInvariantCampaignTest")
    systemProperty("ketraterm.resizeReflow.required", "true")
    systemProperty(
        "ketraterm.resizeReflow.cases",
        providers.gradleProperty("resizeReflowCases").getOrElse(defaultCases.toString()),
    )
    systemProperty(
        "ketraterm.resizeReflow.startIndex",
        providers.gradleProperty("resizeReflowStartIndex").getOrElse("0"),
    )
    systemProperty(
        "ketraterm.resizeReflow.commitSha",
        providers
            .gradleProperty("resizeReflowCommitSha")
            .orElse(providers.environmentVariable("GITHUB_SHA"))
            .getOrElse("unknown"),
    )
    systemProperty(
        "ketraterm.resizeReflow.artifacts",
        layout.buildDirectory
            .dir("reports/resize-reflow-invariant")
            .get()
            .asFile.absolutePath,
    )
}

registerResizeReflowInvariantProfile(
    taskName = "resizeReflowInvariantSmokeTest",
    descriptionText = "Runs 100 deterministic state-aware resize/reflow invariant cases.",
    defaultCases = 100,
)

registerResizeReflowInvariantProfile(
    taskName = "resizeReflowInvariantNightlyTest",
    descriptionText = "Runs 10,000 deterministic state-aware resize/reflow invariant cases.",
    defaultCases = 10_000,
)

fun registerCursorWrapModelProfile(
    taskName: String,
    descriptionText: String,
    defaultCases: Int,
) = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    dependsOn(tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*TerminalCursorWrapModelCampaignTest")
    systemProperty("ketraterm.cursorWrap.required", "true")
    systemProperty(
        "ketraterm.cursorWrap.cases",
        providers.gradleProperty("cursorWrapCases").getOrElse(defaultCases.toString()),
    )
    systemProperty(
        "ketraterm.cursorWrap.startIndex",
        providers.gradleProperty("cursorWrapStartIndex").getOrElse("0"),
    )
    systemProperty(
        "ketraterm.cursorWrap.commitSha",
        providers
            .gradleProperty("cursorWrapCommitSha")
            .orElse(providers.environmentVariable("GITHUB_SHA"))
            .getOrElse("unknown"),
    )
    systemProperty(
        "ketraterm.cursorWrap.artifacts",
        layout.buildDirectory
            .dir("reports/cursor-wrap-model")
            .get()
            .asFile.absolutePath,
    )
}

registerCursorWrapModelProfile(
    taskName = "cursorWrapModelSmokeTest",
    descriptionText = "Runs 100 deterministic model-based cursor and deferred-wrap cases.",
    defaultCases = 100,
)

registerCursorWrapModelProfile(
    taskName = "cursorWrapModelNightlyTest",
    descriptionText = "Runs 25,000 deterministic model-based terminal grid-physics cases.",
    defaultCases = 25_000,
)

tasks.test {
    useJUnitPlatform { excludeTags("compiled-client-upgrade", "publication-verification") }
}

// Compile consumer fixtures against each module's exported API variant, not testkit's classpath.
val consumerClasspathsDirectory = layout.buildDirectory.dir("consumer-classpaths")
val prepareConsumerClasspaths =
    listOf("core", "host", "input", "parser", "completion", "completion-host", "ui-swing", "ui-swing-host", "pty").map { module ->
        val consumerClasspath =
            configurations.create("${module}ConsumerCompileClasspath") {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                    attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
                }
            }
        dependencies.add(consumerClasspath.name, dependencies.project(mapOf("path" to ":ketraterm-$module")))
        val consumerName = module.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        tasks.register<Sync>("prepare${consumerName}ConsumerClasspath") {
            from(consumerClasspath)
            into(consumerClasspathsDirectory.map { it.dir(module) })
        }
    }

tasks.test {
    dependsOn(prepareConsumerClasspaths)
    inputs.dir(consumerClasspathsDirectory)
    systemProperty("ketraterm.consumerClasspaths", consumerClasspathsDirectory.get().asFile.absolutePath)
}

val consumerFixtureDirectory = layout.buildDirectory.dir("published-consumers")
configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlinGradle { target("*.gradle.kts", "src/consumerTest/*.gradle.kts") }
}
val extractSwingReadmeExample =
    tasks.register("extractSwingReadmeExample") {
        val readme = rootProject.layout.projectDirectory.file("ketraterm-ui-swing/README.md")
        val destination =
            layout.buildDirectory.file(
                "readme-example/ui-swing/src/main/kotlin/consumer/documentation/TerminalViewExample.kt",
            )
        inputs.file(readme)
        outputs.file(destination)
        doLast {
            val example =
                Regex("(?s)<!-- compiled-example:terminal-view -->\\s*```kotlin\\r?\\n(.*?)\\r?\\n```")
                    .findAll(readme.asFile.readText())
                    .single()
                    .groupValues[1]
            destination.get().asFile.apply {
                parentFile.mkdirs()
                writeText("package consumer.documentation\n\n$example\n")
            }
        }
    }
val preparePublishedConsumers =
    tasks.register<Sync>("preparePublishedConsumers") {
        dependsOn(extractSwingReadmeExample)
        from("src/consumerTest")
        from(layout.buildDirectory.dir("readme-example"))
        into(consumerFixtureDirectory)
        preserve { include("**/build/**", ".gradle/**") }
    }

fun JavaExec.configureConsumerBuild(
    metadata: String,
    verificationTask: String,
    kotlinCompilerVersion: String =
        org.jetbrains.kotlin.gradle.plugin
            .getKotlinPluginVersion(logger),
) {
    dependsOn(preparePublishedConsumers, rootProject.tasks.named("prepareLibraryPublicationRepository"))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = files(rootProject.file("gradle/wrapper/gradle-wrapper.jar"))
    mainClass.set("org.gradle.wrapper.GradleWrapperMain")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(
        "--project-dir",
        consumerFixtureDirectory.get().asFile.absolutePath,
        "--console=plain",
        "--no-daemon",
        "--max-workers=2",
        "-PkotlinVersion=$kotlinCompilerVersion",
        "-PlibraryVersion=${project.version}",
        "-PlibraryRepository=${rootProject.layout.buildDirectory.dir("library-publication-repository").get().asFile.toURI()}",
        "-PmetadataMode=$metadata",
        "-PsupportedLibraryNames=${(rootProject.extra["publishedDependencyNames"] as Set<*>).joinToString(",")}",
        verificationTask,
    )
}

tasks.register<Test>("publicationVerificationTest") {
    group = "verification"
    description = "Verifies the complete Maven repository, including the BOM and packaged sources/documentation."
    dependsOn(rootProject.tasks.named("prepareLibraryPublicationRepository"), tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("publication-verification") }
    val repository = rootProject.layout.buildDirectory.dir("library-publication-repository")
    inputs.dir(repository)
    systemProperty("publication.repository", repository.get().asFile.absolutePath)
    systemProperty("publication.version", project.version.toString())
    systemProperty("publication.libraries", (rootProject.extra["publishedLibraryNames"] as Set<*>).joinToString(","))
    systemProperty("publication.entryPoints", (rootProject.extra["publishedEntryPointNames"] as Set<*>).joinToString(","))
    systemProperty(
        "publication.signed",
        providers.gradleProperty("signingInMemoryKey").isPresent || providers.environmentVariable("RELEASE").getOrElse("false") == "true",
    )
}

val verifyPublishedConsumers =
    listOf("gradle", "pom").flatMap { metadata ->
        listOf(false, true).map { minimumCompiler ->
            val compilerSuffix = if (minimumCompiler) "MinimumKotlin" else ""
            tasks.register<JavaExec>("verify${metadata.replaceFirstChar(Char::uppercaseChar)}${compilerSuffix}PublishedConsumers") {
                group = "verification"
                description = "Compiles and runs isolated Kotlin/Java consumers using $metadata publication metadata."
                if (minimumCompiler) {
                    configureConsumerBuild(metadata, "check", "2.4.0")
                } else {
                    configureConsumerBuild(metadata, "check")
                }
            }
        }
    }
verifyPublishedConsumers.zipWithNext { previous, next -> next.configure { mustRunAfter(previous) } }

val prepareCompiledClientRuntimes =
    listOf("gradle", "pom").flatMap { metadata ->
        listOf<String?>(null, "2.4.0").map { kotlinRuntime ->
            val runtimeSuffix = if (kotlinRuntime == null) "" else "Kotlin${kotlinRuntime.replace(".", "")}"
            tasks.register<JavaExec>("prepare${metadata.replaceFirstChar(Char::uppercaseChar)}${runtimeSuffix}CompiledClientRuntime") {
                description = "Resolves $metadata runtime artifacts with Kotlin ${kotlinRuntime ?: "current"}, without compiling clients."
                configureConsumerBuild(metadata, "prepareCompiledClientRuntime")
                if (kotlinRuntime != null) args("-PruntimeKotlinVersion=$kotlinRuntime")
            }
        }
    }
prepareCompiledClientRuntimes.zipWithNext { previous, next -> next.configure { mustRunAfter(previous) } }

val compiledClientUpgradeTest =
    tasks.register<Test>("compiledClientUpgradeTest") {
        group = "verification"
        description = "Runs retained Kotlin/Java client binaries against current published libraries."
        dependsOn(tasks.testClasses, prepareCompiledClientRuntimes)
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("compiled-client-upgrade") }
        val baseline = layout.projectDirectory.dir("src/consumerTest/baseline")
        val runtimes = consumerFixtureDirectory.map { it.dir("upgrade-classpaths") }
        inputs.dir(baseline)
        inputs.dir(runtimes)
        inputs.dir(rootProject.layout.buildDirectory.dir("library-publication-repository"))
        systemProperty("ketraterm.compiledClientBaseline", baseline.asFile.absolutePath)
        systemProperty("ketraterm.compiledClientRuntimes", runtimes.get().asFile.absolutePath)
        systemProperty(
            "ketraterm.currentKotlinRuntimeVersion",
            org.jetbrains.kotlin.gradle.plugin
                .getKotlinPluginVersion(logger),
        )
    }

tasks.register<JavaExec>("recordCompiledClientBaseline") {
    group = "verification"
    description = "Intentionally recompiles and replaces the retained consumer baseline for review."
    configureConsumerBuild("gradle", "recordCompiledClientBaseline")
    val revision =
        providers.gradleProperty("compiledClientBaselineRevision").orElse(
            providers
                .exec { commandLine("git", "rev-parse", "HEAD") }
                .standardOutput.asText
                .map(String::trim),
        )
    args("-PbaselineRevision=${revision.get()}")
    doLast {
        copy {
            from(consumerFixtureDirectory.map { it.dir("baseline") })
            into(layout.projectDirectory.dir("src/consumerTest/baseline"))
        }
    }
}

tasks.register("publishedConsumerTest") {
    group = "verification"
    description = "Verifies isolated published-library consumption and retained-client upgrades."
    dependsOn(verifyPublishedConsumers, compiledClientUpgradeTest)
}
tasks.named("check") { dependsOn("publishedConsumerTest") }
