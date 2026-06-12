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
import java.util.concurrent.atomic.AtomicReference
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
        val dispatcher = CountingDispatcher()
        val component =
            TerminalSwingTerminal(
                hostServices =
                    TerminalSwingHostServices(
                        uiDispatcher = dispatcher,
                    ),
            )
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
            assertEquals(1, dispatcher.count)

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
    fun `mouse wheel updates component scrollback viewport`() {
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

        val state = component.viewportState()
        assertEquals(3.0, state.scrollbackOffset)
        assertEquals(3, state.renderOffset)
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

        val state = component.viewportState()
        assertEquals(0.75, state.scrollbackOffset)
        assertEquals(1, state.renderOffset)
        assertTrue(state.requestedRows > state.visibleRows, "fractional scroll did not request overscan rows")
        session.close()
    }

    @Test
    fun `host scroll command requests absolute scrollback viewport`() {
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
        component.scrollToScrollbackOffset(4)

        drainEdt()
        val state = component.viewportState()
        assertEquals(4, renderReader.lastRequestedOffset)
        assertEquals(5, state.historySize)
        assertEquals(4.0, state.scrollbackOffset)
        assertEquals(4, state.renderOffset)
        session.close()
    }

    @Test
    fun `host fractional scroll command publishes overscan viewport state`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val listener = RecordingViewportListener()
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
        val component =
            TerminalSwingTerminal(
                hostServices =
                    TerminalSwingHostServices(
                        viewportListener = listener,
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(30, 20)
            component.bind(session)
        }
        component.scrollViewportBy(0.25)

        drainEdt()
        val state = component.viewportState()
        assertEquals(0.25, state.scrollbackOffset)
        assertEquals(1, state.renderOffset)
        assertTrue(state.requestedRows > state.visibleRows)
        assertEquals(0.25, listener.lastScrollbackOffset.get())
        assertEquals(1, listener.lastRenderOffset.get())
        session.close()
    }

    @Test
    fun `components bound to same session keep independent scrollback viewports`() {
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
        val left = TerminalSwingTerminal()
        val right = TerminalSwingTerminal()

        SwingUtilities.invokeAndWait {
            left.setSize(30, 20)
            right.setSize(30, 20)
            left.bind(session)
            right.bind(session)
        }
        drainEdt()

        left.scrollToScrollbackOffset(4)
        drainEdt()

        assertEquals(4.0, left.viewportState().scrollbackOffset)
        assertEquals(0.0, right.viewportState().scrollbackOffset)

        right.scrollToScrollbackOffset(2)
        drainEdt()

        assertEquals(4.0, left.viewportState().scrollbackOffset)
        assertEquals(2.0, right.viewportState().scrollbackOffset)
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

    private class CountingDispatcher : TerminalUiDispatcher {
        private val dispatchCount = AtomicInteger()

        val count: Int
            get() = dispatchCount.get()

        override fun dispatch(runnable: Runnable) {
            dispatchCount.incrementAndGet()
            SwingUtilities.invokeLater(runnable)
        }
    }

    private class RecordingViewportListener : TerminalViewportListener {
        val lastScrollbackOffset = AtomicReference(0.0)
        val lastRenderOffset = AtomicInteger()

        override fun viewportChanged(
            historySize: Int,
            scrollbackOffset: Double,
            renderOffset: Int,
            visibleRows: Int,
            requestedRows: Int,
        ) {
            lastScrollbackOffset.set(scrollbackOffset)
            lastRenderOffset.set(renderOffset)
        }
    }

    private fun drainEdt() {
        if (SwingUtilities.isEventDispatchThread()) return
        SwingUtilities.invokeAndWait {
        }
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
