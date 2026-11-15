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
package io.github.ketraterm.testkit

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Process-isolated upgrade checks; client classes are loaded only from the tracked baseline jars. */
@Tag("compiled-client-upgrade")
class CompiledClientUpgradeTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @MethodSource("upgradeCases")
    fun `already compiled Kotlin and Java clients run against the current publication`(
        module: String,
        metadata: String,
        kotlinRuntime: String,
    ) {
        val result = runClient(module, runtimeClasspath(module, metadata, kotlinRuntime), kotlinRuntime)
        assertEquals(0, result.exitCode, "$module client failed using $metadata metadata and Kotlin $kotlinRuntime:\n${result.output}")
    }

    @Test
    fun `every resolved consumer has a retained jar and provenance in every runtime variant`() {
        val baseline = Path.of(checkNotNull(System.getProperty("ketraterm.compiledClientBaseline")))
        val modules = runtimeModules("gradle", "current").toSet()
        assertTrue(modules.isNotEmpty(), "No published consumer runtimes were prepared")
        assertEquals(modules, baseline.listDirectoryEntries("*.jar").map { it.nameWithoutExtension }.toSet(), "Retained client inventory")
        assertEquals(modules, baseline.listDirectoryEntries("*.provenance").map { it.nameWithoutExtension }.toSet(), "Provenance inventory")
        for ((metadata, kotlinRuntime) in RUNTIME_VARIANTS) {
            assertEquals(modules, runtimeModules(metadata, kotlinRuntime).toSet(), "$metadata/$kotlinRuntime runtime inventory")
        }
    }

    @ParameterizedTest
    @CsvSource("parser", "workspace", "completion-persistence")
    fun `upgrade check rejects removal of a class used by the retained client`(module: String) {
        val runtime = runtimeClasspath(module, "gradle")
        val library = runtime.single { it.fileName.toString().startsWith("ketraterm-$module-") }
        val incompatibleRuntime = runtime.filterNot { it == library }
        assertTrue(incompatibleRuntime.none { it == library })
        val result = runClient(module, incompatibleRuntime)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("NoClassDefFoundError"), result.output)
        assertTrue(result.output.contains("io/github/ketraterm/${module.replace('-', '/')}"), result.output)
    }

    @ParameterizedTest
    @CsvSource(
        "parser,io.github.ketraterm.parser.api,TerminalParsers,create",
        "workspace,io.github.ketraterm.workspace,TerminalWorkspaceOpenOptions,create",
        "completion-persistence,io.github.ketraterm.completion.persistence,TerminalCompletionLearningCoordinator,currentFileName",
    )
    fun `upgrade check rejects removal of a method used by the retained client`(
        module: String,
        packageName: String,
        className: String,
        method: String,
    ) {
        val source = directory.resolve("$className.java")
        source.writeText(
            """
            package $packageName;
            public final class $className {}
            """.trimIndent(),
        )
        val classes = directory.resolve("incompatible-library").createDirectories()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Upgrade controls require a JDK" }
        assertEquals(0, compiler.run(null, null, null, "--release", "25", "-proc:none", "-d", classes.toString(), source.toString()))
        val result = runClient(module, listOf(classes) + runtimeClasspath(module, "gradle"))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("NoSuchMethodError"), result.output)
        assertTrue(result.output.contains("$className.$method"), result.output)
    }

    private fun runtimeClasspath(
        module: String,
        metadata: String,
        kotlinRuntime: String = "current",
    ): List<Path> =
        Path
            .of(checkNotNull(System.getProperty("ketraterm.compiledClientRuntimes")))
            .resolve("$metadata/$kotlinRuntime/$module.txt")
            .readLines()
            .filter(String::isNotBlank)
            .map(Path::of)
            .also { classpath ->
                assertTrue(classpath.isNotEmpty(), "No published runtime artifacts resolved for $module/$metadata")
                assertTrue(classpath.all { it.toFile().isFile }, "Missing published runtime artifact in $module/$metadata")
                val expectedVersion = expectedKotlinVersion(kotlinRuntime)
                val stdlib = classpath.filter { it.fileName.toString().matches(Regex("kotlin-stdlib-[0-9].*\\.jar")) }
                assertEquals(listOf("kotlin-stdlib-$expectedVersion.jar"), stdlib.map { it.fileName.toString() })
                for (artifact in classpath) {
                    val name = artifact.fileName.toString()
                    if (name.startsWith("kotlin-stdlib-jdk")) assertTrue(name.endsWith("-$expectedVersion.jar"), name)
                }
            }

    private fun runClient(
        module: String,
        runtime: List<Path>,
        kotlinRuntime: String = "current",
    ): ClientResult {
        val baseline = Path.of(checkNotNull(System.getProperty("ketraterm.compiledClientBaseline")))
        val jar = baseline.resolve("$module.jar")
        val provenance =
            Properties().apply {
                baseline
                    .resolve("$module.provenance")
                    .toFile()
                    .reader()
                    .use(::load)
            }
        if (module == "workspace" || module == "completion-persistence") {
            assertEquals("published-release", provenance.getProperty("basis"), "$module requires a released-client baseline")
            assertEquals("0.5.0", provenance.getProperty("libraryVersion"), "$module baseline release changed without review")
            assertEquals("https://repo.maven.apache.org/maven2/", provenance.getProperty("repository"))
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(provenance.getProperty("clientSha256"), sha256, "The retained $module client differs from its provenance")
        JarFile(jar.toFile()).use { client ->
            assertTrue(client.getEntry("consumer/JavaConsumer.class") != null, "Missing retained Java client")
            assertTrue(client.getEntry("consumer/KotlinConsumerKt.class") != null, "Missing retained Kotlin client")
            assertTrue(
                client.entries().asSequence().none { it.name.startsWith("io/github/ketraterm/") },
                "A client baseline must not bundle the libraries being upgraded",
            )
        }
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val output = directory.resolve("client-output.txt")
        val launcherClass = "io/github/ketraterm/testkit/CompiledClientLauncher.class"
        val launcherDirectory = directory.resolve("launcher").createDirectories()
        val launcherFile = launcherDirectory.resolve(launcherClass)
        launcherFile.parent.createDirectories()
        checkNotNull(CompiledClientLauncher::class.java.getResourceAsStream("/$launcherClass")).use {
            Files.copy(it, launcherFile)
        }
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Djava.awt.headless=true",
                "-classpath",
                (listOf(launcherDirectory, jar) + runtime).joinToString(File.pathSeparator),
                "io.github.ketraterm.testkit.CompiledClientLauncher",
                expectedKotlinVersion(kotlinRuntime),
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Retained $module client did not finish:\n${output.readText()}")
            return ClientResult(process.exitValue(), output.readText())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Retained $module client process did not terminate")
            }
        }
    }

    private fun expectedKotlinVersion(runtime: String): String =
        if (runtime == "current") checkNotNull(System.getProperty("ketraterm.currentKotlinRuntimeVersion")) else runtime

    private data class ClientResult(
        val exitCode: Int,
        val output: String,
    )

    private companion object {
        val RUNTIME_VARIANTS = listOf("gradle" to "current", "pom" to "current", "gradle" to "2.4.0", "pom" to "2.4.0")

        fun runtimeModules(
            metadata: String,
            kotlinRuntime: String,
        ): List<String> =
            Path
                .of(checkNotNull(System.getProperty("ketraterm.compiledClientRuntimes")))
                .resolve("$metadata/$kotlinRuntime")
                .listDirectoryEntries("*.txt")
                .map { it.nameWithoutExtension }
                .sorted()

        @JvmStatic
        fun upgradeCases(): List<Arguments> =
            RUNTIME_VARIANTS.flatMap { (metadata, kotlinRuntime) ->
                runtimeModules(metadata, kotlinRuntime).map { Arguments.of(it, metadata, kotlinRuntime) }
            }
    }
}
