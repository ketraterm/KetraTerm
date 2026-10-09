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
    @ValueSource(ints = [2, 33, 4097, 16385])
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
        for (length in intArrayOf(4097, 3, 4, 8193, 2)) {
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
        for (length in intArrayOf(33, 4097, 16385)) {
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
