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
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalSearchScanTest {
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
                                        copied++
                                        frame.copyLine(
                                            row,
                                            codeWords,
                                            codeOffset,
                                            attrWords,
                                            attrOffset,
                                            flags,
                                            flagOffset,
                                            extraAttrWords,
                                            extraAttrOffset,
                                            hyperlinkIds,
                                            hyperlinkOffset,
                                            clusterSink,
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
            assertTrue(calls > 1)
            assertTrue(maximum * 160 <= 4096, "No lock acquisition may copy the entire tall grid")
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
            { if (cancel && ++checks == 1000) throw cancelled },
        )
        cache.lineWrapped.fill(true)
        model.append(cache, cache.discardedCount)
        cancel = true
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException::class.java) { model.finish() })
        assertEquals(1000, checks)
    }
}
