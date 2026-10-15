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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalLine
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

class TerminalClusterCopyContractTest {
    @Test
    fun `cluster replacement cleans both old and intersecting wide spans`() {
        val buffer = TerminalBuffers.create(5, 1, 0)
        val wide = intArrayOf(0x2764, 0xFE0F)
        val narrow = intArrayOf('e'.code, 0x0301)
        buffer.writeCluster(wide)
        buffer.positionCursor(0, 0)
        buffer.writeCluster(narrow)
        assertEquals('e'.code, buffer.getLine(0).getCodepoint(0))
        assertEquals(0, buffer.getLine(0).getCodepoint(1))

        buffer.positionCursor(1, 0)
        buffer.writeCluster(wide)
        buffer.positionCursor(0, 0)
        buffer.writeCluster(wide)
        assertEquals(0x2764, buffer.getLine(0).getCodepoint(0))
        assertEquals(-1, buffer.getLine(0).getCodepoint(1))
        assertEquals(0, buffer.getLine(0).getCodepoint(2))

        buffer.positionCursor(1, 0)
        buffer.writeCluster(narrow)
        assertEquals(0, buffer.getLine(0).getCodepoint(0))
        val copied = IntArray(2)
        assertEquals(2, buffer.getLine(0).readCluster(1, copied))
        assertContentEquals(narrow, copied)
        assertEquals(0, buffer.getLine(0).getCodepoint(2))
    }

    @Test
    fun `the limit applies to the used prefix rather than source array capacity`() {
        val buffer = TerminalBuffers.create(2, 1, 0)
        val source = IntArray(33) { if (it == 0) 'e'.code else 0x0301 }
        source[32] = -1
        buffer.writeCluster(source, 32)
        buffer.updatePreviousCluster(source, 32)
        val copy = IntArray(32)
        assertEquals(32, buffer.getLine(0).readCluster(0, copy))
        assertContentEquals(source.copyOf(32), copy)
    }

    @Test
    fun `reflow preserves distinct clusters with equal row local references across wrapped rows`() {
        val buffer = TerminalBuffers.create(2, 3, 10)
        val clusters = List(6) { index -> IntArray(2) { if (it == 0) 'a'.code + index else 0x0301 } }
        clusters.forEach { buffer.writeCluster(it) }
        buffer.resize(6, 3)
        val copied = IntArray(32)
        for (column in clusters.indices) {
            val expected = clusters[column]
            assertEquals(expected.size, buffer.getLine(0).readCluster(column, copied))
            assertContentEquals(expected, copied.copyOf(expected.size))
        }
        buffer.resize(2, 3)
        for (index in clusters.indices) {
            val expected = clusters[index]
            assertEquals(expected.size, buffer.getLine(index / 2).readCluster(index % 2, copied))
            assertContentEquals(expected, copied.copyOf(expected.size))
        }
    }

    @Test
    fun `reflow grows a long wrapped logical line after repeated cluster compaction`() {
        val buffer = TerminalBuffers.create(3, 15, 0)
        val clusters = List(45) { index -> IntArray(32) { if (it == 0) 'a'.code + index % 26 else 0x0300 + index } }
        clusters.forEach { buffer.writeCluster(it) }
        repeat(64) { round ->
            val column = round % 3
            for (row in 0 until 15) {
                buffer.positionCursor(column, row)
                buffer.writeCodepoint('X'.code)
                buffer.positionCursor(column, row)
                buffer.writeCluster(clusters[row * 3 + column])
            }
        }
        buffer.readRenderFrame { frame ->
            for (row in 0 until 15) assertEquals(row < 14, frame.lineWrapped(row))
        }

        // Forty-five cells exceed the builder's initial oldWidth * 10 capacity.
        val copied = IntArray(32)
        for (width in intArrayOf(9, 3, 9, 3)) {
            buffer.resize(width, 15)
            assertEquals(0, buffer.historySize)
            assertEquals(43 / width, buffer.cursorRow)
            assertEquals(43 % width, buffer.cursorCol)
            for (index in clusters.indices) {
                val line = buffer.getLine(index / width)
                assertEquals(32, line.getClusterLength(index % width), "width=$width index=$index")
                assertEquals(32, line.readCluster(index % width, copied))
                assertContentEquals(clusters[index], copied, "width=$width index=$index")
            }
        }
    }

