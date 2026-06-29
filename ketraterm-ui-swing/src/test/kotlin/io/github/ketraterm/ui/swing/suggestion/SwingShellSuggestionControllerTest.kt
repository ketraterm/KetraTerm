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
    fun `show publishes visible state with clamped selected index and anchor`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val suggestions = suggestions(3)

        val shown = controller.show(suggestions, anchorColumn = 7, anchorRow = 2, selectedIndex = 99)
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
    fun `disabled settings hide popup and ignore incoming suggestions`() {
        val host = RecordingSuggestionHost(settings = SwingSettings(shellSuggestionsEnabled = false))
        val controller = SwingShellSuggestionController(host)

        val shown = controller.show(suggestions(2), anchorColumn = 0, anchorRow = 0, selectedIndex = 0)

        assertFalse(shown)
        assertFalse(controller.state().visible)
        assertFalse(controller.popup.isVisible)
    }

    @Test
    fun `navigation keys update selected suggestion and consume event`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(suggestions(3), anchorColumn = 0, anchorRow = 0, selectedIndex = 0)
        val down = keyPressed(KeyEvent.VK_DOWN)
        val up = keyPressed(KeyEvent.VK_UP)

        assertTrue(controller.handleKeyPressed(down))
        assertEquals(1, controller.state().selectedIndex)
        assertTrue(down.isConsumed)

        assertTrue(controller.handleKeyPressed(up))
        assertEquals(0, controller.state().selectedIndex)
        assertTrue(up.isConsumed)
    }

    @Test
    fun `enter accepts selected suggestion hides popup and notifies host`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val items = suggestions(2)
        controller.show(items, anchorColumn = 0, anchorRow = 0, selectedIndex = 1)
        val enter = keyPressed(KeyEvent.VK_ENTER)

        assertTrue(controller.handleKeyPressed(enter))

        assertFalse(controller.state().visible)
        assertEquals(listOf(1), host.acceptedIndexes)
        assertEquals(listOf(items[1]), host.acceptedSuggestions)
        assertEquals(1, host.focusRequests)
        assertTrue(enter.isConsumed)
    }

    @Test
    fun `escape hides popup without accepting`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(suggestions(2), anchorColumn = 0, anchorRow = 0, selectedIndex = 0)
        val escape = keyPressed(KeyEvent.VK_ESCAPE)

        assertTrue(controller.handleKeyPressed(escape))

        assertFalse(controller.state().visible)
        assertTrue(host.acceptedSuggestions.isEmpty())
        assertTrue(escape.isConsumed)
    }

    @Test
    fun `reload settings hides visible popup when setting is disabled`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(suggestions(2), anchorColumn = 0, anchorRow = 0, selectedIndex = 0)

        host.settings = SwingSettings(shellSuggestionsEnabled = false)
        controller.reloadSettings()

        assertFalse(controller.state().visible)
    }

    private fun suggestions(count: Int): List<SwingShellSuggestion> =
        List(count) { index ->
            SwingShellSuggestion(
                replacementText = "command-$index",
                displayText = "command-$index",
                detail = "detail-$index",
                source = "test",
            )
        }

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
    ) : SwingShellSuggestionHost {
        val acceptedSuggestions = ArrayList<SwingShellSuggestion>()
        val acceptedIndexes = ArrayList<Int>()
        var focusRequests = 0
        var revalidations = 0
        var repaints = 0

        override val suggestionHandler: SwingShellSuggestionHandler =
            SwingShellSuggestionHandler { suggestion, index ->
                acceptedSuggestions += suggestion
                acceptedIndexes += index
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
