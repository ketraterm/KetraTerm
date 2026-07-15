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
package io.github.ketraterm.ui.swing.search

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class TerminalSearchControllerTest {
    @Test
    fun `search scans retained scrollback and requests active result viewport`() {
        val reader = SearchFrameReader(historyLines = listOf("needle"), liveLines = listOf(""))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("needle")
        }

        val state = controller.state()
        assertEquals("needle", state.query)
        assertEquals(1, state.resultCount)
        assertEquals(0, state.activeResultIndex)
        assertEquals(1, host.scrollRequestCount)
        assertEquals(1, host.repaintCount)
        session.close()
    }

    @Test
    fun `reset clears query result state and viewport highlights`() {
        val reader = SearchFrameReader(historyLines = listOf("needle"), liveLines = listOf(""))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("needle")
            controller.reset(viewportRows = 1)
        }

        val state = controller.state()
        assertEquals("", state.query)
        assertEquals(0, state.resultCount)
        assertEquals(-1, state.activeResultIndex)
        assertEquals(0, controller.viewportHighlights.segmentCount)
        session.close()
    }

    @Test
    fun `next and previous cycle through active search results`() {
        val reader = SearchFrameReader(liveLines = listOf("aaa"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("a")

            assertEquals(3, controller.state().resultCount)
            assertEquals(0, controller.state().activeResultIndex)

            assertTrue(controller.findNext())
            assertEquals(1, controller.state().activeResultIndex)
            assertTrue(controller.findNext())
            assertEquals(2, controller.state().activeResultIndex)
            assertTrue(controller.findNext())
            assertEquals(0, controller.state().activeResultIndex)
            assertTrue(controller.findPrevious())
            assertEquals(2, controller.state().activeResultIndex)
        }

        session.close()
    }

    @Test
    fun `case sensitivity toggle refreshes matches`() {
        val reader = SearchFrameReader(liveLines = listOf("Needle needle NEEDLE"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("Needle")

            assertEquals(3, controller.state().resultCount)

            controller.setIgnoreCase(false)
            assertEquals(1, controller.state().resultCount)
            assertEquals(0, controller.state().activeResultIndex)

            controller.setIgnoreCase(true)
            assertEquals(3, controller.state().resultCount)
        }

        session.close()
    }

    @Test
    fun `clear removes query results and viewport highlights`() {
        val reader = SearchFrameReader(liveLines = listOf("needle"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("needle")
            assertTrue(controller.viewportHighlights.segmentCount > 0)

            controller.clear()

            val state = controller.state()
            assertEquals("", state.query)
            assertEquals(0, state.resultCount)
            assertEquals(-1, state.activeResultIndex)
            assertEquals(0, controller.viewportHighlights.segmentCount)
        }

        session.close()
    }

    private class RecordingSearchHost(
        override val session: TerminalSession,
        columns: Int,
        rows: Int,
    ) : TerminalSearchHost {
        override val renderCache = TerminalRenderCache(columns, rows)
        override val searchCache = TerminalRenderCache(columns, rows)
        var scrollRequestCount: Int = 0
            private set
        var repaintCount: Int = 0
            private set

        override fun visibleGridRows(): Int = renderCache.rows

        override fun scrollViewportTo(
            offsetRows: Int,
            historySize: Int,
            boundSession: TerminalSession,
        ): Boolean {
            scrollRequestCount++
            renderCache.updateFrom(boundSession, scrollbackOffset = offsetRows, viewportRows = visibleGridRows())
            return true
        }

        override fun repaint() {
            repaintCount++
        }
    }

    private class SearchFrameReader(
        private val historyLines: List<String> = emptyList(),
        private val liveLines: List<String>,
    ) : TerminalRenderFrameReader {
        val columns: Int = (historyLines + liveLines).maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val visibleRows: Int = liveLines.size.coerceAtLeast(1)

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, viewportRows = visibleRows, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            readRenderFrame(scrollbackOffset, viewportRows = 1, consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(
                SearchFrame(
                    historyLines = historyLines,
                    liveLines = liveLines,
                    columns = columns,
                    scrollbackOffset = scrollbackOffset.coerceIn(0, historyLines.size),
                    rows = viewportRows.coerceAtLeast(1),
                ),
            )
        }
    }

    private class SearchFrame(
        private val historyLines: List<String>,
        private val liveLines: List<String>,
        override val columns: Int,
        override val scrollbackOffset: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        override val historySize: Int = historyLines.size
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
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
            val absoluteRow = historySize - scrollbackOffset + row
            val text = lineAt(absoluteRow)
            var column = 0
            while (column < columns) {
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                if (column < text.length) {
                    codeWords[codeOffset + column] = text[column].code
                    flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                } else {
                    codeWords[codeOffset + column] = 0
                    flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                }
                column++
            }
        }

        private fun lineAt(absoluteRow: Int): String =
            if (absoluteRow < historySize) {
                historyLines.getOrElse(absoluteRow) { "" }
            } else {
                liveLines.getOrElse(absoluteRow - historySize) { "" }
            }
    }

    private fun testSession(renderReader: SearchFrameReader): TerminalSession {
        val terminal = TerminalBuffers.create(width = renderReader.columns, height = renderReader.visibleRows, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            renderPublisher = TerminalRenderPublisher(renderReader.columns, renderReader.visibleRows),
            renderReader = renderReader,
            responseReader = terminal,
            connector = NoOpConnector,
            parser = NoOpParser,
            inputEncoder = NoOpInputEncoder,
        )
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