    @Test
    fun `reflow recycles destination rows under eviction and preserves surviving cluster attributes`() {
        val buffer = TerminalBuffers.create(6, 2, 2)
        val clusters = List(18) { index -> IntArray(32) { if (it == 0) 'a'.code + index else 0x0300 + index } }
        for ((index, cluster) in clusters.withIndex()) {
            buffer.setPenAttributes(fg = index + 1, bg = 0, bold = index % 2 == 0, underlineColor = index + 2)
            buffer.setHyperlinkId(index + 1)
            buffer.writeCluster(cluster)
        }
        val survivingClusters = clusters.takeLast(8)
        val survivingAttributes = List(8) { index -> assertNotNull(buffer.getAttrAt((index + 10) % 6, (index + 10) / 6 - 1)) }

        fun assertRetainedClusters() {
            val observed = mutableListOf<IntArray>()
            val codes = IntArray(buffer.width)
            val flags = IntArray(buffer.width)
            buffer.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
                assertEquals(8, frame.rows * frame.columns)
                for (row in 0 until frame.rows) {
                    frame.copyTextLine(row, codes, flags = flags, clusterDataSink = { _, data, offset, length ->
                        observed += data.copyOfRange(offset, offset + length)
                    })
                    for (column in 0 until frame.columns) assertTrue(flags[column] and TerminalRenderCellFlags.CLUSTER != 0)
                }
            }
            assertEquals(survivingClusters.size, observed.size)
            for (index in survivingClusters.indices) assertContentEquals(survivingClusters[index], observed[index])
        }

