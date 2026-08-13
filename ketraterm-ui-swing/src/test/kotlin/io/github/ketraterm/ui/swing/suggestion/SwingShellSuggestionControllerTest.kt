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
    fun `host view factory receives the controller snapshot and selection`() {
        lateinit var view: RecordingSuggestionView
        val controller =
            SwingShellSuggestionController(
                host = RecordingSuggestionHost(),
                viewFactory =
                    SwingShellSuggestionViewFactory { listener ->
                        RecordingSuggestionView(listener).also { view = it }
                    },
            )
        val items = suggestions(3)

        controller.show(request(), items, selectedIndex = -1)
        view.listener.onSuggestionHovered(1)

        assertEquals(items, view.suggestions)
        assertEquals(1, view.selectedIndex)
        assertEquals(1, controller.state().selectedIndex)
    }

    @Test
    fun `show keeps popup passive when no valid initial selection is supplied`() {
        val controller = SwingShellSuggestionController(RecordingSuggestionHost())

        controller.show(request(), suggestions(3), selectedIndex = 99)

        assertEquals(-1, controller.state().selectedIndex)
        assertNull(controller.state().selectedSuggestion)
    }

    @Test
    fun `explicit display is independent of automatic suggestion setting`() {
        val host = RecordingSuggestionHost(settings = SwingSettings(shellSuggestionsEnabled = false))
        val controller = SwingShellSuggestionController(host)

        val shown = controller.show(request(), suggestions(2), selectedIndex = 0)

        assertTrue(shown)
        assertTrue(controller.state().visible)
        assertTrue(controller.popup.isVisible)
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
    fun `up from a passive popup selects the last retained suggestion`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)

        controller.show(request(), suggestions(20), selectedIndex = -1)
        controller.handleKeyPressed(keyPressed(KeyEvent.VK_UP))

        assertEquals(20, controller.state().count)
        assertEquals(19, controller.state().selectedIndex)
    }

    @Test
    fun `controller retains full ranking while view receives eight rows`() {
        lateinit var view: RecordingSuggestionView
        val controller =
            SwingShellSuggestionController(
                host = RecordingSuggestionHost(),
                viewFactory = SwingShellSuggestionViewFactory { listener -> RecordingSuggestionView(listener).also { view = it } },
            )

        controller.show(request(), suggestions(80), selectedIndex = -1)

        assertEquals(80, controller.state().count)
        assertEquals(8, view.suggestions.size)
        assertEquals((0..7).map { "command-$it" }, view.suggestions.map { it.replacementText })
    }

    @Test
    fun `navigation scrolls viewport and mouse acceptance resolves global rank`() {
        lateinit var view: RecordingSuggestionView
        val host = RecordingSuggestionHost()
        val controller =
            SwingShellSuggestionController(
                host = host,
                viewFactory = SwingShellSuggestionViewFactory { listener -> RecordingSuggestionView(listener).also { view = it } },
            )
        val items = suggestions(20)
        controller.show(request(), items, selectedIndex = -1)

        repeat(10) { controller.handleKeyPressed(keyPressed(KeyEvent.VK_DOWN)) }

        assertEquals(9, controller.state().selectedIndex)
        assertEquals(7, view.selectedIndex)
        assertEquals((2..9).map { "command-$it" }, view.suggestions.map { it.replacementText })
        view.listener.onSuggestionClicked(3)
        assertEquals(listOf(5), host.acceptedIndexes)
        assertEquals(listOf(items[5]), host.acceptedSuggestions)
    }

    @Test
    fun `progressive reranking preserves selected outcome only for the same request`() {
        val controller = SwingShellSuggestionController(RecordingSuggestionHost())
        val request = request(commandText = "git s")
        val first = suggestions(3, endOffset = request.commandText.length)
        controller.show(request, first, selectedIndex = 1)

        controller.show(request, listOf(first[2], first[1], first[0]), selectedIndex = -1)
        assertEquals(1, controller.state().selectedIndex)
        assertSame(first[1], controller.state().selectedSuggestion)

        controller.show(request(commandText = "git st"), first, selectedIndex = -1)
        assertEquals(-1, controller.state().selectedIndex)
    }

    @Test
    fun `enter passes through to the shell when no suggestion is selected`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val request = request(commandText = "git sw", cursorOffset = 6)
        val items = suggestions(2, endOffset = request.commandText.length)
        controller.show(request, items, selectedIndex = -1)
        val enter = keyPressed(KeyEvent.VK_ENTER)

        assertFalse(controller.handleKeyPressed(enter))

        assertTrue(controller.state().visible)
        assertTrue(host.acceptedSuggestions.isEmpty())
        assertTrue(host.feedbackKinds.isEmpty())
        assertFalse(enter.isConsumed)
    }

    @Test
    fun `enter accepts an already-selected suggestion`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        val request = request(commandText = "git sw", cursorOffset = 6)
        val items = suggestions(2, endOffset = request.commandText.length)
        controller.show(request, items, selectedIndex = 1)
        val enter = keyPressed(KeyEvent.VK_ENTER)

        assertTrue(controller.handleKeyPressed(enter))

        assertFalse(controller.state().visible)
        assertEquals(listOf(1), host.acceptedIndexes)
        assertEquals(listOf(items[1]), host.acceptedSuggestions)
        assertEquals(listOf(request), host.acceptedRequests)
        assertEquals(listOf(SwingShellSuggestionFeedbackKind.ACCEPTED), host.feedbackKinds)
        assertEquals(1, host.focusRequests)
        assertTrue(enter.isConsumed)
    }

    @Test
    fun `disabled enter acceptance passes through with a selected suggestion`() {
        val host =
            RecordingSuggestionHost(
                settings = SwingSettings(acceptSelectedSuggestionWithEnter = false),
            )
        val controller = SwingShellSuggestionController(host)
        val items = suggestions(2)
        controller.show(request(), items, selectedIndex = 1)
        val enter = keyPressed(KeyEvent.VK_ENTER)

        assertFalse(controller.handleKeyPressed(enter))

        assertTrue(controller.state().visible)
        assertEquals(1, controller.state().selectedIndex)
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
        assertTrue(host.feedbackKinds.isEmpty())
    }

    @Test
    fun `ordinary popup closure with a selected suggestion records no dismissal`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(2), selectedIndex = 0)

        controller.hide()

        assertFalse(controller.state().visible)
        assertTrue(host.feedbackKinds.isEmpty())
    }

    @Test
    fun `popup replacement records no negative feedback`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(2), selectedIndex = 0)

        controller.show(request(commandText = "g"), suggestions(1), selectedIndex = -1)

        assertTrue(controller.state().visible)
        assertTrue(host.feedbackKinds.isEmpty())
    }

    @Test
    fun `focus loss hides selected suggestion without negative feedback`() {
        assertPassiveHideIsNeutral()
    }

    @Test
    fun `timeout hides selected suggestion without negative feedback`() {
        assertPassiveHideIsNeutral()
    }

    @Test
    fun `continued typing replaces selected suggestion without negative feedback`() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(commandText = "git s"), suggestions(2, endOffset = 5), selectedIndex = 0)

        controller.show(request(commandText = "git sw"), suggestions(1, endOffset = 6), selectedIndex = -1)

        assertTrue(controller.state().visible)
        assertEquals(-1, controller.state().selectedIndex)
        assertTrue(host.feedbackKinds.isEmpty())
    }

    private fun assertPassiveHideIsNeutral() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(2), selectedIndex = 0)

        controller.hide()

        assertFalse(controller.state().visible)
        assertTrue(host.feedbackKinds.isEmpty())
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
        var invalidations = 0

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

        override fun invalidateSuggestions() {
            invalidations++
        }
    }

    private class RecordingSuggestionView(
        val listener: SwingShellSuggestionViewListener,
    ) : SwingShellSuggestionView {
        override val component = JPanel()
        var suggestions: List<SwingShellSuggestion> = emptyList()
            private set
        var selectedIndex: Int = -1
            private set

        override fun update(
            suggestions: List<SwingShellSuggestion>,
            selectedIndex: Int,
        ) {
            this.suggestions = suggestions
            this.selectedIndex = selectedIndex
        }
    }
}
