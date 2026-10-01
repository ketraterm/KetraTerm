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
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.input.hyperlinkNavigationModifierMask
import io.github.ketraterm.ui.swing.settings.TerminalHyperlinkHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Cursor
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.SwingUtilities

class TerminalHyperlinkControllerTest {
    @Test
    fun `stationary hover follows replaced spans and retains modifiers while detection is pending`() {
        val cache = TerminalRenderCache(8, 2)
        val host = FakeHyperlinkHost(cache, null, SwingHostServices(), mapOf(-1 to { true }, -2 to { true }))
        val controller = TerminalHyperlinkController(host)
        controller.handleMouseMoved(MouseEvent(JButton(), MouseEvent.MOUSE_MOVED, 0L, hyperlinkNavigationModifierMask, 15, 5, 0, false))
        assertEquals(0, controller.hoveredHyperlinkId)

        cache.hyperlinkIds.fill(-1, 1, 11)
        cache.lineWrapped[0] = true
        controller.refreshHyperlinkHover()
        assertEquals(-1, controller.hoveredHyperlinkId)
        assertEquals(0, controller.segmentRow(0))
        assertEquals(1, controller.segmentStartColumn(0))
        assertEquals(1, controller.segmentRow(controller.hoveredSegmentCount - 1))
        assertEquals(3, controller.segmentEndColumn(controller.hoveredSegmentCount - 1))
        assertTrue(controller.hyperlinkActivationHover)
        host.repaintSpans.clear()
        controller.refreshHyperlinkHover()
        assertTrue(host.repaintSpans.isEmpty(), "Unchanged hover must not schedule extra painting")

        cache.hyperlinkIds.fill(0)
        cache.hyperlinkIds.fill(-2, 0, 4)
        cache.lineWrapped[0] = false
        controller.refreshHyperlinkHover()
        assertEquals(-2, controller.hoveredHyperlinkId)
        assertEquals(0, controller.segmentStartColumn(0))
        assertEquals(0, controller.segmentRow(controller.hoveredSegmentCount - 1))
        assertEquals(4, controller.segmentEndColumn(controller.hoveredSegmentCount - 1))

        cache.hyperlinkIds.fill(0)
        cache.hyperlinkIds[cache.rowOffset(1)] = -2
        controller.refreshHyperlinkHover()
        assertEquals(0, controller.hoveredHyperlinkId, "A link moved away from the pointer must not stay hovered")
        assertEquals(Cursor.DEFAULT_CURSOR, host.cursor.type)
        controller.updateHyperlinkActivationHover(false)
        cache.hyperlinkIds[1] = -1
        controller.refreshHyperlinkHover()
        assertEquals(-1, controller.hoveredHyperlinkId)
        assertEquals(false, controller.hyperlinkActivationHover)

        controller.handleMouseExited()
        controller.refreshHyperlinkHover()
        assertEquals(0, controller.hoveredHyperlinkId, "Publication after mouse exit must not revive hover")
        controller.handleMouseMoved(MouseEvent(JButton(), MouseEvent.MOUSE_MOVED, 0L, 0, 15, 5, 0, false))
        controller.clearHyperlinkHover()
        controller.refreshHyperlinkHover()
        assertEquals(0, controller.hoveredHyperlinkId, "Lifecycle reset must forget the old pointer")
    }

