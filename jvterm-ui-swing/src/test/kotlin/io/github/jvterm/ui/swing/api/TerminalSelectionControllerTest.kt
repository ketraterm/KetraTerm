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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.render.api.*
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Insets
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JButton

class TerminalSelectionControllerTest {
    private class FakeSelectionHost(
        override val renderCache: TerminalRenderCache,
    ) : TerminalSelectionHost {
        override val settings = TerminalSwingSettings(padding = Insets(0, 0, 0, 0))
        override val metrics =
            TerminalSwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 15,
                underlineY = 16,
                strikethroughY = 10,
                overlineY = 0,
                cursorStrokeWidth = 2,
            )
        override val contentYOffset = 0.0
        override val componentWidth = 100
        override val componentHeight = 200

        var cellAtCallCount = 0
        var cellAtX = -1
        var cellAtY = -1
        var scrollDelta = 0.0
        var repaints = 0
        var focusRequests = 0

        override fun cellAt(
            x: Int,
            y: Int,
        ): Long {
            cellAtCallCount++
            cellAtX = x
            cellAtY = y
            val col = x / 10
            val row = y / 20
            return (col.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }

        override fun scrollViewportBy(
            delta: Double,
            historySize: Int,
        ): Boolean {
            scrollDelta = delta
            return true
        }

        override fun repaint() {
            repaints++
        }

        override fun requestFocusInWindow(): Boolean {
            focusRequests++
            return true
        }
    }

    private class FakeFrameReader(
        private val frame: TerminalRenderFrame,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(frame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(frame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(frame)
        }
    }

    private class FakeFrame(
        val content: String,
    ) : TerminalRenderFrame {
        override val columns = 10
        override val rows = 1
        override val historySize = 0
        override val scrollbackOffset = 0
        override val frameGeneration = 1L
        override val structureGeneration = 1L
        override val activeBuffer = TerminalRenderBufferKind.PRIMARY
        override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1L)
        override val discardedCount = 0L

        override fun lineGeneration(row: Int) = 1L

        override fun lineWrapped(row: Int) = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            for (col in 0 until minOf(columns, content.length)) {
                val idx = codeOffset + col
                codeWords[idx] = content[col].code
                attrWords[idx] = TerminalRenderAttrs.DEFAULT
                flags[idx] = TerminalRenderCellFlags.CODEPOINT
            }
        }
    }

    @Test
    fun `clearSelection resets selection rows`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)

        controller.clearSelection()

        assertNull(controller.selectionAnchorAbsoluteRow)
        assertNull(controller.selectionCaretAbsoluteRow)
    }

    @Test
    fun `mousePressed on left button requests focus and sets anchor`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)

        val button = JButton()
        val event =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                25,
                45,
                1,
                false,
                MouseEvent.BUTTON1,
            )

        controller.handleSelectionMousePressed(event)

        assertEquals(1, host.focusRequests)
        assertTrue(controller.selectingWithMouse)
        assertEquals(2, controller.selectionAnchorColumn)
        assertEquals(2L, controller.selectionAnchorAbsoluteRow)
    }

    @Test
    fun `mouseDragged updates caret and triggers repaint`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)

        val button = JButton()
        val pressEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMousePressed(pressEvent)

        val dragEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                35,
                65,
                0,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMouseDragged(dragEvent)

        assertTrue(controller.selectingWithMouse)
        assertEquals(1, controller.selectionAnchorColumn)
        assertEquals(4, controller.selectionCaretColumn)
    }

    @Test
    fun `double click on mousePressed selects word`() {
        val cache = TerminalRenderCache(10, 1)
        val frame = FakeFrame("hello")
        cache.updateFrom(FakeFrameReader(frame))

        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)
        val button = JButton()

        val doubleClickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                15,
                5,
                2,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMousePressed(doubleClickEvent)

        assertEquals(0, controller.selectionAnchorColumn)
        assertEquals(5, controller.selectionCaretColumn)
        assertEquals(0L, controller.selectionAnchorAbsoluteRow)
    }

    @Test
    fun `triple click selects line`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)
        val button = JButton()

        val tripleClickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                15,
                25,
                3,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMousePressed(tripleClickEvent)

        assertEquals(0, controller.selectionAnchorColumn)
        assertEquals(10, controller.selectionCaretColumn)
        assertEquals(1L, controller.selectionAnchorAbsoluteRow)
        assertEquals(1L, controller.selectionCaretAbsoluteRow)
    }
}
