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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleSelection
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SwingCompletionPopupViewTest {
    @Test
    fun `view exposes list semantics and selected item to accessibility`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(3) { index -> suggestion("candidate-$index", detail = "detail-$index") }
            view.update(snapshot(suggestions, selectedIndex = 1, total = 12, start = 4))

            val context = view.accessibleContext
            assertEquals(AccessibleRole.LIST, context.accessibleRole)
            assertEquals("Command completions", context.accessibleName)
            assertEquals(3, context.accessibleChildrenCount)
            assertTrue(context.accessibleDescription.contains("12 suggestions"))
            val selection = context.accessibleSelection as AccessibleSelection
            assertEquals(1, selection.accessibleSelectionCount)
            assertEquals("candidate-1", selection.getAccessibleSelection(0).accessibleContext.accessibleName)
            assertEquals(AccessibleRole.LIST_ITEM, context.getAccessibleChild(1).accessibleContext.accessibleRole)
        }

    @Test
    fun `clipped popup paints selected tail row and maps pointer to snapshot index`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(8) { index -> suggestion("candidate-$index") }
            view.update(snapshot(suggestions, selectedIndex = 7, total = 20, start = 8))
            val fullHeight = view.preferredSize.height
            val rowHeight = (fullHeight - SwingCompletionPopupLayout.SURFACE_PADDING * 2) / suggestions.size
            view.setSize(440, SwingCompletionPopupLayout.SURFACE_PADDING * 2 + rowHeight * 2)

            val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
            view.paint(image.createGraphics())
            val event =
                MouseEvent(
                    view,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    0,
                    40,
                    SwingCompletionPopupLayout.SURFACE_PADDING + 2,
                    0,
                    false,
                )
            view.dispatchEvent(event)

            assertEquals(6, listener.hoveredIndex)
            assertTrue(imageContainsNonTransparentPixel(image))
        }

    @Test
    fun `mouse click and wheel remain controller-owned interactions`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            view.update(snapshot(List(2) { suggestion("candidate-$it") }, selectedIndex = 0, total = 2, start = 0))
            view.size = view.preferredSize

            view.dispatchEvent(
                MouseEvent(
                    view,
                    MouseEvent.MOUSE_PRESSED,
                    0L,
                    0,
                    40,
                    SwingCompletionPopupLayout.SURFACE_PADDING + 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
            val wheel =
                MouseWheelEvent(
                    view,
                    MouseEvent.MOUSE_WHEEL,
                    0L,
                    0,
                    40,
                    10,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    2,
                )
            view.dispatchEvent(wheel)

            assertEquals(0, listener.clickedIndex)
            assertEquals(2, listener.scrollDelta)
            assertTrue(wheel.isConsumed)
        }

    @Test
    fun `tooltips retain bounded full row context`() =
        onEdt {
            val view = createView(RecordingListener())
            view.update(
                snapshot(
                    listOf(suggestion("git checkout feature/terminal", detail = "Switch branch", sourceDisplayText = "Git")),
                    selectedIndex = 0,
                    total = 1,
                    start = 0,
                ),
            )
            view.size = view.preferredSize
            val event =
                MouseEvent(
                    view,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    0,
                    40,
                    SwingCompletionPopupLayout.SURFACE_PADDING + 2,
                    0,
                    false,
                )

            val tooltip = view.getToolTipText(event)
            assertNotNull(tooltip)
            assertTrue(tooltip!!.contains("git checkout feature/terminal"))
            assertTrue(tooltip.contains("Switch branch"))
            assertTrue(tooltip.contains("Git"))
        }

    @Test
    fun `selection-only updates reuse measured text layouts`() =
        onEdt {
            val view = createView(RecordingListener())
            val suggestions = List(3) { suggestion("candidate-$it") }
            view.update(snapshot(suggestions, selectedIndex = 0, total = 3, start = 0))
            val preparationsAfterContent = view.layoutPreparationCount

            view.update(snapshot(suggestions, selectedIndex = 1, total = 3, start = 0))

            assertEquals(preparationsAfterContent, view.layoutPreparationCount)
        }

    @Test
    fun `parent terminal font changes recompute adaptive row metrics`() =
        onEdt {
            val view = createView(RecordingListener())
            view.update(snapshot(listOf(suggestion("candidate")), selectedIndex = 0, total = 1, start = 0))
            val initialHeight = view.preferredSize.height

            (view.parent as JPanel).font = Font(Font.MONOSPACED, Font.PLAIN, 32)

            assertTrue(view.preferredSize.height > initialHeight)
        }

    private fun createView(listener: RecordingListener): SwingCompletionPopupView {
        val parent =
            JPanel(null).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
                background = Color(0x10, 0x14, 0x18)
                foreground = Color(0xE5, 0xE7, 0xEB)
            }
        return SwingCompletionPopupView(listener).also {
            it.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            parent.add(it)
        }
    }

    private fun snapshot(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
        total: Int,
        start: Int,
    ): SwingShellSuggestionViewSnapshot =
        SwingShellSuggestionViewSnapshot.create(
            visibleSuggestions = suggestions,
            selectedIndex = selectedIndex,
            viewportStartIndex = start,
            totalSuggestionCount = total,
        )

    private fun suggestion(
        displayText: String,
        detail: String = "",
        sourceDisplayText: String = "Built-in",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = "spec",
            kind = "COMMAND",
            displayText = displayText,
            detail = detail,
            sourceDisplayText = sourceDisplayText,
        )

    private fun imageContainsNonTransparentPixel(image: BufferedImage): Boolean {
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (image.getRGB(x, y) ushr 24 != 0) return true
                x++
            }
            y++
        }
        return false
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    private class RecordingListener : SwingShellSuggestionViewListener {
        var hoveredIndex = -1
        var clickedIndex = -1
        var scrollDelta = 0

        override fun onSuggestionHovered(index: Int) {
            hoveredIndex = index
        }

        override fun onSuggestionClicked(index: Int) {
            clickedIndex = index
        }

        override fun onSuggestionScrollRequested(delta: Int) {
            scrollDelta = delta
        }
    }
}
