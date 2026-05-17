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
package com.gagik.terminal.ui.swing.api

import com.gagik.core.TerminalBuffers
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.render.api.*
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseWheelEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.SwingUtilities

class TerminalSwingTerminalScrollbackTest {
    @Test
    fun `dirty notifications are coalesced into one pending EDT repaint`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(3, 1),
                renderReader = ScrollbackFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = TerminalSwingTerminal()
        val oldManager = RepaintManager.currentManager(component)
        val repaintManager = CountingRepaintManager(component)

        try {
            RepaintManager.setCurrentManager(repaintManager)
            SwingUtilities.invokeAndWait {
                component.bind(session)
            }
            repaintManager.reset()

            val edtBlocked = CountDownLatch(1)
            val releaseEdt = CountDownLatch(1)
            SwingUtilities.invokeLater {
                edtBlocked.countDown()
                assertTrue(releaseEdt.await(1, TimeUnit.SECONDS), "EDT block was not released")
            }
            assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT block did not start")

            repeat(1_000) {
                session.onDirty?.invoke()
            }
            assertEquals(0, repaintManager.count)

            releaseEdt.countDown()
            SwingUtilities.invokeAndWait {
                // Drain pending dirty notification runnable.
            }

            assertEquals(1, repaintManager.count)
        } finally {
            RepaintManager.setCurrentManager(oldManager)
            session.close()
        }
    }

    @Test
    fun `mouse wheel requests scrolled render snapshot through session`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = TerminalSwingTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(30, 20)
            component.bind(session)
        }
        session.requestRender(scrollbackOffset = 0)
        assertTrue(awaitOffset(session, 0), "initial render was not published")

        SwingUtilities.invokeAndWait {
            component.dispatchEvent(
                MouseWheelEvent(
                    component,
                    MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    5,
                    5,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    -1,
                ),
            )
        }

        assertTrue(awaitOffset(session, 3), "scrolled render was not published")
        assertEquals(3, renderReader.lastRequestedOffset)
        session.close()
    }

    @Test
    fun `precise mouse wheel fractions update scrollback viewport`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(3, 1),
                renderReader = ScrollbackFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = TerminalSwingTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(30, 20)
            component.bind(session)
        }
        session.requestRender(scrollbackOffset = 0)
        assertTrue(awaitOffset(session, 0), "initial render was not published")

        SwingUtilities.invokeAndWait {
            component.dispatchEvent(
                MouseWheelEvent(
                    component,
                    MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    5,
                    5,
                    0,
                    0,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    0,
                    -0.25,
                ),
            )
        }

        assertTrue(awaitOffset(session, 1), "fractional scroll did not publish crossed line offset")
        assertTrue((session.publisher.current()?.rows ?: 0) > 1, "fractional scroll did not request overscan rows")
        session.close()
    }

    private class CountingRepaintManager(
        private val target: JComponent,
    ) : RepaintManager() {
        private val repaintCount = AtomicInteger()

        val count: Int
            get() = repaintCount.get()

        fun reset() {
            repaintCount.set(0)
        }

        override fun addDirtyRegion(
            component: JComponent,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ) {
            if (component === target) {
                repaintCount.incrementAndGet()
            }
            super.addDirtyRegion(component, x, y, w, h)
        }
    }

    private fun awaitOffset(
        session: TerminalSession,
        offset: Int,
    ): Boolean {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (session.publisher.current()?.scrollbackOffset == offset) return true
            Thread.sleep(10)
        }
        return false
    }

    private class ScrollbackFrameReader : TerminalRenderFrameReader {
        @Volatile
        var lastRequestedOffset: Int = -1
            private set

        @Volatile
        var lastRequestedRows: Int = -1
            private set

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            lastRequestedRows = 0
            consumer.accept(ScrollbackFrame(scrollbackOffset.coerceIn(0, 5), rows = 1))
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            lastRequestedRows = viewportRows
            consumer.accept(ScrollbackFrame(scrollbackOffset.coerceIn(0, 5), rows = viewportRows.coerceAtLeast(1)))
        }
    }

    private class ScrollbackFrame(
        override val scrollbackOffset: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 3
        override val historySize: Int = 5
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = scrollbackOffset == 0,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = false

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
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
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
}
