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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalLibraryConsumerCompilationTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @CsvSource(
        "getShellIntegrationState().clear()",
        "getShellIntegrationState().recordPromptStart(1L)",
        "getRenderPublisher().updateAndPublish(null)",
    )
    fun `session consumers cannot publish through their read access`(operation: String) {
        assertCompilation(
            "ui-swing",
            """
            import io.github.ketraterm.session.TerminalSession;
            final class Consumer {
                void mutate(TerminalSession session) { session.$operation; }
            }
            """.trimIndent(),
            expectedSuccess = false,
        )
    }

    @Test
    fun `Java consumers can observe shell projections and borrow session frames`() {
        assertCompilation(
            "ui-swing",
            """
            import io.github.ketraterm.session.TerminalSession;
            import io.github.ketraterm.session.TerminalShellIntegrationView;
            final class Consumer {
                Integer read(TerminalSession session) {
                    TerminalShellIntegrationView view = session.getShellIntegrationState();
                    int records = view.recordCount();
                    return session.readPublishedFrame(cache -> cache.getCodeWords()[0] + records);
                }
            }
            """.trimIndent(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "host,io.github.ketraterm.host.HostPolicy",
        "input,io.github.ketraterm.input.event.TerminalKeyEvent",
        "parser,io.github.ketraterm.parser.api.TerminalOutputParser",
        "completion,io.github.ketraterm.completion.api.TerminalCompletionCandidateKind",
        "completion-host,io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner",
        "ui-swing,io.github.ketraterm.ui.swing.api.SwingTerminal",
        "ui-swing-host,io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider",
        "pty,io.github.ketraterm.pty.PtyConnector",
    )
    fun `isolated consumer classpaths contain the requested library and Kotlin runtime`(
        module: String,
        publicType: String,
    ) {
        assertCompilation(
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
    fun `Swing exports clipboard independent selected text to Java`() {
        assertCompilation(
            "ui-swing",
            """
            import io.github.ketraterm.ui.swing.api.SwingTerminal;
            final class Consumer {
                String readSelection(SwingTerminal terminal) {
                    return terminal.selectedText();
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `Swing host exports native popup coordination and independently callable provider to Java`() {
        assertCompilation(
            "ui-swing-host",
            """
            import io.github.ketraterm.completion.api.TerminalCompletionEngine;
            import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionInteraction;
            import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionTarget;
            import io.github.ketraterm.ui.swing.api.SwingTerminal;
            import io.github.ketraterm.ui.swing.host.*;
            import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest;
            import kotlin.jvm.functions.Function0;

            final class Consumer {
                static final class Popup implements SwingShellSuggestionTarget {
                    @Override public void requestSuggestions(SwingShellSuggestionInteraction interaction) {}
                    @Override public void hideSuggestions() {}
                }
                void wire(SwingTerminal terminal,
                          TerminalCompletionEngine engine, Function0<SwingCompletionContext> context) {
                    terminal.setShellSuggestionTarget(new Popup());
                    var provider = new SwingCompletionSuggestionProvider(engine, context);
                    terminal.setShellSuggestionProvider(provider);
                    terminal.refreshShellSuggestions();
                    provider.suggestions(new SwingShellSuggestionRequest("git", 3));
                    terminal.copyCellBounds(3, 0, new java.awt.Rectangle());
                    terminal.setShellSuggestionProvider(null);
                    terminal.setShellSuggestionTarget(null);
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `Swing exports independent suggestion editing publication and presentation to Java`() {
        assertCompilation(
            "ui-swing",
            """
            import io.github.ketraterm.ui.swing.api.SwingTerminal;
            import io.github.ketraterm.ui.swing.suggestion.*;
            import java.util.List;

            final class Consumer {
                void suggestions(SwingShellSuggestionFeedbackHandler feedback) {
                    var terminal = new SwingTerminal();
                    try {
                        var interaction = terminal.beginShellSuggestionInteraction(
                            new SwingShellSuggestionRequest("git st", 6),
                            SwingShellSuggestionTrigger.EXPLICIT,
                            feedback,
                            request -> acceptance -> SwingShellSuggestionAcceptanceResult.REJECTED);
                        if (interaction == null) return;
                        interaction.publish(List.of(new SwingShellSuggestion("status", 4, 6, "native", "SUBCOMMAND")));
                        terminal.presentShellSuggestions(interaction, 6, 0);
                        interaction.tryAccept(interaction.getSnapshot(), 0);
                        interaction.close();
                    } finally {
                        terminal.dispose();
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `completion host exports Java scanner configuration overloads`() {
        assertCompilation(
            "completion-host",
            """
            import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner;
            import kotlinx.coroutines.Dispatchers;

            final class Consumer {
                TerminalBoundedDirectoryScanner[] scanners() {
                    return new TerminalBoundedDirectoryScanner[] {
                        new TerminalBoundedDirectoryScanner(),
                        new TerminalBoundedDirectoryScanner(100),
                        new TerminalBoundedDirectoryScanner(100, 1_000_000L),
                        new TerminalBoundedDirectoryScanner(100, 1_000_000L, Dispatchers.getIO())
                    };
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `host dependency exports the types needed to call its adapter`() {
        assertCompilation(
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
        assertCompilation(
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
        assertCompilation(
            "host",
            """
            import io.github.ketraterm.core.api.TerminalBuffer;
            import io.github.ketraterm.host.HostCommandAdapter;
            import io.github.ketraterm.host.HostEventSink;
            import io.github.ketraterm.host.HostPolicy;

            final class Consumer {
                HostCommandAdapter[] create(TerminalBuffer buffer, HostEventSink events, HostPolicy policy) {
                    return new HostCommandAdapter[] {
                        new HostCommandAdapter(buffer),
                        new HostCommandAdapter(buffer, events),
                        new HostCommandAdapter(buffer, events, policy),
                        new HostCommandAdapter(buffer, events, policy, 0),
                        new HostCommandAdapter(buffer, events, policy, 0, 0),
                        new HostCommandAdapter(buffer, events, policy, 0, 0, false)
                    };
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `input dependency exports static key event factories and Java overloads`() {
        assertCompilation(
            "input",
            """
            import io.github.ketraterm.input.event.*;

            final class Consumer {
                TerminalKeyEvent[] events() {
                    return new TerminalKeyEvent[] {
                        TerminalKeyEvent.key(TerminalKey.UP),
                        TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL),
                        TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL, TerminalKeyEventType.RELEASE),
                        TerminalKeyEvent.codepoint('a'),
                        TerminalKeyEvent.codepoint('a', TerminalModifiers.CTRL),
                        TerminalKeyEvent.codepoint('a', TerminalModifiers.CTRL, 'a'),
                        TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A'),
                        TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A', 'a'),
                        TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A', 'a', "A"),
                        TerminalKeyEvent.codepoint('A', TerminalModifiers.SHIFT, 'a', 'A', 'a', "A", TerminalKeyEventType.REPEAT),
                        TerminalKeyEvent.text("committed"),
                        TerminalKeyEvent.text("committed", TerminalModifiers.CTRL),
                        TerminalKeyEvent.text("committed", TerminalModifiers.CTRL, TerminalKeyEventType.REPEAT)
                    };
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `completion exports restrictive replay construction to Java with existing defaults`() {
        assertCompilation(
            "completion",
            """
            import io.github.ketraterm.completion.api.TerminalCompletionLearningStore;
            final class Consumer {
                TerminalCompletionLearningStore[] stores() {
                    return new TerminalCompletionLearningStore[] {
                        new TerminalCompletionLearningStore(),
                        new TerminalCompletionLearningStore(128),
                        new TerminalCompletionLearningStore(command -> false),
                        new TerminalCompletionLearningStore(command -> !command.startsWith("acme "), 128)
                    };
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `independent Java input state can pack every key resource without a terminal buffer`() {
        assertCompilation(
            "input",
            """
            import io.github.ketraterm.core.api.TerminalInputState;
            import io.github.ketraterm.input.TerminalInputEncoders;
            import io.github.ketraterm.input.event.TerminalKey;
            import io.github.ketraterm.input.event.TerminalKeyEvent;
            import io.github.ketraterm.protocol.keyboard.XtermKeyResource;
            import io.github.ketraterm.protocol.host.TerminalHostOutput;

            final class Consumer implements TerminalInputState {
                private volatile long bits;

                void configure() {
                    long next = 0L;
                    int[] resources = {
                        XtermKeyResource.KEYBOARD, XtermKeyResource.CURSOR_KEYS,
                        XtermKeyResource.FUNCTION_KEYS, XtermKeyResource.KEYPAD_KEYS,
                        XtermKeyResource.OTHER_KEYS, XtermKeyResource.MODIFIER_KEYS,
                        XtermKeyResource.SPECIAL_KEYS
                    };
                    for (int resource : resources) {
                        next = TerminalInputState.withKeyModifierOption(next, resource, -1);
                        next = TerminalInputState.withKeyFormatOption(next, resource, 1);
                    }
                    bits = next;
                }

                @Override public long getInputModeBits() { return bits; }

                void encode(TerminalHostOutput output) {
                    TerminalInputEncoders.create(this, output).encodeKey(TerminalKeyEvent.key(TerminalKey.UP));
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `completion dependency exports the flow returned by its engine`() {
        assertCompilation(
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

    @Test
    fun `Swing dependency exports session and flow types needed by host integration`() {
        assertCompilation(
            "ui-swing",
            """
            import io.github.ketraterm.session.TerminalSession;
            import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetector;
            import io.github.ketraterm.ui.swing.api.SwingTerminal;
            import kotlinx.coroutines.flow.Flow;
            import kotlin.Unit;

            final class Consumer {
                void bind(SwingTerminal terminal, TerminalSession session) {
                    terminal.bind(session);
                }

                Flow<Unit> changes(SwingHyperlinkDetector detector) {
                    return detector.getConfigurationChanges();
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `PTY dependency exports the native process type in its public connector constructor`() {
        assertCompilation(
            "pty",
            """
            import com.pty4j.PtyProcess;
            import io.github.ketraterm.pty.PtyConnector;

            final class Consumer {
                PtyConnector connector(PtyProcess process) {
                    return new PtyConnector(process, 4096, "reader", "watcher");
                }
            }
            """.trimIndent(),
        )
    }

    private fun assertCompilation(
        module: String,
        source: String,
        expectedSuccess: Boolean = true,
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
            assertEquals(
                compiled,
                expectedSuccess,
                "Consumer of ketraterm-$module expected compilation success=$expectedSuccess:\n" +
                    diagnostics.diagnostics.joinToString("\n") { it.getMessage(Locale.ROOT) },
            )
        }
    }
}
