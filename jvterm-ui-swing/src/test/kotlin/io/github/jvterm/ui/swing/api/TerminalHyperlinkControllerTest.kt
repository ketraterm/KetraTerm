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

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.input.api.TerminalInputEncoder
import io.github.jvterm.input.event.TerminalFocusEvent
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalPasteEvent
import io.github.jvterm.parser.api.TerminalOutputParser
import io.github.jvterm.render.api.TerminalRenderFrameConsumer
import io.github.jvterm.render.api.TerminalRenderFrameReader
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.render.cache.TerminalRenderPublisher
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.transport.TerminalConnectorListener
import io.github.jvterm.ui.swing.settings.TerminalHyperlinkHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton

class TerminalHyperlinkControllerTest {
    private class FakeHyperlinkHost(
        override val renderCache: TerminalRenderCache,
        override val session: TerminalSession?,
        override val hostServices: TerminalSwingHostServices,
    ) : TerminalHyperlinkHost {
        override var cursor: Cursor = Cursor.getDefaultCursor()
        var repaints = 0

        override fun cellAt(
            x: Int,
            y: Int,
        ): Long {
            val col = x / 10
            val row = y / 20
            return (col.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }

        override fun repaint() {
            repaints++
        }
    }

    private class FakeFrameReader : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = Unit

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) = Unit

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) = Unit
    }

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

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
    }

    private object NoOpParser : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    @Test
    fun `hover over cell without hyperlink keeps default cursor`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeHyperlinkHost(cache, null, TerminalSwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(0, controller.hoveredHyperlinkId)
        assertEquals(Cursor.getDefaultCursor(), host.cursor)
    }

    @Test
    fun `hover over cell with resolved hyperlink changes cursor to hand`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
            }
        val terminal = TerminalBuffers.create(width = 10, height = 10, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, TerminalSwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR), host.cursor)
    }

    @Test
    fun `mouseExited clears hover state and resets cursor`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
            }
        val terminal = TerminalBuffers.create(width = 10, height = 10, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, TerminalSwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val moveEvent = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(moveEvent)
        assertEquals(5, controller.hoveredHyperlinkId)

        controller.handleMouseExited()
        assertEquals(0, controller.hoveredHyperlinkId)
        assertEquals(Cursor.getDefaultCursor(), host.cursor)
    }

    @Test
    fun `ctrl click on resolved hyperlink invokes hyperlink handler`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
            }
        val terminal = TerminalBuffers.create(width = 10, height = 10, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val openedUri = AtomicReference<String?>()
        val handler =
            TerminalHyperlinkHandler { uri ->
                openedUri.set(uri)
                true
            }
        val host = FakeHyperlinkHost(cache, session, TerminalSwingHostServices(hyperlinkHandler = handler))
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val clickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK or InputEvent.CTRL_DOWN_MASK,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        val consumed = controller.handleMousePressed(clickEvent)

        assertTrue(consumed)
        assertEquals("https://example.com", openedUri.get())
    }
}
