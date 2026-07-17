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

import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.JPanel

class SwingShellSuggestionControllerTest {
    private val source = JPanel()

    @Test
    fun `show publishes visible state with explicit selection and anchor`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val suggestions = suggestions(3)
        val request = request(anchorColumn = 7, anchorRow = 2)

        val shown = controller.show(request, suggestions, selectedIndex = 2)
        val state = controller.state()

        assertTrue(shown)
        assertTrue(state.visible)
        assertEquals(3, state.count)
        assertEquals(2, state.selectedIndex)
        assertEquals(7, state.anchorColumn)
        assertEquals(2, state.anchorRow)
        assertSame(suggestions[2], state.selectedSuggestion)
    }

    @Test
    fun `show keeps popup passive when no valid initial selection is supplied`() {
        val controller = SwingShellSuggestionController(RecordingSuggestionHost())

        controller.show(request(), suggestions(3), selectedIndex = 99)

        assertEquals(-1, controller.state().selectedIndex)
        assertNull(controller.state().selectedSuggestion)
    }

    @Test
    fun `disabled settings hide popup and ignore incoming suggestions`() {
        val host = RecordingSuggestionHost(settings = SwingSettings(shellSuggestionsEnabled = false))
        val controller = SwingShellSuggestionController(host)

        val shown = controller.show(request(), suggestions(2), selectedIndex = 0)

        assertFalse(shown)
        assertFalse(controller.state().visible)
        assertFalse(controller.popup.isVisible)
    }

    @Test
    fun `navigation keys update selected suggestion and consume event`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(3), selectedIndex = -1)
        val down = keyPressed(KeyEvent.VK_DOWN)
        val up = keyPressed(KeyEvent.VK_UP)

        assertTrue(controller.handleKeyPressed(down))
        assertEquals(0, controller.state().selectedIndex)
        assertTrue(down.isConsumed)

        assertTrue(controller.handleKeyPressed(up))
        assertEquals(0, controller.state().selectedIndex)
        assertTrue(up.isConsumed)
    }

    @Test
    fun `host keymap controls acceptance bindings`() {
        val host =
            RecordingSuggestionHost(
                suggestionKeymap =
                    SwingShellSuggestionKeymap { event ->
                        if (event.keyCode == KeyEvent.VK_F2) SwingShellSuggestionAction.ACCEPT else null
                    },
            )
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(1), selectedIndex = 0)

        assertFalse(controller.handleKeyPressed(keyPressed(KeyEvent.VK_ENTER)))
        assertTrue(controller.handleKeyPressed(keyPressed(KeyEvent.VK_F2)))
        assertEquals(1, host.acceptedSuggestions.size)
    }

    @Test
    fun `up from a passive popup selects the last visible suggestion`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)

        controller.show(request(), suggestions(20), selectedIndex = -1)
        controller.handleKeyPressed(keyPressed(KeyEvent.VK_UP))

        assertEquals(8, controller.state().count)
        assertEquals(7, controller.state().selectedIndex)
    }

    @Test
    fun `enter always passes through to the shell even when a suggestion is selected`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val request = request(commandText = "git sw", cursorOffset = 6)
        val items = suggestions(2, endOffset = request.commandText.length)
        controller.show(request, items, selectedIndex = 1)
        val enter = keyPressed(KeyEvent.VK_ENTER)

        assertFalse(controller.handleKeyPressed(enter))

        assertTrue(controller.state().visible)
        assertTrue(host.acceptedSuggestions.isEmpty())
        assertTrue(host.feedbackKinds.isEmpty())
        assertFalse(enter.isConsumed)
    }

    @Test
    fun `tab selects first suggestion before accepting it`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val request = request(commandText = "cd ", cursorOffset = 3)
        val items = suggestions(2, endOffset = request.commandText.length)
        controller.show(request, items, selectedIndex = -1)

        val firstTab = keyPressed(KeyEvent.VK_TAB)
        assertTrue(controller.handleKeyPressed(firstTab))
        assertEquals(0, controller.state().selectedIndex)
        assertTrue(host.acceptedSuggestions.isEmpty())

        val secondTab = keyPressed(KeyEvent.VK_TAB)
        assertTrue(controller.handleKeyPressed(secondTab))

        assertFalse(controller.state().visible)
        assertEquals(listOf(0), host.acceptedIndexes)
        assertEquals(listOf(items[0]), host.acceptedSuggestions)
        assertEquals(listOf(request), host.acceptedRequests)
        assertEquals(listOf(SwingShellSuggestionFeedbackKind.ACCEPTED), host.feedbackKinds)
        assertEquals(listOf(items[0]), host.feedbackSuggestions)
        assertEquals(1, host.focusRequests)
        assertTrue(secondTab.isConsumed)
    }

    @Test
    fun `escape hides popup records dismissal without accepting`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val items = suggestions(2)
        controller.show(request(), items, selectedIndex = 0)
        val escape = keyPressed(KeyEvent.VK_ESCAPE)

        assertTrue(controller.handleKeyPressed(escape))

        assertFalse(controller.state().visible)
        assertTrue(host.acceptedSuggestions.isEmpty())
        assertEquals(listOf(SwingShellSuggestionFeedbackKind.DISMISSED), host.feedbackKinds)
        assertEquals(listOf(items[0]), host.feedbackSuggestions)
        assertEquals(1, host.focusRequests)
        assertTrue(escape.isConsumed)
    }

    @Test
    fun `reload settings hides visible popup when setting is disabled`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(2), selectedIndex = 0)

        host.settings = SwingSettings(shellSuggestionsEnabled = false)
        controller.reloadSettings()

        assertFalse(controller.state().visible)
    }

    private fun suggestions(
        count: Int,
        startOffset: Int = 0,
        endOffset: Int = 0,
    ): List<SwingShellSuggestion> =
        List(count) { index ->
            SwingShellSuggestion(
                replacementText = "command-$index",
                replacementStartOffset = startOffset,
                replacementEndOffset = endOffset,
                source = "test",
                kind = "COMMAND",
                displayText = "command-$index",
                detail = "detail-$index",
            )
        }

    private fun request(
        commandText: String = "",
        cursorOffset: Int = commandText.length,
        anchorColumn: Int = 0,
        anchorRow: Int = 0,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = anchorColumn,
            anchorRow = anchorRow,
        )

    private fun keyPressed(keyCode: Int): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private class RecordingSuggestionHost(
        override var settings: SwingSettings = SwingSettings(),
        override val suggestionKeymap: SwingShellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD,
    ) : SwingShellSuggestionHost {
        val acceptedSuggestions = ArrayList<SwingShellSuggestion>()
        val acceptedIndexes = ArrayList<Int>()
        val acceptedRequests = ArrayList<SwingShellSuggestionRequest>()
        val feedbackKinds = ArrayList<SwingShellSuggestionFeedbackKind>()
        val feedbackSuggestions = ArrayList<SwingShellSuggestion>()
        var focusRequests = 0
        var revalidations = 0
        var repaints = 0

        override val suggestionHandler: SwingShellSuggestionHandler =
            SwingShellSuggestionHandler { acceptance ->
                acceptedSuggestions += acceptance.suggestion
                acceptedIndexes += acceptance.index
                acceptedRequests += acceptance.request
            }

        override val suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler =
            SwingShellSuggestionFeedbackHandler { feedback ->
                feedbackKinds += feedback.kind
                feedbackSuggestions += feedback.suggestion
            }

        override fun revalidate() {
            revalidations++
        }

        override fun repaint() {
            repaints++
        }

        override fun requestFocusInWindow(): Boolean {
            focusRequests++
            return true
        }
    }
}
