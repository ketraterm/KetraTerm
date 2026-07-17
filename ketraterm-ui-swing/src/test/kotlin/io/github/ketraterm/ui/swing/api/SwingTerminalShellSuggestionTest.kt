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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.*
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Insets
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities

class SwingTerminalShellSuggestionTest {
    @Test
    fun `public suggestion popup handles keyboard selection and host acceptance`() {
        val accepted = ArrayList<SwingShellSuggestion>()
        val indexes = ArrayList<Int>()
        val requests = ArrayList<SwingShellSuggestionRequest>()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionHandler =
                            SwingShellSuggestionHandler { acceptance ->
                                accepted += acceptance.suggestion
                                indexes += acceptance.index
                                requests += acceptance.request
                            },
                    ),
            )
        val request = request(anchorColumn = 1, anchorRow = 1)
        val suggestions = suggestions(request.commandText)

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(request, suggestions)

            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_DOWN)) }
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_ENTER)) }

            assertFalse(component.currentShellSuggestionState().visible)
        }

        assertEquals(listOf(suggestions[1]), accepted)
        assertEquals(listOf(1), indexes)
        assertEquals(listOf(request), requests)
    }

    @Test
    fun `provider backed suggestion request shows results and preserves acceptance context`() {
        val providerRequests = ArrayList<SwingShellSuggestionRequest>()
        val acceptedRequests = ArrayList<SwingShellSuggestionRequest>()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                providerRequests += request
                                suggestions(request.commandText)
                            },
                        shellSuggestionHandler =
                            SwingShellSuggestionHandler { acceptance ->
                                acceptedRequests += acceptance.request
                            },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.requestShellSuggestions(
                commandText = "git s",
                cursorOffset = 5,
                anchorColumn = 5,
                anchorRow = 2,
            )

            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(2, state.count)
            assertEquals(5, state.anchorColumn)
            assertEquals(2, state.anchorRow)

            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_ENTER)) }
        }

        val expectedRequest =
            SwingShellSuggestionRequest(
                commandText = "git s",
                cursorOffset = 5,
                anchorColumn = 5,
                anchorRow = 2,
            )
        assertEquals(listOf(expectedRequest), providerRequests)
        assertEquals(listOf(expectedRequest), acceptedRequests)
    }

    @Test
    fun `provider empty result hides current suggestion popup`() {
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = SwingShellSuggestionProvider { emptyList() },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(request(), suggestions())
            assertTrue(component.currentShellSuggestionState().visible)

            component.requestShellSuggestions(commandText = "missing", cursorOffset = 7, anchorColumn = 0, anchorRow = 0)

            assertFalse(component.currentShellSuggestionState().visible)
        }
    }

    @Test
    fun `active shell suggestion request uses bound session command snapshot`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s".utf8())
        val providerRequests = ArrayList<SwingShellSuggestionRequest>()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                providerRequests += request
                                suggestions(request.commandText)
                            },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(30, 4)
            component.bind(session)
            component.requestActiveShellSuggestions()

            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals("git status", state.selectedSuggestion?.replacementText)
        }

        assertEquals(
            listOf(
                SwingShellSuggestionRequest(
                    commandText = "git s",
                    cursorOffset = 5,
                    anchorColumn = "PS> git s".length,
                    anchorRow = 0,
                ),
            ),
            providerRequests,
        )
        session.close()
    }

    @Test
    fun `active shell suggestion request hides popup when session has no active command snapshot`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s\u001B]133;C\u0007".utf8())
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = SwingShellSuggestionProvider { request -> suggestions(request.commandText) },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(30, 4)
            component.bind(session)
            component.showShellSuggestions(request(), suggestions())
            assertTrue(component.currentShellSuggestionState().visible)

            component.requestActiveShellSuggestions()

            assertFalse(component.currentShellSuggestionState().visible)
        }

        session.close()
    }

    @Test
    fun `disabled shell suggestions setting ignores public show requests`() {
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(shellSuggestionsEnabled = false) },
            )

        SwingUtilities.invokeAndWait {
            component.showShellSuggestions(request(), suggestions())

            assertFalse(component.currentShellSuggestionState().visible)
        }
    }

    @Test
    fun `shown shell suggestion state exposes selected item`() {
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })
        val request = request(anchorColumn = 2, anchorRow = 1)
        val suggestions = suggestions(request.commandText)

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(request, suggestions, selectedIndex = 1)

            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(2, state.count)
            assertEquals(1, state.selectedIndex)
            assertEquals(suggestions[1], state.selectedSuggestion)
        }
    }

    @Test
    fun `default handler deletes standard ASCII prefix and pastes replacement`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git s")
        val suggestion =
            suggestion(
                replacementText = "git status",
                commandText = request.commandText,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(5, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("git status", session.pastes[0].text)
    }

    @Test
    fun `default handler deletes emoji prefix using grapheme clusters count`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)
        val commandText = "a\uD83D\uDE02"

        val request = request(commandText = commandText)
        val suggestion =
            suggestion(
                replacementText = "$commandText b",
                commandText = commandText,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(2, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("$commandText b", session.pastes[0].text)
    }

    @Test
    fun `default handler deletes combining accents prefix using grapheme clusters count`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)
        val commandText = "e\u0301"

        val request = request(commandText = commandText)
        val suggestion =
            suggestion(
                replacementText = "$commandText test",
                commandText = commandText,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(1, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("$commandText test", session.pastes[0].text)
    }

    @Test
    fun `default handler treats extended emoji sequences as single grapheme clusters`() {
        val clusters = listOf("\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDDE6\uD83C\uDDF2", "\uD83D\uDC4D\uD83C\uDFFD")

        for (cluster in clusters) {
            val session = RecordingInputEncoder()
            val handler = SwingShellSuggestionHandler.createDefault(session)
            val request = request(commandText = cluster)
            val suggestion = suggestion(replacementText = "$cluster accepted", commandText = cluster)

            handler.onSuggestionAccepted(SwingShellSuggestionAcceptance(suggestion, 0, request))

            assertEquals(1, session.keys.size, "cluster=$cluster")
            assertEquals(TerminalKey.BACKSPACE, session.keys.single().key, "cluster=$cluster")
            assertEquals(1, session.replacements.size, "cluster=$cluster")
        }
    }

    @Test
    fun `default handler rejects replacement range inside grapheme cluster`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)
        val commandText = "e\u0301"
        val request = request(commandText = commandText)
        val suggestion =
            suggestion(
                replacementText = "x",
                startOffset = 1,
                endOffset = commandText.length,
            )

        handler.onSuggestionAccepted(SwingShellSuggestionAcceptance(suggestion, 0, request))

        assertTrue(session.replacements.isEmpty())
        assertTrue(session.keys.isEmpty())
        assertTrue(session.pastes.isEmpty())
    }

    @Test
    fun `default handler replaces token range`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git s")
        val suggestion =
            suggestion(
                replacementText = "status",
                startOffset = 4,
                endOffset = 5,
                source = "spec",
                kind = "SUBCOMMAND",
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(1, session.keys.size)
        assertEquals(TerminalKey.BACKSPACE, session.keys[0].key)
        assertEquals(1, session.pastes.size)
        assertEquals("status", session.pastes[0].text)
    }

    @Test
    fun `default handler replaces explicit range around cursor`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git che", cursorOffset = 6)
        val suggestion =
            suggestion(
                replacementText = "checkout",
                startOffset = 4,
                endOffset = 7,
                source = "spec",
                kind = "SUBCOMMAND",
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(
            listOf(TerminalKey.DELETE, TerminalKey.BACKSPACE, TerminalKey.BACKSPACE),
            session.keys.map { it.key },
        )
        assertEquals(1, session.pastes.size)
        assertEquals("checkout", session.pastes[0].text)
        assertEquals(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 1,
                deleteBeforeCursorCount = 2,
                replacementText = "checkout",
            ),
            session.replacements.single(),
        )
    }

    @Test
    fun `default handler ignores explicit range outside request text`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git che", cursorOffset = 6)
        val suggestion =
            suggestion(
                replacementText = "checkout",
                startOffset = 4,
                endOffset = 20,
                source = "spec",
                kind = "SUBCOMMAND",
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertTrue(session.keys.isEmpty())
        assertTrue(session.pastes.isEmpty())
    }

    private class RecordingInputEncoder : TerminalInputEncoder {
        val keys = ArrayList<TerminalKeyEvent>()
        val pastes = ArrayList<TerminalPasteEvent>()
        val replacements = ArrayList<TerminalTextReplacementEvent>()

        override fun encodeKey(event: TerminalKeyEvent) {
            keys += event
        }

        override fun encodePaste(event: TerminalPasteEvent) {
            pastes += event
        }

        override fun encodeTextReplacement(event: TerminalTextReplacementEvent) {
            replacements += event
            super<TerminalInputEncoder>.encodeTextReplacement(event)
        }

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    private class RecordingConnector : TerminalConnector {
        private var listener: TerminalConnectorListener? = null

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit

        fun feedFromHost(bytes: ByteArray) {
            listener?.onBytes(bytes, 0, bytes.size)
        }
    }

    private fun activeSuggestionSession(connector: RecordingConnector): TerminalSession {
        val terminal = TerminalBuffers.create(width = 30, height = 4, maxHistory = 20)
        val session = TerminalSession.create(terminal = terminal, connector = connector)
        session.start(columns = 30, rows = 4)
        return session
    }

    private fun suggestions(commandText: String = "git s"): List<SwingShellSuggestion> =
        listOf(
            suggestion(
                replacementText = "git status",
                commandText = commandText,
                detail = "show working tree status",
                source = "history",
                kind = "HISTORY",
            ),
            suggestion(
                replacementText = "git switch main",
                commandText = commandText,
                detail = "switch to main branch",
                source = "git",
                kind = "SUBCOMMAND",
            ),
        )

    private fun suggestion(
        replacementText: String,
        commandText: String = "git s",
        startOffset: Int = 0,
        endOffset: Int = commandText.length,
        source: String = "test",
        kind: String = "COMMAND",
        detail: String = "",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = replacementText,
            replacementStartOffset = startOffset,
            replacementEndOffset = endOffset,
            source = source,
            kind = kind,
            detail = detail,
        )

    private fun request(
        commandText: String = "git s",
        cursorOffset: Int = commandText.length,
        anchorColumn: Int = cursorOffset,
        anchorRow: Int = 0,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = anchorColumn,
            anchorRow = anchorRow,
        )

    private fun keyPressed(
        component: SwingTerminal,
        keyCode: Int,
    ): KeyEvent =
        KeyEvent(
            component,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
