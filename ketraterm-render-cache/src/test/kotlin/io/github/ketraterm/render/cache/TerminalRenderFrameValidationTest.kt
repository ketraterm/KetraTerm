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

class TerminalRenderFrameValidationTest {
    @Test
    fun `invalid frame headers fail before row access and leave reusable caches invalid`() {
        val invalidHeaders: List<(Frame) -> Unit> =
            listOf(
                { it.columns = 0 },
                { it.columns = -1 },
                { it.columns = Int.MIN_VALUE },
                { it.rows = 0 },
                { it.rows = -1 },
                { it.rows = Int.MIN_VALUE },
                { it.historySize = -1 },
                { it.scrollbackOffset = -1 },
                { it.scrollbackOffset = 1 },
                { it.discardedCount = -1L },
                { it.discardedCount = Long.MAX_VALUE },
            )
        for ((index, invalidate) in invalidHeaders.withIndex()) {
            val frame = Frame()
            val reader = Reader(frame)
            val cache = TerminalRenderCache(2, 2, rowCapacityReserve = 3)
            val renderRange = TerminalRenderRangeCopy()
            val textRange = TerminalTextRangeCopy()
            cache.updateFrom(reader)
            assertTrue(renderRange.read(reader, 0, 1))
            assertTrue(textRange.read(reader, 0, 1))
            val codes = cache.codeWords
            val copied = frame.rowAccesses
            invalidate(frame)
            assertThrows(IllegalArgumentException::class.java, { cache.accept(frame) }, "header=$index")
            assertFalse(cache.hasFrame)
            assertEquals(2, cache.columns)
            assertEquals(2, cache.rows)
            assertSame(codes, cache.codeWords)
            assertThrows(IllegalArgumentException::class.java, { renderRange.read(reader, 0, 1) }, "header=$index")
            assertFalse(renderRange.cache.hasFrame)
            assertThrows(IllegalArgumentException::class.java, { textRange.read(reader, 0, 1) }, "header=$index")
            assertEquals(0, textRange.rows)
            assertThrows(IllegalArgumentException::class.java, { TerminalRenderRowSnapshot.copyOf(frame, 0) }, "header=$index")
            assertEquals(copied, frame.rowAccesses, "Invalid geometry must not reach row callbacks: header=$index")

            frame.columns = 2
            frame.rows = 2
            frame.historySize = 0
            frame.scrollbackOffset = 0
            frame.discardedCount = 0
            frame.scalar = 'b'.code
            cache.accept(frame)
            assertTrue(cache.hasFrame)
            assertEquals('b'.code, cache.codeWords[0], "Retry with unchanged generations must copy rows")
            assertTrue(renderRange.read(reader, 0, 1))
            assertTrue(textRange.read(reader, 0, 1))
            assertEquals('b'.code, textRange.codeWords[0])
        }
    }

    @Test
    fun `reader failure after copying exposes no usable frame and retry recopies unchanged generations`() {
        for (operation in 0..3) {
            val frame = Frame()
            val reader = Reader(frame)
            val cache = TerminalRenderCache(2, 2)
            val range = TerminalRenderRangeCopy()

            fun read() =
                when (operation) {
                    0 -> cache.updateFrom(reader)
                    1 -> cache.updateFrom(reader, 0, 2)
                    2 -> cache.updateFromAbsoluteRange(reader, 0, 1)
                    else -> {
                        range.read(reader, 0, 1)
                        Unit
                    }
                }
            read()
            reader.failAfterCopy = true
            assertThrows(IllegalStateException::class.java) { read() }
            assertFalse(if (operation == 3) range.cache.hasFrame else cache.hasFrame)
            reader.failAfterCopy = false
            frame.scalar = 'b'.code
            read()
            val result = if (operation == 3) range.cache else cache
            assertTrue(result.hasFrame)
            assertEquals('b'.code, result.codeWords[0])
        }
    }

