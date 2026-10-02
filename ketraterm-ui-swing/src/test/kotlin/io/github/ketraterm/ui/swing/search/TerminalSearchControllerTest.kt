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
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

class TerminalSearchControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @AfterEach fun cleanupSearch() {
        scope.cancel()
    }

    @Test
    fun `content generation rollover adds and removes matches including empty results`() {
        val reader = SearchFrameReader(liveLines = listOf("absent"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                assertEquals(0, controller.state().resultCount)
                val generations = longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE, 0L)
                for ((index, generation) in generations.withIndex()) {
                    reader.liveLines = listOf(if (index == 1) "absent" else "needle")
                    reader.contentGeneration = generation
                    reader.frameGeneration++
                    host.renderCache.updateFrom(session)
                    controller.refreshForFrame()
                    dispatcher.scheduler.runCurrent()
                    assertEquals(if (index == 1) 0 else 1, controller.state().resultCount)
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `cursor and viewport frames reuse search without reading retained history`() {
        val reader = SearchFrameReader(historyLines = List(1000) { "needle" }, liveLines = listOf("needle"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                controller.findNext()
                val active = controller.state().activeResultIndex
                repeat(3) {
                    reader.frameGeneration++
                    reader.cursorColumn++
                    host.renderCache.updateFrom(session, scrollbackOffset = it, viewportRows = 1)
                    val reads = reader.readCount

                    controller.refreshForFrame()
                    dispatcher.scheduler.runCurrent()

                    assertEquals(reads, reader.readCount, "Unchanged content must not request another frame")
                    assertEquals(1001, controller.state().resultCount)
                    assertEquals(active, controller.state().activeResultIndex)
                    assertEquals(1, controller.viewportHighlights.segmentCount)
                }
                host.renderCache.updateFrom(session, scrollbackOffset = 999, viewportRows = 1)
                controller.refreshForFrame()
                dispatcher.scheduler.runCurrent()
                assertTrue(controller.viewportHighlights.isActive(0))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `content changes outside the viewport refresh results even if another consumer refreshed the shared cache`() {
        val reader = SearchFrameReader(historyLines = listOf("needle"), liveLines = listOf("absent"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                assertEquals(1, controller.state().resultCount)

                reader.liveLines = listOf("needle")
                reader.contentGeneration++
                reader.frameGeneration++
                host.renderCache.updateFrom(session, scrollbackOffset = 1, viewportRows = 1)
                host.searchCache.updateFrom(session, scrollbackOffset = 1, viewportRows = 2)
                controller.refreshForFrame()
                dispatcher.scheduler.runCurrent()

                assertEquals(2, controller.state().resultCount)
                assertEquals(0, controller.state().activeResultIndex)
                val reads = reader.readCount
                controller.refreshForFrame()
                dispatcher.scheduler.runCurrent()
                assertEquals(reads, reader.readCount)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `search reads all current history when published viewport lags output`() {
        val reader = SearchFrameReader(historyLines = listOf("needle"), liveLines = listOf("absent"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                reader.historyLines = listOf("needle", "needle", "needle")
                reader.contentGeneration++
                reader.frameGeneration++

                controller.search("needle")
                dispatcher.scheduler.runCurrent()

                assertEquals(3, controller.state().resultCount)
                host.renderCache.updateFrom(session)
                val reads = reader.readCount
                controller.refreshForFrame()
                dispatcher.scheduler.runCurrent()
                assertEquals(reads, reader.readCount)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `empty query never reads retained history`() {
        val reader = SearchFrameReader(liveLines = listOf("needle"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                val reads = reader.readCount
                controller.refreshForFrame()
                dispatcher.scheduler.runCurrent()
                assertEquals(reads, reader.readCount)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `search scans retained scrollback and requests active result viewport`() {
        val reader = SearchFrameReader(historyLines = listOf("needle"), liveLines = listOf(""))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("needle")
            dispatcher.scheduler.runCurrent()
        }

        val state = controller.state()
        assertEquals("needle", state.query)
        assertEquals(1, state.resultCount)
        assertEquals(0, state.activeResultIndex)
        assertEquals(1, host.scrollRequestCount)
        assertEquals(2, host.repaintCount)
        session.close()
    }

    @Test
    fun `reset clears query result state and viewport highlights`() {
        val reader = SearchFrameReader(historyLines = listOf("needle"), liveLines = listOf(""))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("needle")
            dispatcher.scheduler.runCurrent()
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
        val controller = TerminalSearchController(host, scope, dispatcher)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("a")
            dispatcher.scheduler.runCurrent()

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
        val controller = TerminalSearchController(host, scope, dispatcher)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("Needle")
            dispatcher.scheduler.runCurrent()

            assertEquals(3, controller.state().resultCount)

            controller.setIgnoreCase(false)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, controller.state().resultCount)
            assertEquals(0, controller.state().activeResultIndex)

            controller.setIgnoreCase(true)
            dispatcher.scheduler.runCurrent()
            assertEquals(3, controller.state().resultCount)
        }

        session.close()
    }

    @Test
    fun `clear removes query results and viewport highlights`() {
        val reader = SearchFrameReader(liveLines = listOf("needle"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, columns = reader.columns, rows = reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)

        SwingUtilities.invokeAndWait {
            host.renderCache.updateFrom(session)
            controller.search("needle")
            dispatcher.scheduler.runCurrent()
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

    @Test
    fun `query replacement discards a completed worker result awaiting publication`() {
        val worker = QueuedDispatcher()
        val reader = SearchFrameReader(liveLines = listOf("alpha bravo"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, reader.columns, reader.visibleRows)
        val controller = TerminalSearchController(host, scope, worker)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                controller.search("alpha")
                assertTrue(controller.state().isSearching)
                assertEquals(0, controller.state().resultCount)
                dispatcher.scheduler.runCurrent()
                worker.runCurrent()
                // The first result is ready, but its EDT continuation has not run.
                controller.search("missing")
                dispatcher.scheduler.runCurrent()
                worker.runCurrent()
                dispatcher.scheduler.runCurrent()
                assertEquals(TerminalSearchState("missing", 0, -1), controller.state())
                assertEquals(0, controller.viewportHighlights.segmentCount)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `clear and reset reject queued publication and release the worker slot`() {
        val worker = QueuedDispatcher()
        val reader = SearchFrameReader(liveLines = listOf("needle"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, reader.columns, reader.visibleRows)
        val controller = TerminalSearchController(host, scope, worker)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                repeat(2) { reset ->
                    controller.search("needle")
                    dispatcher.scheduler.runCurrent()
                    worker.runCurrent()
                    if (reset == 0) controller.clear() else controller.reset(1)
                    dispatcher.scheduler.runCurrent()
                    assertEquals(TerminalSearchState("", 0, -1), controller.state())
                    assertEquals(0, controller.viewportHighlights.segmentCount)
                }
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                worker.runCurrent()
                dispatcher.scheduler.runCurrent()
                assertEquals(TerminalSearchState("needle", 1, 0), controller.state())
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `refresh preserves the active match location when earlier matches are inserted`() {
        val reader = SearchFrameReader(historyLines = listOf("absent", "needle"), liveLines = listOf("needle"))
        val session = testSession(reader)
        val host = RecordingSearchHost(session, reader.columns, reader.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                controller.findNext()
                assertEquals(1, controller.state().activeResultIndex)
                reader.historyLines = listOf("needle", "needle")
                reader.contentGeneration++
                reader.frameGeneration++
                host.renderCache.updateFrom(session)
                controller.refreshForFrame()
                dispatcher.scheduler.runCurrent()
                assertEquals(3, controller.state().resultCount)
                assertEquals(2, controller.state().activeResultIndex)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `reader failure is observable and changing the query retries`() {
        val source = SearchFrameReader(liveLines = listOf("needle"))
        val error = IllegalStateException("read failed")
        var fail = true
        val reader =
            object : TerminalRenderFrameReader by source {
                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    if (fail) throw error
                    source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                }
            }
        val session = testSession(reader)
        val host = RecordingSearchHost(session, source.columns, source.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        try {
            SwingUtilities.invokeAndWait {
                host.renderCache.updateFrom(session)
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                assertFalse(controller.state().isSearching)
                assertSame(error, generateSequence(controller.state().failure) { it.cause }.last())
                fail = false
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                assertEquals(TerminalSearchState("needle", 1, 0), controller.state())
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `blocked background read keeps EDT responsive and query replacement waits for the worker`() {
        val entered = CompletableFuture<Boolean>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val source = SearchFrameReader(liveLines = listOf("needle"))
        val reader =
            object : TerminalRenderFrameReader by source {
                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    try {
                        if (reads.incrementAndGet() == 1) {
                            entered.complete(SwingUtilities.isEventDispatchThread())
                            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release search read" }
                        }
                        source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        val session = testSession(reader)
        val host = RecordingSearchHost(session, source.columns, source.visibleRows)
        val uiScope =
            CoroutineScope(
                SupervisorJob() +
                    object : CoroutineDispatcher() {
                        override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()

                        override fun dispatch(
                            context: CoroutineContext,
                            block: Runnable,
                        ) = SwingUtilities.invokeLater(block)
                    },
            )
        val controller = TerminalSearchController(host, uiScope)
        val completed = CompletableFuture<TerminalSearchState>()
        try {
            SwingUtilities.invokeAndWait {
                uiScope.launch { completed.complete(controller.states.first { it.query == "missing" && !it.isSearching }) }
                host.renderCache.updateFrom(session)
                controller.search("needle")
            }
            assertFalse(entered.get(10, TimeUnit.SECONDS), "History copying must run off the EDT")
            SwingUtilities.invokeAndWait {
                controller.search("missing")
                assertTrue(controller.state().isSearching)
                assertEquals(0, controller.state().resultCount)
            }
            assertEquals(1, reads.get(), "An uncancellable read must retain the sole worker slot")
            release.countDown()
            assertEquals(TerminalSearchState("missing", 0, -1), completed.get(10, TimeUnit.SECONDS))
            assertEquals(1, maximum.get())
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait {
                controller.reset(1)
                uiScope.cancel()
            }
            session.close()
        }
    }

    @Test
    fun `continuous content changes publish completed passes instead of starving results`() {
        val source = SearchFrameReader(historyLines = List(65) { "needle" }, liveLines = listOf("needle"))
        var rangeReads = 0
        val reader =
            object : TerminalRenderFrameReader by source {
                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                    rangeReads++
                    source.contentGeneration++
                    source.frameGeneration++
                }
            }
        val session = testSession(reader)
        val host = RecordingSearchHost(session, source.columns, source.visibleRows)
        val controller = TerminalSearchController(host, scope, dispatcher)
        var published: TerminalSearchState? = null
        try {
            SwingUtilities.invokeAndWait {
                scope.launch {
                    controller.states.collect {
                        if (it.resultCount > 0) {
                            published = it
                            controller.clear()
                        }
                    }
                }
                host.renderCache.updateFrom(session)
                controller.search("needle")
                dispatcher.scheduler.runCurrent()
                assertEquals(66, published?.resultCount)
                assertEquals(true, published?.isSearching, "Publication must precede catch-up work")
                assertEquals(TerminalSearchState("", 0, -1), controller.state())
                assertEquals(2, rangeReads, "A changed generation must not restart the same pass")
            }
        } finally {
            session.close()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.addLast(block)
        }

        fun runCurrent() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class RecordingSearchHost(
        override val session: TerminalSession,
        columns: Int,
        rows: Int,
    ) : TerminalSearchHost {
        override val renderCache = TerminalRenderCache(columns, rows)
        val searchCache = TerminalRenderCache(columns, rows)
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
        var historyLines: List<String> = emptyList(),
        var liveLines: List<String>,
    ) : TerminalRenderFrameReader {
        val columns: Int = (historyLines + liveLines).maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val visibleRows: Int = liveLines.size.coerceAtLeast(1)
        var frameGeneration: Long = 1L
        var contentGeneration: Long = 1L
        var cursorColumn: Int = 0
        var readCount: Int = 0
            private set

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
            readCount++
            consumer.accept(
                SearchFrame(
                    historyLines = historyLines,
                    liveLines = liveLines,
                    columns = columns,
                    scrollbackOffset = scrollbackOffset.coerceIn(0, historyLines.size),
                    rows = viewportRows.coerceAtLeast(1),
                    frameGeneration = frameGeneration,
                    contentGeneration = contentGeneration,
                    cursorColumn = cursorColumn,
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
        override val frameGeneration: Long,
        override val contentGeneration: Long,
        cursorColumn: Int,
    ) : TerminalRenderFrame {
        override val historySize: Int = historyLines.size
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = cursorColumn,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = contentGeneration

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

    private fun testSession(renderReader: TerminalRenderFrameReader): TerminalSession {
        var columns = 1
        var rows = 1
        renderReader.readRenderFrame {
            columns = it.columns
            rows = it.rows
        }
        val terminal = TerminalBuffers.create(width = columns, height = rows, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            renderPublisher = TerminalRenderPublisher(columns, rows),
            renderReader = renderReader,
            responseReader = terminal,
            connector = NoOpConnector,
            parser = NoOpParser,
            inputEncoderFactory = TerminalInputEncoderFactory { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
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
        override fun setInputPolicy(policy: io.github.ketraterm.input.policy.TerminalInputPolicy) = Unit

        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}
