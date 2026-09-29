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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class TerminalLibraryConsumerCompilationTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @CsvSource(
        "host,io.github.ketraterm.host.HostPolicy",
        "parser,io.github.ketraterm.parser.api.TerminalOutputParser",
        "completion,io.github.ketraterm.completion.api.TerminalCompletionCandidateKind",
    )
    fun `isolated consumer classpaths contain the requested library and Kotlin runtime`(
        module: String,
        publicType: String,
    ) {
        assertCompiles(
            module,
            """
            final class Consumer {
                Class<?> libraryType() { return $publicType.class; }
                Object runtimeValue() { return kotlin.Unit.INSTANCE; }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `host dependency exports the types needed to call its adapter`() {
        assertCompiles(
            "host",
            """
            import io.github.ketraterm.host.HostCommandAdapter;

            final class Consumer {
                void write(HostCommandAdapter adapter) {
                    adapter.writeCodepoint(65);
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `parser dependency exports protocol types in its sink contract`() {
        assertCompiles(
            "parser",
            """
            import io.github.ketraterm.parser.spi.TerminalCommandSink;
            import io.github.ketraterm.protocol.NotificationLevel;
            import io.github.ketraterm.protocol.ShellIntegrationEvent;

            final class Consumer {
                void notify(TerminalCommandSink sink, NotificationLevel level) {
                    sink.showNotification("title", "body", level);
                }

                void mark(TerminalCommandSink sink, ShellIntegrationEvent event) {
                    sink.shellIntegrationMarker(event);
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `host dependency exports the core types needed to construct its adapter`() {
        assertCompiles(
            "host",
            """
            import io.github.ketraterm.core.api.TerminalBuffer;
            import io.github.ketraterm.host.HostCommandAdapter;
            import io.github.ketraterm.host.HostEventSink;
            import io.github.ketraterm.host.HostPolicy;

            final class Consumer {
                HostCommandAdapter create(TerminalBuffer buffer, HostEventSink events, HostPolicy policy) {
                    return new HostCommandAdapter(buffer, events, policy, 0, 0, false);
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `completion dependency exports the flow returned by its engine`() {
        assertCompiles(
            "completion",
            """
            import io.github.ketraterm.completion.api.TerminalCompletionEngine;
            import io.github.ketraterm.completion.api.TerminalCompletionRequest;

            final class Consumer {
                Object stream(TerminalCompletionEngine engine, TerminalCompletionRequest request) {
                    return engine.completions(request);
                }
            }
            """.trimIndent(),
        )
    }

    private fun assertCompiles(
        module: String,
        source: String,
    ) {
        val classpaths =
            Path.of(
                checkNotNull(System.getProperty("ketraterm.consumerClasspaths")) {
                    "Run this integration test through :ketraterm-testkit:test to resolve the consumer API variants"
                },
            )
        val jars = classpaths.resolve(module).listDirectoryEntries().filter { it.extension == "jar" }
        assertTrue(jars.isNotEmpty(), "No exported API artifacts were prepared for $module")
        val sourceFile = directory.resolve("Consumer.java").apply { writeText(source) }
        val outputDirectory = Files.createDirectory(directory.resolve("classes"))
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Consumer compilation requires a JDK" }
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8).use { fileManager ->
            val compiled =
                compiler
                    .getTask(
                        null,
                        fileManager,
                        diagnostics,
                        listOf(
                            "--release",
                            "25",
                            "-proc:none",
                            "-classpath",
                            jars.joinToString(File.pathSeparator),
                            "-d",
                            outputDirectory.toString(),
                        ),
                        null,
                        fileManager.getJavaFileObjects(sourceFile.toFile()),
                    ).call()
            assertTrue(
                compiled,
                "A consumer depending only on ketraterm-$module must compile:\n" +
                    diagnostics.diagnostics.joinToString("\n") { it.getMessage(Locale.ROOT) },
            )
        }
    }
}