    @Test
    fun `public coordinates cannot alias other rows or unused reserved storage`() {
        val frame = Frame().apply { clusters = true }
        val cache = TerminalRenderCache(2, 2, rowCapacityReserve = 4)
        cache.accept(frame)
        assertEquals("e\u0301", cache.clusterText(0, 0))
        assertEquals("e\u0301", cache.clusterText(1, 0))
        assertNull(cache.clusterText(1, 1))
        assertEquals(0, cache.rowOffset(0))
        assertEquals(2, cache.rowOffset(1))
        for ((row, column) in listOf(
            0 to -1,
            0 to Int.MIN_VALUE,
            0 to 2,
            1 to -2,
            -1 to 2,
            2 to 0,
            Int.MAX_VALUE to 0,
            0 to Int.MAX_VALUE,
        )) {
            assertThrows(IllegalArgumentException::class.java, { cache.clusterText(row, column) }, "row=$row column=$column")
        }
        for (row in listOf(Int.MIN_VALUE, -1, 2, 5, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { cache.rowOffset(row) }
        }
        assertEquals("e\u0301", cache.clusterText(0, 0))
    }

    @Test
    fun `absolute row boundary remains valid at Long maximum`() {
        val frame =
            Frame().apply {
                rows = 1
                discardedCount = Long.MAX_VALUE
            }
        val reader = Reader(frame)
        val render = TerminalRenderRangeCopy()
        val text = TerminalTextRangeCopy()
        assertTrue(render.read(reader, Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(text.read(reader, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, render.firstAbsoluteRow)
        assertEquals(Long.MAX_VALUE, render.lastAbsoluteRow)
        assertEquals(Long.MAX_VALUE, text.lastAbsoluteRow)
        frame.rows = 2
        frame.historySize = 1
        frame.scrollbackOffset = 1
        frame.discardedCount = Long.MAX_VALUE - 1
        assertTrue(render.read(reader, Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertTrue(text.read(reader, Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(2, render.cache.rows)
        assertEquals(2, text.rows)
        assertEquals(Long.MAX_VALUE, text.lastAbsoluteRow)
    }

    @Test
    fun `unrepresentable full-frame storage is rejected while a bounded virtual range remains usable`() {
        val frame = Frame().apply { rows = Int.MAX_VALUE }
        val cache = TerminalRenderCache(2, 2, rowCapacityReserve = 3)
        val codes = cache.codeWords
        assertThrows(IllegalArgumentException::class.java) { cache.accept(frame) }
        assertFalse(cache.hasFrame)
        assertSame(codes, cache.codeWords)
        assertEquals(2, cache.rows)
        assertEquals(0, frame.rowAccesses)
        val render = TerminalRenderRangeCopy(maxRows = 1)
        val text = TerminalTextRangeCopy(maxRows = 1)
        assertTrue(render.read(Reader(frame), 0, 0))
        assertTrue(text.read(Reader(frame), 0, 0))
        assertEquals(1, render.cache.rows)
        assertEquals(1, text.rows)
    }

    @Test
    fun `publisher retains its last completed frame after invalid geometry`() {
        val frame = Frame()
        val reader = Reader(frame)
        val publisher = TerminalRenderPublisher(2, 2)
        publisher.updateAndPublish(reader)
        frame.rows = 0
        assertThrows(IllegalArgumentException::class.java) { publisher.updateAndPublish(reader) }
        assertEquals('a'.code, publisher.readCurrent { it.codeWords[0] })
        frame.rows = 2
        frame.scalar = 'b'.code
        publisher.updateAndPublish(reader)
        assertEquals('b'.code, publisher.readCurrent { it.codeWords[0] })
    }

    private class Reader(
        private val frame: Frame,
    ) : TerminalRenderFrameReader {
        var failAfterCopy = false

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(frame)
            check(!failAfterCopy) { "Reader failed after its callback" }
        }

        override fun readRenderFrameForAbsoluteRange(
            startAbsoluteRow: Long,
            endAbsoluteRow: Long,
            consumer: TerminalRenderFrameConsumer,
        ) = readRenderFrame(consumer)
    }

    private class Frame : TerminalRenderFrame {
        override var columns = 2
        override var rows = 2
        override var historySize = 0
        override var scrollbackOffset = 0
        override var discardedCount = 0L
        override val frameGeneration = 1L
        override val structureGeneration = 1L
        override val activeBuffer = TerminalRenderBufferKind.PRIMARY
        override val cursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )
        var scalar = 'a'.code
        var clusters = false
        var rowAccesses = 0

        override fun lineGeneration(row: Int): Long {
            rowAccesses++
            return 1
        }

        override fun lineWrapped(row: Int): Boolean {
            rowAccesses++
            return false
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
            rowAccesses++
            codeWords.fill(scalar, codeOffset, codeOffset + columns)
            flags.fill(TerminalRenderCellFlags.CODEPOINT, flagOffset, flagOffset + columns)
            if (clusters) {
                codeWords[codeOffset] = 0
                flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
                clusterDataSink?.onCluster(0, intArrayOf('e'.code, 0x301), 0, 2)
            }
        }
    }
}
