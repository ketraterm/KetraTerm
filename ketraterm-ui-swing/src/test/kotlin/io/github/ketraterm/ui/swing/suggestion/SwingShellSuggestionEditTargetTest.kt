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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class SwingShellSuggestionEditTargetTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `default target uses the captured prefix reset when available`(prefixReset: Boolean) =
        runTest {
            for (cluster in listOf("e\u0301", "\uD83D\uDE80", "\uD83D\uDC69\u200D\uD83D\uDCBB")) {
                val text = "echo $cluster"
                val cursor = text.length
                val connector = RecordingConnector()
                session(connector, text, prefixReset = prefixReset).use { session ->
                    val request = SwingShellSuggestionRequest(text, cursor)
                    val suggestion =
                        SwingShellSuggestion(
                            replacementText = "accepted",
                            replacementStartOffset = 5,
                            replacementEndOffset = text.length,
                            source = "test",
                            kind = "ARGUMENT",
                        )
                    SwingUtilities.invokeAndWait {
                        assertNotNull(SwingShellSuggestionEditTarget.createDefault(session).capture(request)).use { handler ->
                            assertEquals(
                                SwingShellSuggestionAcceptanceResult.ACCEPTED,
                                handler.tryAccept(SwingShellSuggestionAcceptance(suggestion, 0, request)),
                            )
                        }
                    }
                    runCurrent()
                    assertEquals(
                        if (prefixReset) "\u001b[24~recho accepted" else "\u007faccepted",
                        connector.writtenBytes.toString(Charsets.UTF_8),
                        "prefixReset=$prefixReset: $cluster",
                    )
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `middle cursor keeps range editing even with a prefix reset capability`(prefixReset: Boolean) =
        runTest {
            val text = "echo ab"
            val connector = RecordingConnector()
            session(connector, text, cursor = 6, prefixReset = prefixReset).use { session ->
                val request = SwingShellSuggestionRequest(text, 6)
                val suggestion = SwingShellSuggestion("accepted", 5, 7, "test", "ARGUMENT")
                SwingUtilities.invokeAndWait {
                    assertNotNull(SwingShellSuggestionEditTarget.createDefault(session).capture(request)).use { handler ->
                        assertEquals(
                            SwingShellSuggestionAcceptanceResult.ACCEPTED,
                            handler.tryAccept(SwingShellSuggestionAcceptance(suggestion, 0, request)),
                        )
                    }
                }
                runCurrent()
                assertEquals("\u001b[3~\u007faccepted", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r", "\t", "\u001b", "\u007f", "\u0085"])
    fun `prefix reset rejects retained controls without sending input`(control: String) =
        runTest {
            val text = "echo${control}value"
            val connector = RecordingConnector()
            session(connector, text).use { session ->
                val request = SwingShellSuggestionRequest(text, text.length)
                val suggestion = SwingShellSuggestion("accepted", text.length - 5, text.length, "test", "ARGUMENT")
                SwingUtilities.invokeAndWait {
                    assertNotNull(SwingShellSuggestionEditTarget.createDefault(session).capture(request)).use { handler ->
                        assertEquals(
                            SwingShellSuggestionAcceptanceResult.UNSUPPORTED,
                            handler.tryAccept(SwingShellSuggestionAcceptance(suggestion, 0, request)),
                        )
                    }
                }
                runCurrent()
                assertEquals(0, connector.writtenBytes.size())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r", "\t", "\u001b", "\u007f", "\u0085"])
    fun `prefix reset rejects controls supplied by a custom suggestion`(control: String) =
        runTest {
            val text = "echo value"
            val connector = RecordingConnector()
            session(connector, text).use { session ->
                val request = SwingShellSuggestionRequest(text, text.length)
                val suggestion = SwingShellSuggestion("accepted${control}text", 5, text.length, "test", "ARGUMENT")
                SwingUtilities.invokeAndWait {
                    assertNotNull(SwingShellSuggestionEditTarget.createDefault(session).capture(request)).use { handler ->
                        assertEquals(
                            SwingShellSuggestionAcceptanceResult.UNSUPPORTED,
                            handler.tryAccept(SwingShellSuggestionAcceptance(suggestion, 0, request)),
                        )
                    }
                }
                runCurrent()
                assertEquals(0, connector.writtenBytes.size())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r", "\t", "\u001b", "\u007f", "\u0085"])
    fun `prefix reset allows controls removed by the replacement`(control: String) =
        runTest {
            val text = "echo${control}value"
            val connector = RecordingConnector()
            session(connector, text).use { session ->
                val request = SwingShellSuggestionRequest(text, text.length)
                val suggestion = SwingShellSuggestion("echo accepted", 0, text.length, "test", "ARGUMENT")
                SwingUtilities.invokeAndWait {
                    assertNotNull(SwingShellSuggestionEditTarget.createDefault(session).capture(request)).use { handler ->
                        assertEquals(
                            SwingShellSuggestionAcceptanceResult.ACCEPTED,
                            handler.tryAccept(SwingShellSuggestionAcceptance(suggestion, 0, request)),
                        )
                    }
                }
                runCurrent()
                assertEquals("\u001b[24~recho accepted", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    private fun TestScope.session(
        connector: RecordingConnector,
        text: String,
        cursor: Int = text.length,
        prefixReset: Boolean = true,
    ): TerminalSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val keys =
            if (prefixReset) {
                listOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent(codepoint = 'r'.code))
            } else {
                emptyList()
            }
        val state = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot(text, cursor, 6, 0))
        return TerminalSession
            .create(
                TerminalBuffers.create(30, 3),
                connector,
                shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), state, keys),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            ).also { it.start() }
    }

    private class RecordingConnector : TerminalConnector {
        val writtenBytes = ByteArrayOutputStream()

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writtenBytes.write(bytes, offset, length)
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }
}
