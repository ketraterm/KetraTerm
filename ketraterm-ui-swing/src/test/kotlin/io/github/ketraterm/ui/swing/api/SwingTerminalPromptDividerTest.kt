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
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingPromptDecoration
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalPromptDividerTest {
    @Test
    fun `divider painting and cursor bounds share reserved band geometry`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                val h = f.cellHeight
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(h, bounds.y)
                assertEquals(3, f.component.visibleGridSize().height)
                f.session.readRenderFrame {
                    assertEquals(3, it.rows)
                    assertEquals(0, it.historySize)
                }
                val image = BufferedImage(f.component.width, f.component.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    f.component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                val ruleTop = (h - 2) / 2
                assertNotEquals(BLACK, image.getRGB(1, ruleTop))
                assertNotEquals(BLACK, image.getRGB(1, ruleTop + 1))
                assertEquals(BLACK, image.getRGB(1, ruleTop - 1))
                assertEquals(BLACK, image.getRGB(1, ruleTop + 2))
                assertEquals(BLACK, image.getRGB(1, 0))
                assertEquals(BLACK, image.getRGB(1, h - 1))
                assertEquals(BLACK, image.getRGB(1, h * 2 - 1))
            }
        }

    @Test
    fun `scrollbar reaches rows displaced from live grid and copying excludes bands`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.feed("\r\n")
                f.prompt(">two")
                f.feed("\r\n")
                f.prompt(">three")
                val h = f.cellHeight
                assertEquals(3, f.component.viewportState().historySize)
                assertEquals(3 * h, f.component.viewportState().visualScrollRangePixels)
                f.session.readRenderFrame {
                    assertEquals(0, it.historySize)
                    assertEquals(3, it.rows)
                }
                val bounds = Rectangle()
                assertFalse(f.component.copyCellBounds(0, 0, bounds))
                assertTrue(f.component.copyCellBounds(0, 2, bounds))
                assertEquals(2 * h, bounds.y)
                f.component.scrollFromScrollbar(3, true)
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(h, bounds.y)
                assertTrue(f.component.selectAll())
                assertTrue(f.component.copySelectionToClipboard())
                assertEquals(">one\n>two\n>three", f.clipboard.trimEnd())
                f.component.scrollFromScrollbar(0, true)
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 2, bounds))
                assertEquals(2 * h, bounds.y)
            }
        }

    @Test
    fun `alternate screen suppresses divider bands without resizing the terminal`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                val resizes = f.connector.resizes.toList()
                f.feed("\u001B[?1049hALT")
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.y)
                assertEquals(0, f.component.viewportState().historySize)
                f.feed("\u001B[?1049l")
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.cellHeight, bounds.y)
                assertEquals(resizes, f.connector.resizes)
            }
        }

    @Test
    fun `none removes decoration space while retaining shell commands`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.session.readRenderFrame { f.state.recordCommandStart(it.lineId(0), includeLine = true) }
                f.flush()
                val record = f.state.latestCommandRecordId()
                assertTrue(record != 0)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.NONE }
                f.component.reloadSettings()
                f.flush()
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.y)
                assertEquals(record, f.component.commandRecordAt(1, 1))
                assertEquals(3, f.component.visibleGridSize().height)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.DIVIDER }
                f.component.reloadSettings()
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.cellHeight, bounds.y)
            }
        }

    @Test
    fun `divider gestures never emit application mouse coordinates`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.feed("\u001B[?1000h\u001B[?1006h")
                f.connector.writes.clear()
                f.mouse(MouseEvent.MOUSE_PRESSED, 1, f.cellHeight / 2)
                f.mouse(MouseEvent.MOUSE_DRAGGED, 1, f.cellHeight + 1)
                f.mouse(MouseEvent.MOUSE_RELEASED, 1, f.cellHeight + 1)
                f.flush()
                assertEquals(emptyList(), f.connector.writes)
                f.mouse(MouseEvent.MOUSE_PRESSED, 1, f.cellHeight + 1)
                f.mouse(MouseEvent.MOUSE_RELEASED, 1, f.cellHeight / 2)
                f.flush()
                assertEquals("\u001B[<0;1;1M\u001B[<0;1;1m", f.connector.writes.joinToString(""))
            }
        }

    @Test
    fun `closed divider viewport preserves source grid through height changes`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.feed("\r\n")
                f.prompt(">two")
                f.session.onClosed(0)
                f.flush()
                val resizes = f.connector.resizes.toList()
                f.component.size = f.component.preferredGridSize(8, 1)
                f.component.componentListeners.forEach {
                    it.componentResized(java.awt.event.ComponentEvent(f.component, java.awt.event.ComponentEvent.COMPONENT_RESIZED))
                }
                f.flush()
                assertEquals(4, f.component.viewportState().historySize)
                f.component.scrollFromScrollbar(4, true)
                f.flush()
                // The first slot is the divider; scrolling one slot forward reveals its prompt.
                f.component.scrollFromScrollbar(3, true)
                f.flush()
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.y)
                assertEquals(resizes, f.connector.resizes)
                f.session.readRenderFrame { assertEquals(3, it.rows) }
            }
        }

    private fun edt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
    }

    @Test
    fun `switching decoration modes preserves the viewed history line`() =
        edt {
            Fixture().use { f ->
                repeat(8) { index ->
                    f.prompt(">$index")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                val nativeOffset = f.component.viewportState().renderOffset
                assertTrue(nativeOffset > 0)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.NONE }
                f.component.reloadSettings()
                f.flush()
                assertEquals(nativeOffset, f.component.viewportState().renderOffset)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.DIVIDER }
                f.component.reloadSettings()
                f.flush()
                assertEquals(nativeOffset, f.component.viewportState().renderOffset)
            }
        }

    @Test
    fun `resizing a scrolled divider viewport preserves the viewed line identity`() =
        edt {
            Fixture().use { f ->
                repeat(8) { index ->
                    f.prompt(">$index")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                val offset = f.component.viewportState().renderOffset
                var lineId = 0L
                f.session.readRenderFrame(offset, 3) { lineId = it.lineId(0) }
                f.component.size = f.component.preferredGridSize(8, 4)
                f.component.componentListeners.forEach {
                    it.componentResized(java.awt.event.ComponentEvent(f.component, java.awt.event.ComponentEvent.COMPONENT_RESIZED))
                }
                f.flush()
                f.session.readRenderFrame(f.component.viewportState().renderOffset, 4) {
                    assertEquals(lineId, it.lineId(0))
                }
            }
        }

    private class Fixture : AutoCloseable {
        private val worker = StandardTestDispatcher()
        private val dispatches = LinkedBlockingQueue<Runnable>()
        val state = TerminalShellIntegrationState()
        val connector = Connector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(8, 3, 100),
                connector,
                workerDispatcher = worker,
                ioDispatcher = worker,
                shellIntegration = TerminalShellIntegrationFactory.host(state),
            )
        var settings =
            SwingSettings.create {
                it.padding = SwingPadding(0, 0, 0, 0)
                it.promptDecoration = SwingPromptDecoration.DIVIDER
                it.cursorBlinkMillis = 0
                it.useSystemFallbackFonts = false
                it.palette = TerminalColorPalette(defaultForeground = WHITE, defaultBackground = BLACK)
            }
        var clipboard = ""
        val component =
            SwingTerminal(
                { settings },
                SwingHostServices.create {
                    it.uiDispatcher = { action -> dispatches += action }
                    it.clipboardHandler =
                        object : TerminalClipboardHandler {
                            override fun copyText(text: String) {
                                clipboard = text
                            }

                            override fun readText(): String? = null
                        }
                },
                searchDispatcher = worker,
                hyperlinkDispatcher = worker,
            )
        val cellHeight get() = (component.preferredGridSize(8, 3).height / 3)

        init {
            session.start(8, 3)
            component.size = component.preferredGridSize(8, 3)
            component.bind(session)
            flush()
        }

        fun prompt(text: String) {
            feed(text)
            session.readRenderFrame { frame -> state.recordPromptStart(frame.lineId(frame.cursor.row)) }
            flush()
        }

        fun feed(text: String) {
            val bytes = text.toByteArray()
            session.onBytes(bytes, 0, bytes.size)
            session.requestRender(component.viewportState().renderOffset, component.viewportState().requestedRows)
            flush()
        }

        fun mouse(
            id: Int,
            x: Int,
            y: Int,
        ) {
            val event = MouseEvent(component, id, 0, 0, x, y, 1, false, MouseEvent.BUTTON1)
            when (id) {
                MouseEvent.MOUSE_PRESSED -> component.mouseListeners.forEach { it.mousePressed(event) }
                MouseEvent.MOUSE_RELEASED -> component.mouseListeners.forEach { it.mouseReleased(event) }
                MouseEvent.MOUSE_DRAGGED -> component.mouseMotionListeners.forEach { it.mouseDragged(event) }
            }
        }

        fun flush() {
            do {
                worker.scheduler.runCurrent()
                var dispatched = false
                while (true) {
                    val action = dispatches.poll() ?: break
                    dispatched = true
                    action.run()
                }
            } while (dispatched)
        }

        override fun close() {
            component.dispose()
            session.close()
            flush()
        }
    }

    private class Connector : TerminalConnector {
        val resizes = mutableListOf<Pair<Int, Int>>()
        val writes = mutableListOf<String>()

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes += String(bytes, offset, length)
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            resizes += columns to rows
        }

        override fun close() = Unit
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -1
    }
}
