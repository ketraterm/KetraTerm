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
package io.github.ketraterm.render.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderFrameReaderTest {
    @Test
    fun `default text copy supports frames implementing only full row copies`() {
        val frame =
            object : RecordingFrame(columns = 3, rows = 1) {
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
                    assertEquals(0, row)
                    assertEquals(3, attrWords.size)
                    assertNull(extraAttrWords)
                    assertNull(hyperlinkIds)
                    assertNull(clusterSink)
                    intArrayOf('A'.code, 0, 0).copyInto(codeWords, codeOffset)
                    intArrayOf(
                        TerminalRenderCellFlags.CODEPOINT,
                        TerminalRenderCellFlags.CLUSTER,
                        TerminalRenderCellFlags.EMPTY,
                    ).copyInto(flags, flagOffset)
                    clusterDataSink?.onCluster(1, intArrayOf(99, 'e'.code, 0x301, 99), 1, 2)
                }
            }
        val codes = IntArray(5) { -1 }
        val flags = IntArray(7) { -1 }
        val clusters = mutableMapOf<Int, List<Int>>()
        frame.copyTextLine(0, codes, 1, flags, 2) { col, data, offset, length ->
            clusters[col] = data.slice(offset until offset + length)
        }
        assertArrayEquals(intArrayOf(-1, 'A'.code, 0, 0, -1), codes)
        assertArrayEquals(
            intArrayOf(-1, -1, TerminalRenderCellFlags.CODEPOINT, TerminalRenderCellFlags.CLUSTER, TerminalRenderCellFlags.EMPTY, -1, -1),
            flags,
        )
        assertEquals(mapOf(1 to listOf('e'.code, 0x301)), clusters)
    }

    @Test
    fun `reader passes short lived frame to callback`() {
        val frame = RecordingFrame(columns = 80, rows = 24)
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(frame)
                }
            }

        var observed: TerminalRenderFrame? = null
        reader.readRenderFrame { callbackFrame ->
            observed = callbackFrame
            assertEquals(80, callbackFrame.columns)
            assertEquals(24, callbackFrame.rows)
        }

        assertSame(frame, observed)
    }

    @Test
    fun `default overscan read delegates to scrollback read`() {
        val frame = RecordingFrame(columns = 80, rows = 24)
        var requestedOffset = -1
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(frame)
                }

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    requestedOffset = scrollbackOffset
                    consumer.accept(frame)
                }
            }

        reader.readRenderFrame(scrollbackOffset = 7, viewportRows = 25) {
            assertSame(frame, it)
        }

        assertEquals(7, requestedOffset)
    }

    @Test
    fun `default absolute range read resolves relative viewport from frame metadata`() {
        val liveFrame = RecordingFrame(columns = 80, rows = 3, historySize = 5)
        val selectedFrame = RecordingFrame(columns = 80, rows = 6, historySize = 5, scrollbackOffset = 3)
        var requestedOffset = -1
        var requestedRows = -1
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(liveFrame)
                }

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    viewportRows: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    requestedOffset = scrollbackOffset
                    requestedRows = viewportRows
                    consumer.accept(selectedFrame)
                }
            }

        var observed: TerminalRenderFrame? = null
        reader.readRenderFrameForAbsoluteRange(startAbsoluteRow = 2L, endAbsoluteRow = 7L) {
            observed = it
        }

        assertEquals(3, requestedOffset)
        assertEquals(6, requestedRows)
        assertSame(selectedFrame, observed)
    }

    private open class RecordingFrame(
        override val columns: Int,
        override val rows: Int,
        override val historySize: Int = 0,
        override val scrollbackOffset: Int = 0,
        override val discardedCount: Long = 0L,
    ) : TerminalRenderFrame {
        override val frameGeneration: Long = 0L
        override val structureGeneration: Long = 0L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = true,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0L,
            )

        override fun lineGeneration(row: Int): Long = 0L

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
        ): Unit = throw UnsupportedOperationException("not needed for callback contract test")
    }
}