    @Test
    fun `OSC8 hover survives progress rewrites and clears when its cells are erased`() {
        Osc8PipelineFixture(20, 2).use { fixture ->
            fixture.accept("\u001b]8;;https://example.com\u001b\\docs\u001b]8;;\u001b\\\r\n0%")
            fixture.refresh()
            fixture.hover(1, 0, activationHover = true)
            val id = fixture.controller.hoveredHyperlinkId
            assertTrue(id > 0)
            fixture.host.repaintSpans.clear()
            repeat(3) { progress ->
                fixture.accept("\r\u001b[2K$progress%")
                fixture.refresh()
                fixture.controller.refreshHyperlinkHover()
                fixture.assertHoverSpan(id, 0, 0, 0, 4)
                assertTrue(fixture.controller.hyperlinkActivationHover)
            }
            assertTrue(fixture.host.repaintSpans.isEmpty())
            fixture.accept("\u001b[1;1H\u001b[2K")
            fixture.refresh()
            fixture.controller.refreshHyperlinkHover()
            fixture.assertNoHover()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 7, Int.MAX_VALUE])
    fun `captured agy URL fragments hover together independently of the authentication caption across input chunks`(chunkSize: Int) {
        val bytes = agyCapture()
        val uri = agyTarget(bytes)
        assertEquals(704, uri.length)
        val opened = ArrayList<String>()
        SwingUtilities.invokeAndWait {
            Osc8PipelineFixture(
                width = 176,
                height = 32,
                hyperlinkHandler = TerminalHyperlinkHandler { opened.add(it) },
            ).use { fixture ->
                fixture.accept(bytes, chunkSize)
                fixture.refresh()
                val segments = capturedLinkSegments(fixture.cache)
                assertEquals(
                    listOf(
                        CapturedLinkSegment(9, 1, 175),
                        CapturedLinkSegment(10, 1, 175),
                        CapturedLinkSegment(11, 1, 175),
                        CapturedLinkSegment(12, 1, 175),
                        CapturedLinkSegment(13, 1, 175),
                        CapturedLinkSegment(17, 1, 29),
                    ),
                    segments,
                )
                val first = segments.first()
                val id = fixture.cache.hyperlinkIds[fixture.cache.rowOffset(first.row) + first.startColumn]
                assertTrue(id > 0)
                assertEquals(uri, fixture.session.hyperlinkUri(id))
                val visibleTexts =
                    segments.map { segment ->
                        buildString {
                            for (column in segment.startColumn until segment.endColumn) {
                                val index = fixture.cache.rowOffset(segment.row) + column
                                assertEquals(id, fixture.cache.hyperlinkIds[index], "Shared OSC8 identity at $segment column $column")
                                appendCodePoint(fixture.cache.codeWords[index])
                            }
                        }.trimEnd()
                    }
                assertEquals(listOf(174, 174, 174, 174, 8), visibleTexts.take(5).map(String::length))
                assertEquals(uri, visibleTexts.take(5).joinToString(""))
                assertEquals("→ Click here to authenticate", visibleTexts.last())
                for (segment in segments) {
                    assertFalse(fixture.cache.lineWrapped[segment.row], "The captured TUI uses explicit row placement at $segment")
                    val occurrence = if (segment == segments.last()) listOf(segment) else segments.take(5)
                    fixture.hover(segment.startColumn, segment.row, activationHover = true)
                    assertEquals(occurrence.size, fixture.controller.hoveredSegmentCount)
                    for ((index, expected) in occurrence.withIndex()) {
                        assertEquals(expected.row, fixture.controller.segmentRow(index))
                        assertEquals(expected.startColumn, fixture.controller.segmentStartColumn(index))
                        assertEquals(expected.endColumn, fixture.controller.segmentEndColumn(index))
                    }
                    assertEquals(Cursor.HAND_CURSOR, fixture.host.cursor.type)
                    assertTrue(fixture.click(segment.startColumn, segment.row))
                }
                assertEquals(List(6) { uri }, opened)
                val urlSpans = segments.take(5).map { RepaintSpan(it.row, it.startColumn, it.row, it.endColumn) }
                assertEquals(urlSpans + urlSpans + RepaintSpan(17, 1, 17, 29), fixture.host.repaintSpans)
            }
        }
    }

    @Test
    fun `wide-character wrap padding is excluded from the semantic hover group`() {
        Osc8PipelineFixture(3, 3).use { f ->
            f.accept(osc8("https://example.com/wide", "ab中Z"))
            f.refresh()
            f.hover(0, 1)
            assertEquals(listOf(RepaintSpan(0, 0, 0, 2), RepaintSpan(1, 0, 1, 3)), f.host.repaintSpans)
            f.hover(2, 0)
            f.assertNoHover()
        }
    }

