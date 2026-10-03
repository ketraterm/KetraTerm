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
import org.junit.jupiter.params.provider.CsvSource
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
    @CsvSource(
        "parser,gradle,current",
        "parser,pom,current",
        "host,gradle,current",
        "host,pom,current",
        "completion,gradle,current",
        "completion,pom,current",
        "ui-swing,gradle,current",
        "ui-swing,pom,current",
        "pty,gradle,current",
        "pty,pom,current",
        "parser,gradle,2.4.0",
        "parser,pom,2.4.0",
        "host,gradle,2.4.0",
        "host,pom,2.4.0",
        "completion,gradle,2.4.0",
        "completion,pom,2.4.0",
        "ui-swing,gradle,2.4.0",
        "ui-swing,pom,2.4.0",
        "pty,gradle,2.4.0",
        "pty,pom,2.4.0",
        "core,gradle,current",
        "core,pom,current",
        "core,gradle,2.4.0",
        "core,pom,2.4.0",
        "host-spi,gradle,current",
        "host-spi,pom,current",
        "host-spi,gradle,2.4.0",
        "host-spi,pom,2.4.0",
        "render-cache,gradle,current",
        "render-cache,pom,current",
        "render-cache,gradle,2.4.0",
        "render-cache,pom,2.4.0",
        "completion-host,gradle,current",
        "completion-host,pom,current",
        "completion-host,gradle,2.4.0",
        "completion-host,pom,2.4.0",
        "shell-integration,gradle,current",
        "shell-integration,pom,current",
        "shell-integration,gradle,2.4.0",
        "shell-integration,pom,2.4.0",
        "ui-swing-host,gradle,current",
        "ui-swing-host,pom,current",
        "ui-swing-host,gradle,2.4.0",
        "ui-swing-host,pom,2.4.0",
    )
    fun `already compiled Kotlin and Java clients run against the current publication`(
        module: String,
        metadata: String,
        kotlinRuntime: String,
    ) {
        val result = runClient(module, runtimeClasspath(module, metadata, kotlinRuntime), kotlinRuntime)
        assertEquals(0, result.exitCode, "$module client failed using $metadata metadata and Kotlin $kotlinRuntime:\n${result.output}")
    }

    @Test
    fun `upgrade check rejects removal of a class used by the retained client`() {
        val runtime = runtimeClasspath("parser", "gradle")
        val parser = runtime.single { it.fileName.toString().startsWith("ketraterm-parser-") }
        val incompatibleRuntime = runtime.filterNot { it == parser }
        assertTrue(incompatibleRuntime.none { it == parser })
        val result = runClient("parser", incompatibleRuntime)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("NoClassDefFoundError"), result.output)
        assertTrue(result.output.contains("io/github/ketraterm/parser"), result.output)
    }

    @Test
    fun `upgrade check rejects removal of a method used by the retained client`() {
        val source = directory.resolve("TerminalParsers.java")
        source.writeText(
            """
            package io.github.ketraterm.parser.api;
            public final class TerminalParsers {}
            """.trimIndent(),
        )
        val classes = directory.resolve("incompatible-library").createDirectories()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Upgrade controls require a JDK" }
        assertEquals(0, compiler.run(null, null, null, "--release", "25", "-proc:none", "-d", classes.toString(), source.toString()))
        val result = runClient("parser", listOf(classes) + runtimeClasspath("parser", "gradle"))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("NoSuchMethodError"), result.output)
        assertTrue(result.output.contains("TerminalParsers.create"), result.output)
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
}