        // Nine emitted rows exceed the four-row destination ring.
        buffer.resize(2, 2)
        assertEquals(2, buffer.historySize)
        assertRetainedClusters()
        repeat(3) {
            buffer.resize(8, 1)
            assertEquals(0, buffer.historySize)
            assertRetainedClusters()
            for (column in 0 until 8) assertEquals(survivingAttributes[column], buffer.getAttrAt(column, 0))

            buffer.resize(2, 2)
            assertEquals(2, buffer.historySize)
            assertRetainedClusters()
            for (row in 0 until 2) {
                for (column in 0 until 2) assertEquals(survivingAttributes[4 + row * 2 + column], buffer.getAttrAt(column, row))
            }
        }
    }

    @Test
    fun `primary clusters retain row provenance when resized while alternate screen is active`() {
        val buffer = TerminalBuffers.create(2, 3, 10)
        val clusters = List(6) { index -> IntArray(32) { if (it == 0) 'a'.code + index else 0x0300 + index } }
        for ((index, cluster) in clusters.withIndex()) {
            buffer.setHyperlinkId(index + 1)
            buffer.writeCluster(cluster)
        }
        val attributes = List(6) { index -> assertNotNull(buffer.getAttrAt(index % 2, index / 2)) }
        buffer.enterAltBuffer()
        buffer.writeCluster(intArrayOf('z'.code, 0x0301))

        buffer.resize(6, 3)

        assertTrue(buffer.isAlternateScreenActive)
        assertEquals(0, buffer.historySize)
        for (row in 0 until 3) {
            for (column in 0 until 6) assertEquals(0, buffer.getLine(row).getCodepoint(column))
        }
        buffer.exitAltBuffer()
        assertFalse(buffer.isAlternateScreenActive)
        val copied = IntArray(32)
        for (column in clusters.indices) {
            assertEquals(32, buffer.getLine(0).readCluster(column, copied))
            assertContentEquals(clusters[column], copied)
            assertEquals(attributes[column], buffer.getAttrAt(column, 0))
        }
        for (row in 1 until 3) {
            for (column in 0 until 6) assertEquals(0, buffer.getLine(row).getCodepoint(column))
        }
    }

    @Test
    fun `oversized direct clusters fail before touching content attributes cursor or pending wrap`() {
        for (update in listOf(false, true)) {
            for (length in intArrayOf(33, 1024, 4097)) {
                val buffer = TerminalBuffers.create(2, 2, 0)
                val tooLong = IntArray(length) { if (it == 0) 'e'.code else 0x0301 }
                // Validation is required even when update has no remembered target.
                assertFailsWith<IllegalArgumentException> { buffer.updatePreviousCluster(tooLong) }
                buffer.writeCluster(intArrayOf(0x2764, 0xFE0F))
                val before = buffer.getScreenAsString()
                val row = buffer.cursorRow
                val col = buffer.cursorCol
                assertFailsWith<IllegalArgumentException> {
                    if (update) buffer.updatePreviousCluster(tooLong) else buffer.writeCluster(tooLong)
                }
                assertEquals(before, buffer.getScreenAsString())
                assertEquals(row, buffer.cursorRow)
                assertEquals(col, buffer.cursorCol)
                buffer.writeCodepoint('X'.code)
                assertEquals(1, buffer.cursorRow, "rejected writes must preserve pending wrap")
                assertEquals('X'.code, buffer.getLine(1).getCodepoint(0))
            }
        }
    }

    @Test
    fun `non cluster cells require no capacity and leave destinations untouched`() {
        val buffer = TerminalBuffers.create(4, 1, 0)
        buffer.writeCodepoint('A'.code)
        buffer.writeCodepoint(0x1F600)
        val line = buffer.getLine(0)
        val scratch = intArrayOf(123, 456)

        assertEquals(-1, line.getCodepoint(2))
        for (column in 0 until line.width) {
            assertEquals(0, line.getClusterLength(column))
            assertEquals(0, line.readCluster(column, scratch))
            assertEquals(0, line.readCluster(column, IntArray(0)))
        }
        assertContentEquals(intArrayOf(123, 456), scratch)
        for (column in intArrayOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            assertEquals(0, buffer.getLine(-1).getClusterLength(column))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 16, 31, 32])
    fun `capacity discovery enables exact complete copies and rejects incomplete destinations`(length: Int) {
        val buffer = TerminalBuffers.create(2, 1, 0)
        val expected = IntArray(length) { if (it == 0) 'e'.code else 0x0301 }
        buffer.writeCluster(expected, length)
        val line = buffer.getLine(0)

        assertEquals(length, line.getClusterLength(0))
        val exact = IntArray(line.getClusterLength(0))
        assertEquals(length, line.readCluster(0, exact))
        assertContentEquals(expected, exact)
        for (capacity in intArrayOf(0, length - 1)) {
            val tooSmall = IntArray(capacity) { 123 }
            assertFailsWith<IndexOutOfBoundsException> { line.readCluster(0, tooSmall) }
            assertContentEquals(IntArray(capacity) { 123 }, tooSmall)
            assertEquals(length, line.getClusterLength(0))
        }
        val larger = IntArray(length + 2) { 456 }
        assertEquals(length, line.readCluster(0, larger))
        assertContentEquals(expected, larger.copyOf(length))
        assertEquals(456, larger[length])
        assertEquals(456, larger[length + 1])
    }

    @Test
    fun `length counts only stored codepoints rather than cells UTF16 units or source capacity`() {
        val buffer = TerminalBuffers.create(4, 1, 0)
        val source = intArrayOf(0x1F468, 0x200D, 0x1F469, 'X'.code, 'Y'.code)
        buffer.writeCluster(source, 3)
        val line = buffer.getLine(0)

        assertEquals(3, line.getClusterLength(0))
        assertEquals(-1, line.getCodepoint(1))
        assertEquals(0, line.getClusterLength(1))
        val copied = IntArray(line.getClusterLength(0))
        assertEquals(3, line.readCluster(0, copied))
        assertContentEquals(intArrayOf(0x1F468, 0x200D, 0x1F469), copied)
    }

    @Test
    fun `scratch grows only when required and is reused across serialized reads and mutations`() {
        val buffer = TerminalBuffers.create(2, 1, 0)
        val serialization = Any()
        var scratch = IntArray(16)
        for (length in intArrayOf(17, 3, 4, 32, 2)) {
            val expected = IntArray(length) { if (it == 0) 'e'.code else 0x0301 }
            synchronized(serialization) {
                buffer.reset()
                buffer.writeCluster(expected, length)
                val line = buffer.getLine(0)
                val previous = scratch
                val required = line.getClusterLength(0)
                if (required > scratch.size) scratch = IntArray(required)
                assertEquals(length, line.readCluster(0, scratch))
                assertContentEquals(expected, scratch.copyOf(required))
                if (required <= previous.size) assertSame(previous, scratch)
            }
        }
        synchronized(serialization) {
            buffer.eraseBuffer()
            assertEquals(0, buffer.getLine(0).getClusterLength(0))
            buffer.writeCodepoint('A'.code)
            assertEquals(0, buffer.getLine(0).getClusterLength(0))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -1, 2, Int.MAX_VALUE])
    fun `physical line capacity queries reject invalid columns`(column: Int) {
        val line = TerminalBuffers.create(2, 1, 0).getLine(0)
        assertFailsWith<IndexOutOfBoundsException> { line.getClusterLength(column) }
    }

    @Test
    fun `scalar only implementations inherit capacity discovery`() {
        val line =
            object : TerminalLine {
                override val width = 1

                override fun getCodepoint(col: Int): Int = 'A'.code
            }
        assertEquals(0, line.getClusterLength(0))
        assertEquals(0, line.readCluster(0, IntArray(0)))
    }

    @Test
    fun `clustered implementations without capacity discovery fail explicitly`() {
        val line =
            object : TerminalLine {
                override val width = 1

                override fun getCodepoint(col: Int): Int = 'e'.code

                override fun isCluster(col: Int): Boolean = true

                override fun readCluster(
                    col: Int,
                    dest: IntArray,
                ): Int {
                    dest[0] = 'e'.code
                    dest[1] = 0x0301
                    return 2
                }
            }
        assertFailsWith<UnsupportedOperationException> { line.getClusterLength(0) }
        val copied = IntArray(2)
        assertEquals(2, line.readCluster(0, copied))
        assertContentEquals(intArrayOf('e'.code, 0x0301), copied)
    }

    @Test
    fun `frame sink supplies complete directly written clusters without a capacity guess`() {
        val buffer = TerminalBuffers.create(2, 1, 0)
        for (length in intArrayOf(2, 17, 32)) {
            val expected = IntArray(length) { if (it == 0) 'e'.code else 0x0301 }
            buffer.reset()
            buffer.writeCluster(expected, expected.size)
            var copied: IntArray? = null
            val codes = IntArray(2)
            val attributes = LongArray(2)
            val flags = IntArray(2)
            buffer.readRenderFrame { frame ->
                frame.copyLine(0, codes, attrWords = attributes, flags = flags, clusterDataSink = { col, data, offset, size ->
                    assertEquals(0, col)
                    // The sink receives the truthful length before allocating its owned copy.
                    copied = data.copyOfRange(offset, offset + size)
                })
            }
            assertContentEquals(expected, assertNotNull(copied))
            assertTrue(flags[0] and TerminalRenderCellFlags.CLUSTER != 0)
            assertEquals('e'.code, buffer.getLine(0).getCodepoint(0))
            assertEquals(0, buffer.getLine(0).readCluster(1, IntArray(0)))
        }
    }
}