    @Test
    fun `wide-character padding connects nonoverlapping fragments only through a soft wrap`() {
        Osc8PipelineFixture(8, 3).use { f ->
            f.accept("......" + osc8("https://example.com/wide", "a中"))
            f.refresh()
            f.hover(0, 1)
            assertEquals(2, f.controller.hoveredSegmentCount)
            assertEquals(listOf(RepaintSpan(0, 6, 0, 7), RepaintSpan(1, 0, 1, 2)), f.host.repaintSpans)
            f.hover(7, 0)
            f.assertNoHover()
        }
    }

    @Test
    fun `release over a separate occurrence with the same OSC8 identity does not activate`() {
        val opened = ArrayList<String>()
        Osc8PipelineFixture(10, 2, hyperlinkHandler = TerminalHyperlinkHandler { opened.add(it) }).use { f ->
            val uri = "https://example.com/shared"
            f.accept(osc8(uri, "AA", "shared") + "  " + osc8(uri, "BB", "shared"))
            f.refresh()
            val component = JButton()
            val press =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_PRESSED,
                    0L,
                    hyperlinkNavigationModifierMask,
                    CELL_WIDTH / 2,
                    CELL_HEIGHT / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            val release =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_RELEASED,
                    0L,
                    hyperlinkNavigationModifierMask,
                    4 * CELL_WIDTH + CELL_WIDTH / 2,
                    CELL_HEIGHT / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            assertTrue(f.controller.handleMousePressed(press))
            assertFalse(f.controller.handleMouseReleased(release))
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun `hard breaks do not connect nonoverlapping same-id occurrences at opposite row edges`() {
        Osc8PipelineFixture(8, 3).use { f ->
            val uri = "https://example.com/shared"
            f.accept("......" + osc8(uri, "AA", "shared") + "\r\n" + osc8(uri, "BB", "shared"))
            f.refresh()
            f.hover(0, 1)
            assertEquals(1, f.controller.hoveredSegmentCount)
            assertEquals(listOf(RepaintSpan(1, 0, 1, 2)), f.host.repaintSpans)
        }
    }

    @Test
    fun `explicit destination identity does not join occurrences across intervening rows`() {
        Osc8PipelineFixture(12, 4).use { f ->
            val uri = "https://example.com/group"
            f.accept(
                osc8(uri, "AA", "group") + " " + osc8(uri, "BB", "other") + "\r\n" +
                    osc8("https://example.com/other", "CC", "group") + "\r\n" + osc8(uri, "DD", "group"),
            )
            f.refresh()
            f.hover(0, 2)
            assertEquals(1, f.controller.hoveredSegmentCount)
            assertEquals(listOf(RepaintSpan(2, 0, 2, 2)), f.host.repaintSpans)
            f.hover(3, 0)
            assertEquals(1, f.controller.hoveredSegmentCount)
            assertNotEquals(1, f.controller.hoveredHyperlinkId)
        }
    }

    @Test
    fun `anonymous run survives hard breaks and partial overwrite without joining another run`() {
        Osc8PipelineFixture(8, 3).use { f ->
            val uri = "https://example.com/run"
            f.accept(osc8(uri, "ABCD\r\nEF") + " " + osc8(uri, "GH"))
            f.refresh()
            f.hover(0, 1)
            assertEquals(listOf(RepaintSpan(0, 0, 0, 4), RepaintSpan(1, 0, 1, 2)), f.host.repaintSpans)
            f.host.repaintSpans.clear()
            f.accept("\u001b[1;2Hx")
            f.refresh()
            f.controller.refreshHyperlinkHover()
            assertEquals(2, f.controller.hoveredSegmentCount)
            assertEquals(
                listOf(
                    RepaintSpan(0, 0, 0, 4),
                    RepaintSpan(1, 0, 1, 2),
                    RepaintSpan(0, 0, 0, 1),
                    RepaintSpan(1, 0, 1, 2),
                ),
                f.host.repaintSpans,
            )
            f.hover(3, 1)
            assertEquals(2, f.controller.hoveredHyperlinkId)
            assertEquals(1, f.controller.hoveredSegmentCount)
        }
    }

    private data class RepaintSpan(
        val startRow: Int,
        val startColumn: Int,
        val endRow: Int,
        val endColumn: Int,
    )

    private class FakeHyperlinkHost(
        override val renderCache: TerminalRenderCache,
        private val session: TerminalSession?,
        private val hostServices: SwingHostServices,
        private val discoveredActions: Map<Int, () -> Boolean> = emptyMap(),
        private val activation: SwingHyperlinkActivation = SwingHyperlinkActivation.MODIFIER,
        private val visible: Boolean = true,
    ) : TerminalHyperlinkHost {
        override var cursor: Cursor = Cursor.getDefaultCursor()

        override fun hyperlinkActivation(hyperlinkId: Int) = activation

        override fun isHyperlinkVisible(hyperlinkId: Int) = visible

        var repaints = 0
        val repaintSpans = mutableListOf<RepaintSpan>()

        override fun cellAt(
            x: Int,
            y: Int,
        ): Long {
            val col = x / 10
            val row = y / 20
            return (col.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }

        override fun repaintHyperlinkSpan(
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            repaintSpans += RepaintSpan(startRow, startColumn, endRow, endColumn)
        }

        override fun hyperlinkIdAt(
            row: Int,
            column: Int,
        ): Int = renderCache.hyperlinkIds[renderCache.rowOffset(row) + column]

        override fun isHyperlinkResolvable(hyperlinkId: Int): Boolean =
            if (hyperlinkId > 0) {
                session?.hyperlinkUri(hyperlinkId) != null
            } else {
                discoveredActions.containsKey(hyperlinkId)
            }

        override fun openHyperlink(hyperlinkId: Int): Boolean {
            if (hyperlinkId < 0) return discoveredActions[hyperlinkId]?.invoke() == true
            val uri = session?.hyperlinkUri(hyperlinkId) ?: return false
            return hostServices.hyperlinkHandler.openHyperlink(uri)
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

    private class Osc8PipelineFixture(
        width: Int,
        height: Int,
        maxHistory: Int = 16,
        hostPolicy: HostPolicy = HostPolicy(),
        hyperlinkHandler: TerminalHyperlinkHandler = TerminalHyperlinkHandler.NONE,
    ) : AutoCloseable {
        val terminal = TerminalBuffers.create(width = width, height = height, maxHistory = maxHistory)
        val session = TerminalSession.create(terminal = terminal, connector = NoOpConnector, hostPolicy = hostPolicy)
        val cache = TerminalRenderCache(width, height)
        val host = FakeHyperlinkHost(cache, session, SwingHostServices(hyperlinkHandler = hyperlinkHandler))
        val controller = TerminalHyperlinkController(host)
        private val component = JButton()

        fun accept(text: String) = accept(text.encodeToByteArray())

        fun accept(
            bytes: ByteArray,
            chunkSize: Int = Int.MAX_VALUE,
        ) {
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(chunkSize, bytes.size - offset)
                session.onBytes(bytes, offset, length)
                offset += length
            }
        }

        fun refresh(
            scrollbackOffset: Int = 0,
            viewportRows: Int = 0,
        ) {
            cache.updateFrom(session, scrollbackOffset, viewportRows)
        }

        fun hover(
            column: Int,
            row: Int,
            activationHover: Boolean = false,
        ) {
            val modifiers = if (activationHover) hyperlinkNavigationModifierMask else 0
            controller.handleMouseMoved(
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(),
                    modifiers,
                    column * CELL_WIDTH + CELL_WIDTH / 2,
                    row * CELL_HEIGHT + CELL_HEIGHT / 2,
                    0,
                    false,
                ),
            )
        }

        fun assertHoverSpan(
            hyperlinkId: Int,
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            assertEquals(hyperlinkId, controller.hoveredHyperlinkId)
            assertEquals(startRow, controller.segmentRow(0))
            assertEquals(startColumn, controller.segmentStartColumn(0))
            assertEquals(endRow, controller.segmentRow(controller.hoveredSegmentCount - 1))
            assertEquals(endColumn, controller.segmentEndColumn(controller.hoveredSegmentCount - 1))
            assertEquals(endRow - startRow + 1, controller.hoveredSegmentCount)
            for (index in 0 until controller.hoveredSegmentCount) {
                assertEquals(startRow + index, controller.segmentRow(index))
                assertEquals(if (index == 0) startColumn else 0, controller.segmentStartColumn(index))
                assertEquals(
                    if (index ==
                        controller.hoveredSegmentCount - 1
                    ) {
                        endColumn
                    } else {
                        cache.columns
                    },
                    controller.segmentEndColumn(index),
                )
            }
        }

        fun click(
            column: Int,
            row: Int,
        ): Boolean {
            val event =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_PRESSED,
                    0L,
                    InputEvent.BUTTON1_DOWN_MASK or hyperlinkNavigationModifierMask,
                    column * CELL_WIDTH + CELL_WIDTH / 2,
                    row * CELL_HEIGHT + CELL_HEIGHT / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            controller.handleMousePressed(event)
            val release =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_RELEASED,
                    0L,
                    hyperlinkNavigationModifierMask,
                    event.x,
                    event.y,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            val handled = controller.handleMouseReleased(release)
            assertEquals(handled, release.isConsumed)
            return handled
        }

        fun assertNoHover() {
            assertEquals(0, controller.hoveredHyperlinkId)
            assertEquals(Cursor.getDefaultCursor(), host.cursor)
        }

        override fun close() {
            session.close()
        }
    }

    @Test
    fun `hover over cell without hyperlink keeps default cursor`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeHyperlinkHost(cache, null, SwingHostServices())
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
                renderPublisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(1, controller.segmentRow(0))
        assertEquals(1, controller.segmentStartColumn(0))
        assertEquals(1, controller.segmentRow(controller.hoveredSegmentCount - 1))
        assertEquals(2, controller.segmentEndColumn(controller.hoveredSegmentCount - 1))
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
                renderPublisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
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
    fun `hover over repeated same id isolates separated occurrences on one row`() {
        val cache =
            TerminalRenderCache(6, 2).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
                hyperlinkIds[rowOffset(1) + 2] = 5
                hyperlinkIds[rowOffset(1) + 4] = 5
            }
        val terminal = TerminalBuffers.create(width = 6, height = 2, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(6, 2),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(1, controller.segmentRow(0))
        assertEquals(1, controller.segmentStartColumn(0))
        assertEquals(1, controller.segmentRow(controller.hoveredSegmentCount - 1))
        assertEquals(3, controller.segmentEndColumn(controller.hoveredSegmentCount - 1))
        assertEquals(1, controller.hoveredSegmentCount)
        controller.handleMouseMoved(MouseEvent(button, MouseEvent.MOUSE_MOVED, 0L, 0, 45, 25, 0, false))
        assertEquals(1, controller.hoveredSegmentCount)
        assertEquals(4, controller.segmentStartColumn(0))
        assertEquals(5, controller.segmentEndColumn(0))
    }

    @Test
    fun `hover over soft-wrapped link tracks the full wrapped contiguous span`() {
        val cache =
            TerminalRenderCache(3, 2).apply {
                hyperlinkIds[rowOffset(0) + 1] = 5
                hyperlinkIds[rowOffset(0) + 2] = 5
                hyperlinkIds[rowOffset(1)] = 5
                hyperlinkIds[rowOffset(1) + 1] = 5
                lineWrapped[0] = true
            }
        val terminal = TerminalBuffers.create(width = 3, height = 2, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 2),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 5, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(0, controller.segmentRow(0))
        assertEquals(1, controller.segmentStartColumn(0))
        assertEquals(1, controller.segmentRow(controller.hoveredSegmentCount - 1))
        assertEquals(2, controller.segmentEndColumn(controller.hoveredSegmentCount - 1))
    }

    @Test
    fun `hover state repaints only old and new hyperlink spans`() {
        val cache =
            TerminalRenderCache(6, 2).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
                hyperlinkIds[rowOffset(1) + 2] = 5
                hyperlinkIds[rowOffset(1) + 4] = 5
            }
        val terminal = TerminalBuffers.create(width = 6, height = 2, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(6, 2),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)
        val button = JButton()

        controller.handleMouseMoved(MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false))
        assertEquals(listOf(RepaintSpan(1, 1, 1, 3)), host.repaintSpans)
        assertEquals(0, host.repaints)

        controller.updateHyperlinkActivationHover(true)
        assertEquals(List(2) { RepaintSpan(1, 1, 1, 3) }, host.repaintSpans)
        assertEquals(0, host.repaints)

        controller.handleMouseMoved(MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 45, 25, 0, false))
        assertEquals(
            List(3) { RepaintSpan(1, 1, 1, 3) } + RepaintSpan(1, 4, 1, 5),
            host.repaintSpans,
        )
        assertEquals(0, host.repaints)
    }

