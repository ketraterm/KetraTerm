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
import io.github.ketraterm.render.api.TerminalRenderClusterDataSink
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalSearchScanTest {
    @Test
    fun `one edited cell copies only its logical line and reuses history matches`() =
        runTest {
            val terminal = TerminalBuffers.create(6, 1, maxHistory = 999)
            repeat(1000) { row ->
                if (row > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("needle")
            }
            val reader = CountingReader(terminal as TerminalRenderFrameReader)
            val scanner = TerminalSearchScan()
            val model = TerminalSearchModel()
            assertEquals(1000, requireNotNull(scanner.scan(reader, model, "needle", false)).highlights.resultCount)
            reader.copied.clear()
            terminal.positionCursor(0, 0)
            terminal.writeText("N")
            assertEquals(999, requireNotNull(scanner.scan(reader, model, "needle", false)).highlights.resultCount)
            assertEquals(listOf(999L), reader.copied)
            reader.copied.clear()
            assertEquals(999, requireNotNull(scanner.scan(reader, model, "needle", false)).highlights.resultCount)
            assertTrue(reader.copied.isEmpty(), "An unchanged pass needs no text copying")
            assertEquals(1000, requireNotNull(scanner.scan(reader, model, "needle", true)).highlights.resultCount)
            assertEquals(1000, reader.copied.size, "Case policy changes invalidate reuse")
        }

    @Test
    fun `wrapped edits and newly joined lines agree with a fresh scan`() =
        runTest {
            val terminal = TerminalBuffers.create(4, 3)
            terminal.writeText("abcd")
            terminal.carriageReturn()
            terminal.newLine()
            terminal.writeText("efgh")
            val source = terminal as TerminalRenderFrameReader
            val reader = CountingReader(source)
            val scanner = TerminalSearchScan()
            val model = TerminalSearchModel()
            assertEquals(0, requireNotNull(scanner.scan(reader, model, "cdef", false)).highlights.resultCount)
            terminal.positionCursor(3, 0)
            terminal.writeText("de")
            reader.copied.clear()
            assertEquals(1, requireNotNull(scanner.scan(reader, model, "cdef", false)).highlights.resultCount)
            assertEquals(listOf(0L, 1L), reader.copied)
            terminal.positionCursor(0, 1)
            terminal.eraseCurrentLine()
            terminal.writeText("efgh")
            reader.copied.clear()
            assertEquals(0, requireNotNull(scanner.scan(reader, model, "cdef", false)).highlights.resultCount)
            assertEquals(listOf(0L, 1L), reader.copied, "Splitting a logical line invalidates both pieces")
            terminal.positionCursor(3, 0)
            terminal.writeText("de")
            assertEquals(1, requireNotNull(scanner.scan(reader, model, "cdef", false)).highlights.resultCount)
            terminal.positionCursor(1, 1)
            terminal.writeText("X")
            reader.copied.clear()
            val result = requireNotNull(scanner.scan(reader, model, "cdef", false))
            assertEquals(0, result.highlights.resultCount)
            assertEquals(listOf(0L, 1L), reader.copied, "An edit must re-evaluate both halves of a wrapped match")
            assertEquals(
                requireNotNull(TerminalSearchScan().scan(source, TerminalSearchModel(), "cdef", false)).highlights.resultCount,
                result.highlights.resultCount,
            )
        }

    @Test
    fun `splitting a wrapped prefix rescans an untouched suffix for non overlapping matches`() =
        runTest {
            val terminal = TerminalBuffers.create(4, 2)
            terminal.writeText("aaaaaaaa")
            val reader = CountingReader(terminal as TerminalRenderFrameReader)
            val scanner = TerminalSearchScan()
            val model = TerminalSearchModel()
            assertEquals(2, requireNotNull(scanner.scan(reader, model, "aaa", false)).highlights.resultCount)
            terminal.positionCursor(0, 0)
            terminal.eraseCurrentLine()
            reader.copied.clear()
            val matches = requireNotNull(scanner.scan(reader, model, "aaa", false)).highlights
            assertEquals(1, matches.resultCount)
            assertEquals(1L, matches.activeStartAbsoluteRow())
            assertEquals(0, matches.activeStartColumn())
            assertEquals(listOf(0L, 1L), reader.copied)
        }

    @Test
    fun `eviction reuses intact lines but rescans a truncated wrapped prefix`() =
        runTest {
            for (wrapped in listOf(false, true)) {
                val terminal = TerminalBuffers.create(4, 1, maxHistory = 4)
                repeat(5) { row ->
                    if (!wrapped && row > 0) {
                        terminal.carriageReturn()
                        terminal.newLine()
                    }
                    terminal.writeText("aaaa")
                }
                val source = terminal as TerminalRenderFrameReader
                val reader = CountingReader(source)
                val scanner = TerminalSearchScan()
                val model = TerminalSearchModel()
                scanner.scan(reader, model, "aaa", false)
                if (!wrapped) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("aaaa")
                reader.copied.clear()
                val result = requireNotNull(scanner.scan(reader, model, "aaa", false)).highlights
                val expected = requireNotNull(TerminalSearchScan().scan(source, TerminalSearchModel(), "aaa", false)).highlights
                assertEquals(expected.resultCount, result.resultCount)
                repeat(expected.resultCount) {
                    expected.activate(it)
                    result.activate(it)
                    assertEquals(expected.activeStartAbsoluteRow(), result.activeStartAbsoluteRow())
                    assertEquals(expected.activeStartColumn(), result.activeStartColumn())
                }
                assertEquals(if (wrapped) listOf(1L, 2L, 3L, 4L, 5L) else listOf(5L), reader.copied)
            }
        }

    @Test
    fun `progress publishes detached complete results before the history pass ends`() =
        runTest {
            val terminal = TerminalBuffers.create(6, 1, maxHistory = 4999)
            repeat(5000) { row ->
                if (row > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("needle")
            }
            val reader = CountingReader(terminal as TerminalRenderFrameReader)
            val batches = mutableListOf<TerminalSearchHighlights>()
            var published = 0
            val result =
                requireNotNull(
                    TerminalSearchScan().scan(reader, TerminalSearchModel(), "needle", false) { progress ->
                        assertTrue(reader.copied.size < 5000, "Progress must precede completion")
                        val batch = progress.highlights
                        assertTrue(batch.resultCount > 0)
                        repeat(batch.resultCount) {
                            batch.activate(it)
                            assertEquals((published + it).toLong(), batch.activeStartAbsoluteRow())
                        }
                        published += batch.resultCount
                        batches += batch
                    },
                )
            assertTrue(batches.size >= 2)
            assertEquals(5000, result.highlights.resultCount)
            assertEquals(published, batches.sumOf { it.resultCount }, "Later work must not mutate published batches")
            batches.first().activate(0)
            assertEquals(0L, batches.first().activeStartAbsoluteRow())
        }

    @Test
    fun `resize and clear invalidate the reuse baseline`() =
        runTest {
            val terminal = TerminalBuffers.create(6, 1, maxHistory = 99)
            repeat(100) { row ->
                if (row > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("needle")
            }
            val source = terminal as TerminalRenderFrameReader
            val reader = CountingReader(source)
            val scanner = TerminalSearchScan()
            val model = TerminalSearchModel()
            scanner.scan(reader, model, "needle", false)
            terminal.resize(12, 2)
            reader.copied.clear()
            assertEquals(100, requireNotNull(scanner.scan(reader, model, "needle", false)).highlights.resultCount)
            var retained = 0
            source.readRenderFrame { retained = it.historySize + it.rows }
            assertEquals(retained, reader.copied.size)
            terminal.eraseEntireScreen()
            terminal.eraseScreenAndHistory()
            assertEquals(0, requireNotNull(scanner.scan(reader, model, "needle", false)).highlights.resultCount)
        }

    private class CountingReader(
        private val source: TerminalRenderFrameReader,
    ) : TerminalRenderFrameReader by source {
        val copied = mutableListOf<Long>()
        var afterRead: (() -> Unit)? = null

        override fun readRenderFrameForAbsoluteRange(
            startAbsoluteRow: Long,
            endAbsoluteRow: Long,
            consumer: TerminalRenderFrameConsumer,
        ) {
            source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow) { frame ->
                val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
                consumer.accept(
                    object : TerminalRenderFrame by frame {
                        override fun copyTextLine(
                            row: Int,
                            codeWords: IntArray,
                            codeOffset: Int,
                            flags: IntArray,
                            flagOffset: Int,
                            clusterDataSink: TerminalRenderClusterDataSink?,
                        ) {
                            copied += top + row
                            frame.copyTextLine(row, codeWords, codeOffset, flags, flagOffset, clusterDataSink)
                        }
                    },
                )
            }
            afterRead?.invoke()
        }
    }

    @Test
    fun `output during metadata collection preserves the stable reuse baseline`() =
        runTest {
            val terminal = TerminalBuffers.create(6, 1, maxHistory = 299)
            repeat(300) { row ->
                if (row > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("needle")
            }
            val reader = CountingReader(terminal as TerminalRenderFrameReader)
            val scanner = TerminalSearchScan()
            val model = TerminalSearchModel()
            scanner.scan(reader, model, "needle", false)
            reader.copied.clear()
            reader.afterRead = {
                reader.afterRead = null
                terminal.positionCursor(0, 0)
                terminal.writeText("absent")
            }
            val moving = requireNotNull(scanner.scan(reader, model, "needle", false))
            assertTrue(moving.changedDuringScan)
            assertEquals(299, moving.highlights.resultCount)
            assertEquals(listOf(299L), reader.copied)
            reader.copied.clear()
            val settled = requireNotNull(scanner.scan(reader, model, "needle", false))
            assertFalse(settled.changedDuringScan)
            assertEquals(299, settled.highlights.resultCount)
            assertEquals(listOf(299L), reader.copied, "Catch-up must retain reuse of unchanged history")
        }

    @Test
    fun `repeated text scans preserve cluster payloads and discard erased matches`() =
        runTest {
            val terminal = TerminalBuffers.create(4, 1, maxHistory = 100)
            repeat(64) {
                terminal.writeText("old")
                terminal.carriageReturn()
                terminal.newLine()
            }
            terminal.writeText("abc")
            terminal.writeCluster(intArrayOf(0x1F469, 0x200D, 0x1F4BB))
            terminal.writeText("x")
            val scanner = TerminalSearchScan()
            val model = TerminalSearchModel()
            val reader = terminal as TerminalRenderFrameReader
            val query = "bc\uD83D\uDC69\u200D\uD83D\uDCBBx"
            repeat(2) {
                val result = requireNotNull(scanner.scan(reader, model, query, false))
                assertEquals(1, result.highlights.resultCount)
                assertEquals(64L, result.highlights.activeStartAbsoluteRow())
                assertEquals(1, result.highlights.activeStartColumn())
            }
            terminal.eraseEntireScreen()
            terminal.eraseScreenAndHistory()
            assertEquals(0, requireNotNull(scanner.scan(reader, model, query, false)).highlights.resultCount)
            terminal.positionCursor(0, 0)
            terminal.writeCluster(intArrayOf('e'.code, 0x301))
            assertEquals(1, requireNotNull(scanner.scan(reader, model, "e\u0301", false)).highlights.resultCount)
            assertEquals(0, requireNotNull(scanner.scan(reader, model, query, false)).highlights.resultCount)
        }

    @Test
    fun `clear during a scan rejects all results from the old history`() =
        runTest {
            val terminal = TerminalBuffers.create(6, 1, maxHistory = 99)
            repeat(100) {
                terminal.writeText("needle")
                terminal.carriageReturn()
                terminal.newLine()
            }
            val source = terminal as TerminalRenderFrameReader
            var reads = 0
            val reader =
                object : TerminalRenderFrameReader by source {
                    override fun readRenderFrameForAbsoluteRange(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        consumer: TerminalRenderFrameConsumer,
                    ) {
                        source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                        if (++reads == 1) {
                            terminal.eraseEntireScreen()
                            terminal.eraseScreenAndHistory()
                        }
                    }
                }
            assertNull(TerminalSearchScan().scan(reader, TerminalSearchModel(), "needle", false))
        }

    @Test
    fun `eviction during a pass removes matches from discarded rows without restarting the pass`() =
        runTest {
            val terminal = TerminalBuffers.create(6, 1, maxHistory = 99)
            repeat(100) { row ->
                if (row > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("needle")
            }
            val source = terminal as TerminalRenderFrameReader
            var reads = 0
            val reader =
                object : TerminalRenderFrameReader by source {
                    override fun readRenderFrameForAbsoluteRange(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        consumer: TerminalRenderFrameConsumer,
                    ) {
                        source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                        if (++reads == 1) {
                            repeat(70) {
                                terminal.carriageReturn()
                                terminal.newLine()
                                terminal.writeText("absent")
                            }
                        }
                    }
                }
            val result = requireNotNull(TerminalSearchScan().scan(reader, TerminalSearchModel(), "needle", false))
            assertEquals(2, reads)
            assertEquals(30, result.highlights.resultCount)
            assertEquals(70L, result.highlights.activeStartAbsoluteRow())
            assertTrue(result.changedDuringScan)
        }

    @Test
    fun `copy batches stay bounded even inside a tall live grid`() =
        runTest {
            val terminal = TerminalBuffers.create(160, 200, maxHistory = 0)
            repeat(200) { row ->
                terminal.positionCursor(0, row)
                terminal.writeText("needle")
            }
            val source = terminal as TerminalRenderFrameReader
            var calls = 0
            var maximum = 0
            val reader =
                object : TerminalRenderFrameReader by source {
                    override fun readRenderFrameForAbsoluteRange(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        consumer: TerminalRenderFrameConsumer,
                    ) {
                        var copied = 0
                        source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow) { frame ->
                            consumer.accept(
                                object : TerminalRenderFrame by frame {
                                    override fun copyTextLine(
                                        row: Int,
                                        codeWords: IntArray,
                                        codeOffset: Int,
                                        flags: IntArray,
                                        flagOffset: Int,
                                        clusterDataSink: TerminalRenderClusterDataSink?,
                                    ) {
                                        copied++
                                        frame.copyTextLine(
                                            row,
                                            codeWords,
                                            codeOffset,
                                            flags,
                                            flagOffset,
                                            clusterDataSink,
                                        )
                                    }
                                },
                            )
                        }
                        maximum = maxOf(maximum, copied)
                        calls++
                    }
                }
            val result = requireNotNull(TerminalSearchScan().scan(reader, TerminalSearchModel(), "needle", false))
            assertEquals(200, result.highlights.resultCount)
            assertEquals(8, calls)
            assertEquals(25, maximum, "Each batch must copy text through the bounded text-only path")
        }

    @Test
    fun `wrapped wide text crosses copy batches without padding or lost matches`() =
        runTest {
            val terminal = TerminalBuffers.create(4, 2, maxHistory = 100)
            repeat(63) {
                terminal.writeText("zz")
                terminal.carriageReturn()
                terminal.newLine()
            }
            terminal.writeText("abc")
            terminal.writeCodepoint(0x754C)
            terminal.writeText("x")
            val reader = terminal as TerminalRenderFrameReader
            val result = requireNotNull(TerminalSearchScan().scan(reader, TerminalSearchModel(), "bc界", false))
            assertEquals(1, result.highlights.resultCount)
            assertEquals(63L, result.highlights.activeStartAbsoluteRow())
            assertEquals(1, result.highlights.activeStartColumn())
            val cache = TerminalRenderCache(4, 2)
            cache.updateFrom(reader)
            val viewport = TerminalSearchViewportHighlights()
            result.highlights.buildViewportHighlights(cache, viewport)
            assertEquals(2, viewport.segmentCount)
            assertEquals(1, viewport.startColumn(0))
            assertEquals(3, viewport.endColumn(0))
            assertEquals(0, viewport.startColumn(1))
            assertEquals(2, viewport.endColumn(1))
            terminal.positionCursor(0, 1)
            terminal.writeText("no")
            cache.updateFrom(reader)
            result.highlights.buildViewportHighlights(cache, viewport)
            assertEquals(0, viewport.segmentCount, "An edited row invalidates the complete visible match")
        }

    @Test
    fun `matcher cancellation interrupts a long logical line`() {
        val terminal = TerminalBuffers.create(80, 1, maxHistory = 100)
        terminal.writeText("a".repeat(8000))
        val cache = TerminalRenderCache(80, 1)
        cache.updateFromAbsoluteRange(terminal as TerminalRenderFrameReader, 0, Long.MAX_VALUE)
        val model = TerminalSearchModel()
        var checks = 0
        var cancel = false
        val cancelled = java.util.concurrent.CancellationException("stop")
        model.begin(
            "a".repeat(4000) + "b",
            false,
            cache.discardedCount,
            { if (cancel && ++checks == 3) throw cancelled },
        )
        cache.lineWrapped.fill(true)
        model.append(cache, cache.discardedCount)
        cancel = true
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException::class.java) { model.finish() })
        assertEquals(3, checks)
    }
}
