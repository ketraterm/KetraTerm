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
package io.github.ketraterm.render.cache

import io.github.ketraterm.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalTextRangeCopyTest {
    @Test
    fun `text batches copy only bounded rows and preserve source metadata`() {
        val frame = TextFrame(160, 200)
        val copy = TerminalTextRangeCopy()
        assertTrue(copy.read(frame.reader, 0, 199))
        assertEquals(25, copy.rows)
        assertEquals(25, frame.copies)
        assertEquals(160, copy.columns)
        assertEquals(0L, copy.firstAbsoluteRow)
        assertEquals(24L, copy.lastAbsoluteRow)
        assertEquals(100L, copy.lineIds[0])
        assertEquals(224L, copy.lineGenerations[24])
        assertTrue(copy.lineWrapped[0])
        assertEquals(TerminalRenderBufferKind.ALTERNATE, copy.activeBuffer)
        assertEquals(11L, copy.contentGeneration)
        assertEquals(12L, copy.historyContentGeneration)
        assertEquals('a'.code, copy.codeWords[3999])
        assertTrue(copy.read(frame.reader, 25, 199))
        assertEquals(125L, copy.lineIds[0])
        assertEquals(49L, copy.lastAbsoluteRow)
    }

    @Test
    fun `reuse overwrites active cells without clearing spare capacity or leaking clusters`() {
        val frame = TextFrame(4, 3)
        frame.cluster = intArrayOf('e'.code, 0x301)
        val copy = TerminalTextRangeCopy()
        copy.read(frame.reader, 0, 2)
        val codes = copy.codeWords
        val flags = copy.flags
        val refs = copy.clusterRefs
        val payload = copy.clusterCodepoints
        val ids = copy.lineIds
        assertArrayEquals(intArrayOf('e'.code, 0x301), payload.copyOfRange(0, 2))
        assertEquals(2L, refs[0])
        val spareCode = codes[8]
        val spareRef = refs[8]

        val replacement = TextFrame(4, 1)
        replacement.scalar = 'b'.code
        assertTrue(copy.read(replacement.reader, 0, 0))
        assertEquals(1, copy.rows)
        assertArrayEquals(IntArray(4) { 'b'.code }, copy.codeWords.copyOf(4))
        assertArrayEquals(IntArray(4) { TerminalRenderCellFlags.CODEPOINT }, copy.flags.copyOf(4))
        assertEquals(spareCode, codes[8], "Unused rows must not be cleared")
        assertEquals(spareRef, refs[8], "Unused cluster references must not be cleared")
        assertSame(codes, copy.codeWords)
        assertSame(flags, copy.flags)
        assertSame(refs, copy.clusterRefs)
        assertSame(payload, copy.clusterCodepoints)
        assertSame(ids, copy.lineIds)

        replacement.cluster = intArrayOf(0x1F469, 0x200D, 0x1F4BB)
        copy.read(replacement.reader, 0, 0)
        assertEquals(3L, copy.clusterRefs[0])
        assertArrayEquals(replacement.cluster, copy.clusterCodepoints.copyOf(3))
        replacement.emitCluster = false
        copy.read(replacement.reader, 0, 0)
        assertEquals(TerminalRenderCellFlags.CLUSTER, copy.flags[0])
        assertEquals(0L, copy.clusterRefs[0], "A missing callback must not reuse an earlier payload")

        replacement.columns = 2
        replacement.cluster = null
        copy.read(replacement.reader, 0, 0)
        assertEquals(2, copy.columns)
        assertSame(codes, copy.codeWords)
        assertArrayEquals(intArrayOf('b'.code, 'b'.code), codes.copyOf(2))
    }

    @Test
    fun `failed cancelled and empty reads expose no partial rows and can be retried`() {
        val frame = TextFrame(4, 3)
        val copy = TerminalTextRangeCopy()
        copy.read(frame.reader, 0, 2)
        frame.failAtRow = 1
        assertThrows(IllegalStateException::class.java) { copy.read(frame.reader, 0, 2) }
        assertEquals(0, copy.rows)
        frame.failAtRow = -1
        val failingReader =
            object : TerminalRenderFrameReader by frame.reader {
                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    consumer.accept(frame)
                    error("failure after copying")
                }
            }
        assertThrows(IllegalStateException::class.java) { copy.read(failingReader, 0, 2) }
        assertEquals(0, copy.rows)
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            copy.read(frame.reader, 0, 2) { if (++checks == 3) throw java.util.concurrent.CancellationException() }
        }
        assertEquals(0, copy.rows)
        assertTrue(copy.read(frame.reader, 0, 2))
        assertEquals(3, copy.rows)
        assertFalse(copy.read(frame.reader, 10, 20))
        assertEquals(0, copy.rows)
        assertTrue(copy.lastAbsoluteRow < copy.firstAbsoluteRow)
    }

    @Test
    fun `eviction clips range and a row wider than the budget still progresses`() {
        val frame = TextFrame(9, 3)
        frame.discardedCount = 10L
        val copy = TerminalTextRangeCopy(maxRows = 2, maxCells = 4)
        assertTrue(copy.read(frame.reader, 8, 12))
        assertEquals(10L, copy.firstAbsoluteRow)
        assertEquals(10L, copy.lastAbsoluteRow)
        assertEquals(1, copy.rows)
        assertTrue(copy.read(frame.reader, 11, 12))
        assertEquals(101L, copy.lineIds[0])
        assertThrows(IllegalArgumentException::class.java) { copy.read(frame.reader, -1, 12) }
        assertEquals(0, copy.rows)
        assertThrows(IllegalArgumentException::class.java) { copy.read(frame.reader, 12, 11) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTextRangeCopy(maxRows = 0) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTextRangeCopy(maxCells = 0) }
    }

    @Test
    fun `external oversized clusters use the same retained prefix as rendering`() {
        val frame = TextFrame(2, 1)
        frame.cluster = IntArray(300) { 0x301 + it }
        val copy = TerminalTextRangeCopy()
        copy.read(frame.reader, 0, 0)
        assertEquals(256L, copy.clusterRefs[0])
        assertArrayEquals(IntArray(256) { 0x301 + it }, copy.clusterCodepoints.copyOf(256))
    }

    private class TextFrame(
        override var columns: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        override var discardedCount: Long = 0L
        override val frameGeneration: Long = 10L
        override val contentGeneration: Long = 11L
        override val historyContentGeneration: Long = 12L
        override val structureGeneration: Long = 13L
        override val activeBuffer = TerminalRenderBufferKind.ALTERNATE
        override val cursor: TerminalRenderCursor get() = error("Text copy must not request cursor state")
        override val palette: TerminalColorPalette get() = error("Text copy must not request palette state")
        var scalar = 'a'.code
        var cluster: IntArray? = null
        var emitCluster = true
        var copies = 0
        var failAtRow = -1
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = consumer.accept(this@TextFrame)

                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) = consumer.accept(this@TextFrame)
            }

        override fun lineId(row: Int): Long = 100L + row

        override fun lineGeneration(row: Int): Long = 200L + row

        override fun lineWrapped(row: Int): Boolean = row == 0

        override fun copyTextLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            check(row != failAtRow)
            copies++
            codeWords.fill(scalar, codeOffset, codeOffset + columns)
            flags.fill(TerminalRenderCellFlags.CODEPOINT, flagOffset, flagOffset + columns)
            cluster?.let { payload ->
                codeWords[codeOffset] = 0
                flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
                if (emitCluster) clusterDataSink?.onCluster(0, payload, 0, payload.size)
            }
        }

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
            error("Text copy must not request full render rows")
        }
    }
}