    @Test
    fun `real OSC8 repeated anonymous links produce independent UI hover spans`() {
        Osc8PipelineFixture(width = 8, height = 2).use { f ->
            f.accept(osc8("https://example.com/a", "AA") + " " + osc8("https://example.com/a", "BB"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 2)

            f.hover(column = 3, row = 0)
            f.assertHoverSpan(hyperlinkId = 2, startRow = 0, startColumn = 3, endRow = 0, endColumn = 5)
        }
    }

    @Test
    fun `real OSC8 span includes wide cells and grapheme clusters`() {
        Osc8PipelineFixture(width = 8, height = 2).use { f ->
            f.accept(osc8("https://example.com/unicode", "\u4E2De\u0301Z"))
            f.refresh()

            assertEquals("e\u0301", f.cache.clusterText(row = 0, column = 2))
            f.hover(column = 1, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 4)
        }
    }

    @Test
    fun `real OSC8 span excludes erased and partially overwritten cells`() {
        Osc8PipelineFixture(width = 6, height = 2).use { f ->
            f.accept(osc8("https://example.com/edit", "ABCD") + "\u001B[1;3H\u001B[K\u001B[1;2Hx")
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 1)

            f.hover(column = 1, row = 0)
            f.assertNoHover()
        }
    }

    @Test
    fun `real OSC8 links in scrollback resolve through render cache viewport`() {
        Osc8PipelineFixture(width = 8, height = 2, maxHistory = 8).use { f ->
            f.accept(osc8("https://example.com/history", "SC") + "\r\nplain\r\nbottom")
            f.refresh(scrollbackOffset = 1)

            assertEquals(1, f.cache.historySize)
            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 2)
        }
    }

    @Test
    fun `real OSC8 alternate buffer links are resolved from active alternate frame`() {
        Osc8PipelineFixture(width = 6, height = 2).use { f ->
            f.accept(osc8("https://example.com/primary", "P") + "\u001B[?1049h" + osc8("https://example.com/alt", "A"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 2, startRow = 0, startColumn = 0, endRow = 0, endColumn = 1)
        }
    }

    @Test
    fun `real OSC8 wrapped link remains one span after resize reflow`() {
        Osc8PipelineFixture(width = 6, height = 2, maxHistory = 8).use { f ->
            f.accept(osc8("https://example.com/reflow", "abcdefg"))
            f.session.resize(columns = 3, rows = 3)
            f.refresh()

            f.hover(column = 1, row = 1)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 2, endColumn = 1)
        }
    }

    @Test
    fun `real OSC8 bidi row keeps logical hover span bounds`() {
        Osc8PipelineFixture(width = 6, height = 2).use { f ->
            f.accept(osc8("https://example.com/bidi", "\u05D0\u05D1"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 2)
        }
    }

    @Test
    fun `real OSC8 evicted hyperlink id is not hoverable even when cells still carry the id`() {
        Osc8PipelineFixture(width = 4, height = 2, hostPolicy = HostPolicy(maxHyperlinkEntries = 1)).use { f ->
            f.accept(osc8("https://example.com/old", "A") + osc8("https://example.com/new", "B"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertNoHover()

            f.hover(column = 1, row = 0)
            f.assertHoverSpan(hyperlinkId = 2, startRow = 0, startColumn = 1, endRow = 0, endColumn = 2)
        }
    }

    @Test
    fun `real OSC8 link crossing viewport boundary is clamped to visible cache rows`() {
        Osc8PipelineFixture(width = 3, height = 2, maxHistory = 4).use { f ->
            f.accept(osc8("https://example.com/boundary", "abcdef"))
            f.refresh(scrollbackOffset = 0, viewportRows = 1)

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 3)
        }
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
                renderPublisher = TerminalRenderPublisher(10, 10),
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
        val host = FakeHyperlinkHost(cache, session, SwingHostServices(hyperlinkHandler = handler))
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val clickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK or hyperlinkNavigationModifierMask,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        assertTrue(controller.handleMousePressed(clickEvent))
        val release =
            MouseEvent(
                button,
                MouseEvent.MOUSE_RELEASED,
                0L,
                hyperlinkNavigationModifierMask,
                clickEvent.x,
                clickEvent.y,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        val consumed = controller.handleMouseReleased(release)

        assertTrue(consumed)
        assertEquals("https://example.com", openedUri.get())
    }

    @Test
    fun `ctrl click on discovered hyperlink invokes discovered action`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = -1
            }
        val opened = AtomicBoolean(false)
        val host =
            FakeHyperlinkHost(
                renderCache = cache,
                session = null,
                hostServices = SwingHostServices(),
                discoveredActions =
                    mapOf(
                        -1 to {
                            opened.set(true)
                            true
                        },
                    ),
            )
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val clickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK or hyperlinkNavigationModifierMask,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        assertTrue(controller.handleMousePressed(clickEvent))
        val release =
            MouseEvent(
                button,
                MouseEvent.MOUSE_RELEASED,
                0L,
                hyperlinkNavigationModifierMask,
                clickEvent.x,
                clickEvent.y,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        val consumed = controller.handleMouseReleased(release)

        assertTrue(consumed)
        assertTrue(opened.get())
    }

    @Test
    fun `direct link opens only on unchanged release and drag cancels activation`() {
        val cache = TerminalRenderCache(4, 1)
        cache.hyperlinkIds[0] = -1
        cache.hyperlinkIds[1] = -2
        val opened = ArrayList<Int>()
        val host =
            FakeHyperlinkHost(
                cache,
                null,
                SwingHostServices(),
                mapOf(-1 to { opened.add(-1) }, -2 to { opened.add(-2) }),
                SwingHyperlinkActivation.DIRECT,
            )
        val controller = TerminalHyperlinkController(host)
        val component = JButton()

        fun mouse(
            kind: Int,
            column: Int = 0,
        ) = MouseEvent(component, kind, 0L, 0, column * 10 + 1, 1, 1, false, MouseEvent.BUTTON1)
        assertFalse(controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED)))
        assertTrue(opened.isEmpty(), "Press must not navigate")
        assertTrue(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED)))
        assertEquals(listOf(-1), opened)
        controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED))
        controller.handleMouseDragged()
        assertFalse(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED)))
        controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED))
        assertFalse(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED, 1)))
        controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED))
        cache.hyperlinkIds[0] = -2
        assertFalse(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED)))
        assertEquals(listOf(-1), opened)
    }

    @Test
    fun `implicit link requires navigation modifier for cursor and release`() {
        val cache = TerminalRenderCache(4, 1)
        cache.hyperlinkIds[0] = -1
        var opened = 0
        val host =
            FakeHyperlinkHost(
                cache,
                null,
                SwingHostServices(),
                mapOf(
                    -1 to {
                        opened++
                        true
                    },
                ),
                visible = false,
            )
        val controller = TerminalHyperlinkController(host)
        val component = JButton()
        controller.handleMouseMoved(MouseEvent(component, MouseEvent.MOUSE_MOVED, 0L, 0, 1, 1, 0, false))
        assertEquals(Cursor.DEFAULT_CURSOR, host.cursor.type)
        controller.updateHyperlinkActivationHover(true)
        assertEquals(Cursor.HAND_CURSOR, host.cursor.type)
        controller.updateHyperlinkActivationHover(false)
        assertEquals(Cursor.DEFAULT_CURSOR, host.cursor.type)

        fun mouse(
            kind: Int,
            modifiers: Int,
        ) = MouseEvent(component, kind, 0L, modifiers, 1, 1, 1, false, MouseEvent.BUTTON1)
        assertFalse(controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED, 0)))
        assertFalse(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED, hyperlinkNavigationModifierMask)))
        assertTrue(controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED, hyperlinkNavigationModifierMask)))
        assertFalse(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED, 0)))
        assertTrue(controller.handleMousePressed(mouse(MouseEvent.MOUSE_PRESSED, hyperlinkNavigationModifierMask)))
        assertTrue(controller.handleMouseReleased(mouse(MouseEvent.MOUSE_RELEASED, hyperlinkNavigationModifierMask)))
        assertEquals(1, opened)
    }

    @Test
    fun `concealed cells cannot hover activate or expose a context target`() {
        val cache = TerminalRenderCache(4, 1)
        cache.hyperlinkIds.fill(-1)
        cache.attrWords[0] =
            io.github.ketraterm.render.api.TerminalRenderAttrs
                .pack(invisible = true)
        val host = FakeHyperlinkHost(cache, null, SwingHostServices(), mapOf(-1 to { error("Concealed link opened") }))
        val controller = TerminalHyperlinkController(host)
        val component = JButton()
        val event = MouseEvent(component, MouseEvent.MOUSE_PRESSED, 0L, hyperlinkNavigationModifierMask, 1, 1, 1, false, MouseEvent.BUTTON1)
        assertEquals(0, controller.hyperlinkIdAt(event))
        assertFalse(controller.handleMousePressed(event))
        controller.updatePointerPosition(1, 1)
        assertEquals(0, controller.hoveredHyperlinkId)
        controller.updatePointerPosition(11, 1)
        assertEquals(-1, controller.hoveredHyperlinkId)
        assertEquals(1, controller.segmentStartColumn(0), "Concealed cells must not enter the visible hover group")
    }

    private data class CapturedLinkSegment(
        val row: Int,
        val startColumn: Int,
        val endColumn: Int,
    )

    companion object {
        private const val CELL_WIDTH = 10
        private const val CELL_HEIGHT = 20

        private fun agyCapture(): ByteArray =
            requireNotNull(TerminalHyperlinkControllerTest::class.java.getResourceAsStream("/hyperlinks/agy-auth-176x32.ansi")) {
                "Missing sanitized agy capture"
            }.use { it.readBytes() }

        private fun agyTarget(bytes: ByteArray): String =
            bytes.decodeToString().substringAfter("\u001b]8;id=agyfixture;").substringBefore('\u0007')

        private fun capturedLinkSegments(cache: TerminalRenderCache): List<CapturedLinkSegment> =
            buildList {
                for (row in 0 until cache.rows) {
                    var column = 0
                    while (column < cache.columns) {
                        val id = cache.hyperlinkIds[cache.rowOffset(row) + column]
                        if (id <= 0) {
                            column++
                            continue
                        }
                        val start = column++
                        while (column < cache.columns && cache.hyperlinkIds[cache.rowOffset(row) + column] == id) column++
                        add(CapturedLinkSegment(row, start, column))
                    }
                }
            }

        /** Diagnostic trace: records observed hover geometry without making a defect an expected test result. */
        @JvmStatic
        fun main(args: Array<String>) {
            SwingUtilities.invokeAndWait {
                Osc8PipelineFixture(width = 176, height = 32).use { fixture ->
                    val bytes = agyCapture()
                    fixture.accept(bytes)
                    fixture.refresh()
                    println("agy capture: bytes=${bytes.size}, targetChars=${agyTarget(bytes).length}")
                    for (segment in capturedLinkSegments(fixture.cache)) {
                        fixture.hover(segment.startColumn, segment.row)
                        val hover = fixture.controller
                        println(
                            "segment=$segment, wrapped=${fixture.cache.lineWrapped[segment.row]}, " +
                                "id=${hover.hoveredHyperlinkId}, " +
                                "hover=${hover.segmentRow(0)}:${hover.segmentStartColumn(0)}" +
                                "..${hover.segmentRow(
                                    hover.hoveredSegmentCount - 1,
                                )}:${hover.segmentEndColumn(hover.hoveredSegmentCount - 1)}",
                        )
                    }
                }
            }
        }

        private fun osc8(
            uri: String,
            text: String,
            id: String? = null,
        ): String = osc8Open(uri, id) + text + OSC8_CLOSE

        private fun osc8Open(
            uri: String,
            id: String?,
        ): String {
            val params = if (id == null) "" else "id=$id"
            return "\u001B]8;$params;$uri\u0007"
        }

        private const val OSC8_CLOSE = "\u001B]8;;\u0007"
    }
}
