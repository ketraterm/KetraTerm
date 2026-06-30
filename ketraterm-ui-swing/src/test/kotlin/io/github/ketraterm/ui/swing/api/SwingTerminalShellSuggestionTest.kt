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

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.*
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Insets
import java.awt.event.KeyEvent
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
        val suggestions = suggestions()

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(suggestions, anchorColumn = 1, anchorRow = 1)

            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_DOWN)) }
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_ENTER)) }

            assertFalse(component.currentShellSuggestionState().visible)
        }

        assertEquals(listOf(suggestions[1]), accepted)
        assertEquals(listOf(1), indexes)
        assertEquals(
            listOf(
                SwingShellSuggestionRequest(
                    commandText = "",
                    cursorOffset = 0,
                    anchorColumn = 1,
                    anchorRow = 1,
                ),
            ),
            requests,
        )
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
                                suggestions()
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
            component.showShellSuggestions(suggestions(), anchorColumn = 0, anchorRow = 0)
            assertTrue(component.currentShellSuggestionState().visible)

            component.requestShellSuggestions(commandText = "missing", cursorOffset = 7, anchorColumn = 0, anchorRow = 0)

            assertFalse(component.currentShellSuggestionState().visible)
        }
    }

    @Test
    fun `disabled shell suggestions setting ignores public show requests`() {
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(shellSuggestionsEnabled = false) },
            )

        SwingUtilities.invokeAndWait {
            component.showShellSuggestions(suggestions(), anchorColumn = 0, anchorRow = 0)

            assertFalse(component.currentShellSuggestionState().visible)
        }
    }

    @Test
    fun `shown shell suggestion state exposes selected item`() {
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })
        val suggestions = suggestions()

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(suggestions, anchorColumn = 2, anchorRow = 1, selectedIndex = 1)

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

        val request = SwingShellSuggestionRequest("git s", cursorOffset = 5, anchorColumn = 0, anchorRow = 0)
        val suggestion = SwingShellSuggestion("git status")
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

        // "a😂" is 3 UTF-16 code units (1 for 'a', 2 for '😂') but 2 grapheme clusters.
        val request = SwingShellSuggestionRequest("a😂", cursorOffset = 3, anchorColumn = 0, anchorRow = 0)
        val suggestion = SwingShellSuggestion("a😂 b")
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(2, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("a😂 b", session.pastes[0].text)
    }

    @Test
    fun `default handler deletes combining accents prefix using grapheme clusters count`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        // "é" is 'e' + combining acute accent (2 code points, 2 code units) but 1 grapheme cluster.
        val request = SwingShellSuggestionRequest("é", cursorOffset = 2, anchorColumn = 0, anchorRow = 0)
        val suggestion = SwingShellSuggestion("é test")
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(1, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("é test", session.pastes[0].text)
    }

    @Test
    fun `default handler respects custom deleteCount override`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = SwingShellSuggestionRequest("git s", cursorOffset = 5, anchorColumn = 0, anchorRow = 0)
        val suggestion = SwingShellSuggestion("status", deleteCount = 1) // only delete 's'
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

        val request = SwingShellSuggestionRequest("git che", cursorOffset = 6, anchorColumn = 0, anchorRow = 0)
        val suggestion =
            SwingShellSuggestion(
                replacementText = "checkout",
                replacementStartOffset = 4,
                replacementEndOffset = 7,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(
            listOf(TerminalKey.DELETE, TerminalKey.BACKSPACE, TerminalKey.BACKSPACE),
            session.keys.map { it.key },
        )
        assertEquals(1, session.pastes.size)
        assertEquals("checkout", session.pastes[0].text)
    }

    @Test
    fun `default handler ignores explicit range outside request text`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = SwingShellSuggestionRequest("git che", cursorOffset = 6, anchorColumn = 0, anchorRow = 0)
        val suggestion =
            SwingShellSuggestion(
                replacementText = "checkout",
                replacementStartOffset = 4,
                replacementEndOffset = 20,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertTrue(session.keys.isEmpty())
        assertTrue(session.pastes.isEmpty())
    }

    private class RecordingInputEncoder : TerminalInputEncoder {
        val keys = ArrayList<TerminalKeyEvent>()
        val pastes = ArrayList<TerminalPasteEvent>()

        override fun encodeKey(event: TerminalKeyEvent) {
            keys += event
        }

        override fun encodePaste(event: TerminalPasteEvent) {
            pastes += event
        }

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    private fun suggestions(): List<SwingShellSuggestion> =
        listOf(
            SwingShellSuggestion("git status", detail = "show working tree status", source = "history"),
            SwingShellSuggestion("git switch main", detail = "switch to main branch", source = "git"),
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
}
