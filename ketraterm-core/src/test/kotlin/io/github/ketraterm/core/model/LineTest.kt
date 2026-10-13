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
package io.github.ketraterm.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("Line Test Suite")
class LineTest {
    @ParameterizedTest
    @ValueSource(strings = ["scalar", "clear", "prefix", "suffix", "range", "insert", "delete", "fill"])
    fun `erasure and shifting preserve exactly the surviving cluster payloads`(operation: String) {
        val line = Line(8)
        val payloads = mapOf(0 to intArrayOf('a'.code, 0x0301), 3 to intArrayOf('b'.code, 0x0301), 7 to intArrayOf('c'.code, 0x0301))
        assertEquals(0, clusterCount(line))
        payloads.forEach { (col, cps) -> line.setCluster(col, cps, cps.size, 0L) }
        assertEquals(3, clusterCount(line))

        val survivors =
            when (operation) {
                "scalar" -> {
                    line.setCell(3, 'x'.code, 0L)
                    mapOf(0 to 0, 7 to 7)
                }
                "clear" -> {
                    line.clear(0L)
                    emptyMap()
                }
                "prefix" -> {
                    line.clearToColumn(3, 0L)
                    mapOf(7 to 7)
                }
                "suffix" -> {
                    line.clearFromColumn(3, 0L)
                    mapOf(0 to 0)
                }
                "range" -> {
                    line.clearRange(2, 5, 0L)
                    mapOf(0 to 0, 7 to 7)
                }
                "insert" -> {
                    line.insertCellsInRange(1, 5, 7, 0L)
                    mapOf(0 to 0)
                }
                "delete" -> {
                    line.deleteCellsInRange(1, 4, 7, 0L)
                    mapOf(0 to 0, 3 to 7)
                }
                "fill" -> {
                    line.fill('x'.code, 0L)
                    emptyMap()
                }
                else -> error(operation)
            }
        assertEquals(survivors.size, clusterCount(line))
        repeat(line.width) { col ->
            assertEquals(col in survivors, line.isCluster(col), "operation=$operation column=$col")
            val originalCol = survivors[col]
            if (originalCol != null) {
                val copied = IntArray(2)
                assertEquals(2, line.readCluster(col, copied))
                assertArrayEquals(payloads.getValue(originalCol), copied)
            }
        }
        line.clear(0L)
        assertEquals(0, clusterCount(line))
        assertEquals("", line.toTextTrimmed())
        line.setCluster(4, intArrayOf('z'.code, 0x0301), 2, 0L)
        assertEquals('z'.code, line.getCodepoint(4))
        assertEquals(1, clusterCount(line))
    }

    @Test
    fun `cluster replacement and edits outside clusters preserve exact accounting`() {
        val line = line(8)
        line.setCluster(3, intArrayOf('a'.code, 0x0301), 2, 0L)
        line.setCluster(3, intArrayOf('b'.code, 0x0301), 2, 0L)
        assertEquals(1, clusterCount(line))
        line.setCellAttributes(3, 5L, 7L)
        line.clearRange(0, 2, 0L)
        line.insertCellsInRange(2, 1, 6, 0L)
        assertEquals(1, clusterCount(line))
        assertTrue(line.isCluster(4))
        line.deleteCellsInRange(2, 1, 6, 0L)
        assertEquals(1, clusterCount(line))
        assertEquals('b'.code, line.getCodepoint(3))
        assertEquals(3, line.writeAsciiIntoSimpleCells(0, "abcdef".toByteArray(), 0, 6, 0L, 0L))
        assertEquals(1, clusterCount(line))
        line.clearRange(3, 4, 0L)
        assertEquals(0, clusterCount(line))
        line.setCell(6, '界'.code, 0L)
        line.setCell(7, TerminalConstants.WIDE_CHAR_SPACER, 0L)
        line.clear(0L)
        assertEquals(0, clusterCount(line))
    }

    @Test
    fun `copied clusters remain independent of source row clearing`() {
        val source = Line(4)
        val target = Line(4)
        val payload = intArrayOf('a'.code, 0x0301)
        source.setCluster(0, payload, 2, 0L)
        val copied = IntArray(2)
        target.setCluster(3, copied, source.readCluster(0, copied), 7L)
        source.clear(0L)
        assertEquals(2, target.readCluster(3, copied))
        assertArrayEquals(payload, copied)
        assertEquals(7L, target.getPackedAttr(3))
    }

    private fun clusterCount(line: Line): Int = (0 until line.width).count { line.isCluster(it) }

    @Test
    fun `clearing and reusing a row cannot affect another row`() {
        val line = Line(8)
        val other = Line(8)
        val payload = intArrayOf('e'.code, 0x0301)
        line.setCluster(1, payload, 2, 11L, 13L)
        other.setCluster(1, payload, 2, 17L, 19L)
        repeat(2) { line.clear(0L) }
        line.setCluster(5, intArrayOf('x'.code, 0x0301), 2, 23L, 29L)
        val copied = IntArray(2)
        assertEquals(2, other.readCluster(1, copied))
        assertArrayEquals(payload, copied)
        assertEquals(17L, other.getPackedAttr(1))
        assertEquals(19L, other.getPackedExtendedAttr(1))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["scalar", "attributes", "cluster", "ascii", "clear", "prefix", "suffix", "range", "insert", "delete", "fill"],
    )
    fun `extended storage is allocated only for nonzero writes and reused after clearing`(operation: String) {
        val line = line(6)
        var allocated: LongArray? = null
        for (value in longArrayOf(0L, 0x12345678ABCDEF01L, 0L)) {
            val background = if (allocated != null) 99L else 0L
            line.fill('a'.code, 0L, background)
            val affected =
                when (operation) {
                    "scalar" -> {
                        line.setCell(2, 'b'.code, 0L, value)
                        2..2
                    }
                    "attributes" -> {
                        line.setCellAttributes(2, 0L, value)
                        2..2
                    }
                    "cluster" -> {
                        line.setCluster(2, intArrayOf('e'.code, 0x0301), 2, 0L, value)
                        2..2
                    }
                    "ascii" -> {
                        assertEquals(3, line.writeAsciiIntoSimpleCells(1, "ABC".toByteArray(), 0, 3, 0L, value))
                        1..3
                    }
                    "clear" -> {
                        line.clear(0L, value)
                        0..5
                    }
                    "prefix" -> {
                        line.clearToColumn(2, 0L, value)
                        0..2
                    }
                    "suffix" -> {
                        line.clearFromColumn(2, 0L, value)
                        2..5
                    }
                    "range" -> {
                        line.clearRange(1, 4, 0L, value)
                        1..3
                    }
                    "insert" -> {
                        line.insertCellsInRange(1, 2, 4, 0L, value)
                        1..2
                    }
                    "delete" -> {
                        line.deleteCellsInRange(1, 2, 4, 0L, value)
                        3..4
                    }
                    "fill" -> {
                        line.fill('b'.code, 0L, value)
                        0..5
                    }
                    else -> error(operation)
                }
            repeat(6) { col ->
                val expected = if (col in affected) value else background
                assertEquals(
                    expected,
                    line.getPackedExtendedAttr(col),
                    "operation=$operation column=$col value=$value",
                )
            }
            if (value != 0L) {
                allocated = extendedStorage(line)
                assertNotNull(allocated)
            }
            assertSame(allocated, extendedStorage(line))
        }
    }

    @Test
    fun `extended words move with cells and plain overwrites remove old styling`() {
        val line = line(6)
        val original = longArrayOf(0L, 10L, 20L, 0L, 40L, 50L)
        original.forEachIndexed { col, value -> line.setCell(col, 'a'.code + col, 0L, value) }
        val storage = extendedStorage(line)

        line.insertCellsInRange(2, 2, 4, 0L, 7L)
        assertArrayEquals(longArrayOf(0L, 10L, 7L, 7L, 20L, 50L), LongArray(6) { line.getPackedExtendedAttr(it) })
        line.deleteCellsInRange(1, 2, 4, 0L)
        assertArrayEquals(longArrayOf(0L, 7L, 20L, 0L, 0L, 50L), LongArray(6) { line.getPackedExtendedAttr(it) })
        assertEquals(3, line.writeAsciiIntoSimpleCells(1, "ABC".toByteArray(), 0, 3, 0L, 0L))
        assertArrayEquals(longArrayOf(0L, 0L, 0L, 0L, 0L, 50L), LongArray(6) { line.getPackedExtendedAttr(it) })
        line.setCellAttributes(5, 0L, 0L)
        assertArrayEquals(LongArray(6), LongArray(6) { line.getPackedExtendedAttr(it) })
        assertSame(storage, extendedStorage(line))
    }

    @Test
    fun `empty edits and blocked ASCII spans do not allocate extended storage`() {
        val line = line(4)
        line.setCluster(0, intArrayOf('e'.code, 0x0301), 2, 0L)
        assertEquals(0, line.writeAsciiIntoSimpleCells(0, byteArrayOf(65), 0, 1, 0L, 7L))
        assertEquals(0, line.writeAsciiIntoSimpleCells(1, byteArrayOf(), 0, 0, 0L, 7L))
        line.clearRange(2, 2, 0L, 7L)
        line.clearFromColumn(4, 0L, 7L)
        line.clearToColumn(-1, 0L, 7L)
        line.insertCells(1, 0, 0L, 7L)
        line.deleteCells(1, 0, 0L, 7L)
        assertNull(extendedStorage(line))
        assertEquals("e\u0301", line.toTextTrimmed())
        repeat(4) { assertEquals(0L, line.getPackedExtendedAttr(it)) }
    }

    private fun extendedStorage(line: Line): LongArray? {
        val field = Line::class.java.getDeclaredField("extendedAttrs")
        field.isAccessible = true
        return field.get(line) as LongArray?
    }

    @ParameterizedTest
    @ValueSource(strings = ["clear", "prefix", "suffix", "range", "ascii", "insert", "delete", "fill"])
    fun `full zero extended overwrite preserves reusable storage through plain edits and reactivation`(operation: String) {
        val line = line(6)
        line.fill('a'.code, 7L, 9L)
        val storage = extendedStorage(line)
        when (operation) {
            "clear" -> line.clear(0L)
            "prefix" -> line.clearToColumn(5, 0L)
            "suffix" -> line.clearFromColumn(0, 0L)
            "range" -> line.clearRange(0, 6, 0L)
            "ascii" -> assertEquals(6, line.writeAsciiIntoSimpleCells(0, "ABCDEF".toByteArray(), 0, 6, 0L, 0L))
            "insert" -> line.insertCells(0, 6, 0L)
            "delete" -> line.deleteCells(0, 6, 0L)
            "fill" -> line.fill('b'.code, 0L)
            else -> error(operation)
        }
        assertArrayEquals(LongArray(6), LongArray(6) { line.getPackedExtendedAttr(it) })

        line.setCell(0, 'x'.code, 0L)
        line.setCell(1, 'y'.code, 0L)
        line.setCellAttributes(2, 0L, 0L)
        line.setCluster(3, intArrayOf('e'.code, 0x0301), 2, 0L)
        line.insertCells(0, 1, 0L)
        line.deleteCells(0, 1, 0L)
        line.clearRange(4, 6, 0L)
        assertArrayEquals(LongArray(6), LongArray(6) { line.getPackedExtendedAttr(it) })

        line.setCellAttributes(2, 3L, 11L)
        assertArrayEquals(longArrayOf(0L, 0L, 11L, 0L, 0L, 0L), LongArray(6) { line.getPackedExtendedAttr(it) })
        assertSame(storage, extendedStorage(line))
        assertEquals('e'.code, line.getCodepoint(3))
        line.clear(0L)
        line.setCell(5, 'z'.code, 0L, 13L)
        assertArrayEquals(longArrayOf(0L, 0L, 0L, 0L, 0L, 13L), LongArray(6) { line.getPackedExtendedAttr(it) })
        assertSame(storage, extendedStorage(line))
    }

    @Test
    fun `ASCII span overwrites narrow scalars and blanks with both attribute words`() {
        val line = line(5)
        line.setCell(0, 'a'.code, 1L, 2L)
        line.setCell(2, 'é'.code, 1L, 2L)
        line.setCell(4, 'z'.code, 1L, 2L)
        val bytes = byteArrayOf(0) + "ABCDE".toByteArray() + byteArrayOf(-1)

        assertEquals(5, line.writeAsciiIntoSimpleCells(0, bytes, 1, 5, 3L, 4L))

        assertEquals("ABCDE", line.toText())
        repeat(5) { col ->
            assertEquals(3L, line.getPackedAttr(col))
            assertEquals(4L, line.getPackedExtendedAttr(col))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["wide", "cluster", "wide cluster"])
    fun `ASCII span stops before a complex occupant even when its spacer is outside the write range`(occupant: String) {
        val line = line(5)
        val cluster = intArrayOf(0x1F469, 0x200D, 0x1F4BB)
        line.setCell(0, 'a'.code, 1L, 2L)
        if (occupant == "wide") {
            line.setCell(2, '界'.code, 1L, 2L)
        } else {
            line.setCluster(2, cluster, cluster.size, 1L, 2L)
        }
        if (occupant != "cluster") line.setCell(3, TerminalConstants.WIDE_CHAR_SPACER, 1L, 2L)
        val raw = line.rawCodepoint(2)
        val bytes = "XYZ".toByteArray()

        assertEquals(2, line.writeAsciiIntoSimpleCells(0, bytes, 0, 3, 3L, 4L))
        assertEquals('X'.code, line.rawCodepoint(0))
        assertEquals('Y'.code, line.rawCodepoint(1))
        assertEquals(raw, line.rawCodepoint(2))
        assertEquals(1L, line.getPackedAttr(2))
        assertEquals(2L, line.getPackedExtendedAttr(2))
        assertEquals(0, line.writeAsciiIntoSimpleCells(2, bytes, 0, 1, 3L, 4L))
        if (occupant != "cluster") {
            assertEquals(0, line.writeAsciiIntoSimpleCells(3, bytes, 0, 1, 3L, 4L))
            assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.rawCodepoint(3))
        }
        if (occupant != "wide") {
            val copy = IntArray(cluster.size)
            assertEquals(cluster.size, line.readCluster(2, copy))
            assertArrayEquals(cluster, copy)
        }
        line.clear(0L)
        assertEquals("", line.toTextTrimmed())
    }

    @Test
    fun `edits before trailing wrap padding preserve its provenance`() {
        val line = line(4)
        line.wrapped = true
        line.endsWithWrapPadding = true

        line.setCell(0, 'a'.code, 0)
        line.setCluster(1, intArrayOf('e'.code, 0x0301), 2, 0)
        line.setCell(2, 'c'.code, 0)
        line.clearToColumn(1, 0)
        line.clearRange(1, 3, 0)
        line.insertCellsInRange(0, 1, 2, 0)
        line.deleteCellsInRange(0, 1, 2, 0)
        line.setCellAttributes(3, 1, 2)

        assertTrue(line.endsWithWrapPadding)
        assertEquals(TerminalConstants.EMPTY, line.rawCodepoint(3))
    }

    @ParameterizedTest
    @ValueSource(strings = ["cell write", "clear prefix", "clear suffix", "clear range", "fill", "clear", "unwrap"])
    fun `replacing the final cell or removing its wrap clears padding provenance`(operation: String) {
        val line = line(4)
        line.wrapped = true
        line.endsWithWrapPadding = true

        when (operation) {
            "cell write" -> line.setCell(3, 'x'.code, 0)
            "clear prefix" -> line.clearToColumn(3, 0)
            "clear suffix" -> line.clearFromColumn(3, 0)
            "clear range" -> line.clearRange(2, 4, 0)
            "fill" -> line.fill(TerminalConstants.EMPTY, 0)
            "clear" -> line.clear(0)
            "unwrap" -> line.wrapped = false
        }

        assertFalse(line.endsWithWrapPadding)
    }

    private fun line(width: Int) = Line(width)

    // =========================================================================
    // Initialization
    // =========================================================================

    @Nested
    @DisplayName("Initialization")
    inner class InitializationTests {
        @ParameterizedTest(name = "Create line with width={0}")
        @ValueSource(ints = [1, 10, 80, 1000])
        fun `valid widths construct successfully`(width: Int) {
            val l = line(width)
            assertAll(
                { assertEquals(width, l.width) },
                { assertFalse(l.wrapped) },
            )
        }

        @Test
        fun `all cells start as EMPTY with zeroed attrs`() {
            val l = line(5)
            for (col in 0 until 5) {
                assertAll(
                    { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col), "codepoint at $col") },
                    { assertEquals(0L, l.getPackedAttr(col), "attr at $col") },
                )
            }
        }

        @ParameterizedTest(name = "Reject invalid width={0}")
        @ValueSource(ints = [0, -1, -100])
        fun `non-positive widths throw`(invalidWidth: Int) {
            assertThrows<IllegalArgumentException> { line(invalidWidth) }
        }
    }

    // =========================================================================
    // setCell / getCodepoint / getPackedAttr — plain cells
    // =========================================================================

    @Nested
    @DisplayName("setCell / getCodepoint / getPackedAttr")
    inner class CellOperationsTests {
        @ParameterizedTest(name = "set and get at col={0}")
        @CsvSource("0", "5", "9")
        fun `valid columns round-trip codepoint and attr`(col: Int) {
            val l = line(10)
            l.setCell(col, 'A'.code, 12345)
            assertAll(
                { assertEquals('A'.code, l.getCodepoint(col)) },
                { assertEquals(12345L, l.getPackedAttr(col)) },
            )
        }

        @Test
        fun `scalar overwrite replaces the cluster without changing neighboring cells`() {
            val l = Line(5)
            l.setCluster(2, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)
            l.setCell(2, 'X'.code, 0)
            assertFalse(l.isCluster(2))
            assertEquals('X'.code, l.getCodepoint(2))
            l.setCluster(3, intArrayOf(99, 100), 2, 0)
            assertEquals(99, l.getCodepoint(3))
            assertEquals('X'.code, l.getCodepoint(2))
        }
    }

    // =========================================================================
    // setCluster / getCodepoint / isCluster / readCluster
    // =========================================================================

    @Nested
    @DisplayName("setCluster / isCluster / readCluster")
    inner class ClusterCellTests {
        @Test
        fun `setCluster stores multi-codepoint cluster and isCluster returns true`() {
            val l = line(5)
            l.setCluster(1, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 42)

            assertTrue(l.isCluster(1))
            assertEquals(42L, l.getPackedAttr(1))
        }

        @Test
        fun `getCodepoint on cluster cell returns base codepoint`() {
            val l = line(5)
            l.setCluster(0, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)

            assertEquals(0x1F468, l.getCodepoint(0))
        }

        @Test
        fun `readCluster copies all codepoints into dest and returns length`() {
            val l = line(5)
            l.setCluster(2, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)
            val dest = IntArray(8)

            val written = l.readCluster(2, dest)

            assertAll(
                { assertEquals(3, written) },
                { assertEquals(0x1F468, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
                { assertEquals(0x1F469, dest[2]) },
            )
        }

        @Test
        fun `readCluster on plain cell returns 0 and writes nothing`() {
            val l = line(5)
            l.setCell(2, 'A'.code, 0)
            val dest = IntArray(8) { 999 }

            val written = l.readCluster(2, dest)

            assertAll(
                { assertEquals(0, written) },
                { assertEquals(999, dest[0], "dest must be untouched for plain cell") },
            )
        }

        @Test
        fun `isCluster returns false for plain cells EMPTY and SPACER`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, TerminalConstants.EMPTY, 0)
            l.setCell(2, TerminalConstants.WIDE_CHAR_SPACER, 0)

            assertAll(
                { assertFalse(l.isCluster(0)) },
                { assertFalse(l.isCluster(1)) },
                { assertFalse(l.isCluster(2)) },
            )
        }

        @Test
        fun `growing a cluster preserves its replacement and neighboring payloads`() {
            val l = Line(5)

            l.setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            val firstHandle = l.rawCodepoint(0)
            assertTrue(firstHandle <= TerminalConstants.CLUSTER_HANDLE_MAX)

            // Overwrite with a different cluster
            l.setCluster(0, intArrayOf(0xCCCC, 0xDDDD, 0xEEEE), 3, 0)

            // The larger replacement needs a different region.
            val secondHandle = l.rawCodepoint(0)
            assertNotEquals(firstHandle, secondHandle, "Length is encoded in the row reference")
            l.setCluster(1, intArrayOf(0x1111, 0x2222), 2, 0)

            // New data must be visible
            val dest = IntArray(4)
            val written = l.readCluster(0, dest)
            assertAll(
                { assertEquals(3, written) },
                { assertEquals(0xCCCC, dest[0]) },
                { assertEquals(0xDDDD, dest[1]) },
                { assertEquals(0xEEEE, dest[2]) },
            )
            assertEquals(2, l.readCluster(1, dest))
            assertArrayEquals(intArrayOf(0x1111, 0x2222), dest.copyOf(2))
        }

        @Test
        fun `reusable dest array across multiple readCluster calls produces correct results`() {
            val l = line(5)
            l.setCluster(0, intArrayOf(10, 20), 2, 0)
            l.setCluster(1, intArrayOf(30, 40, 50), 3, 0)
            val dest = IntArray(8)

            val w0 = l.readCluster(0, dest)
            assertEquals(2, w0)
            assertEquals(10, dest[0])
            assertEquals(20, dest[1])

            val w1 = l.readCluster(1, dest)
            assertEquals(3, w1)
            assertEquals(30, dest[0])
            assertEquals(40, dest[1])
            assertEquals(50, dest[2])
        }
    }

    // =========================================================================
    // rawCodepoint / setCell — internal accessors used by TerminalResizer
    // =========================================================================

    @Nested
    @DisplayName("rawCodepoint / setCell (internal reflow contract)")
    inner class RawAccessorTests {
        @Test
        fun `rawCodepoint returns stored Int without resolving cluster handle`() {
            val l = Line(5)
            l.setCluster(0, intArrayOf(0x1F468, 0x200D), 2, 0)

            val raw = l.rawCodepoint(0)
            assertTrue(
                raw <= TerminalConstants.CLUSTER_HANDLE_MAX,
                "rawCodepoint must return the negative handle, not the base codepoint",
            )
        }

        @Test
        fun `payload copies remain independent after source overwrite`() {
            val src = Line(5)
            val dst = Line(5)
            val expected = intArrayOf(0x1F469, 0x200D, 0x1F680)
            src.setCluster(0, expected, 3, 7)
            val scratch = IntArray(32)
            dst.setCluster(0, scratch, src.readCluster(0, scratch), src.getPackedAttr(0))
            src.setCluster(0, intArrayOf('x'.code, 0x0301), 2, 0)
            assertEquals(3, dst.readCluster(0, scratch))
            assertArrayEquals(expected, scratch.copyOf(3))
            assertEquals(7L, dst.getPackedAttr(0))
        }

        @Test
        fun `plain cell writes preserve attributes without a cluster reference`() {
            val l = line(5)
            l.setCell(3, 'Z'.code, 55)

            assertAll(
                { assertEquals('Z'.code, l.getCodepoint(3)) },
                { assertEquals(55L, l.getPackedAttr(3)) },
                { assertFalse(l.isCluster(3)) },
            )
        }
    }

    // =========================================================================
    // clear()
    // =========================================================================

    @Nested
    @DisplayName("clear()")
    inner class ClearTests {
        @Test
        fun `clear resets all cluster cells for reuse`() {
            val l = Line(3)
            l.setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            l.setCluster(1, intArrayOf(0xCCCC), 1, 0)
            l.clear(0)
            assertEquals("", l.toTextTrimmed())
            assertEquals(0, clusterCount(l))
            l.setCluster(2, intArrayOf(1, 2), 2, 0)
            assertEquals(1, l.getCodepoint(2))
        }
    }

    // =========================================================================
    // clearFromColumn()
    // =========================================================================

    @Nested
    @DisplayName("clearFromColumn()")
    inner class ClearFromColumnTests {
        @Test
        fun `clears from specified column to end leaving prefix intact`() {
            val l = line(10)
            for (col in 0 until 10) l.setCell(col, 'A'.code + col, col.toLong())

            l.clearFromColumn(5, 99)

            for (col in 0 until 5) {
                assertAll(
                    { assertEquals('A'.code + col, l.getCodepoint(col)) },
                    { assertEquals(col.toLong(), l.getPackedAttr(col)) },
                )
            }
            for (col in 5 until 10) {
                assertAll(
                    { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col)) },
                    { assertEquals(99L, l.getPackedAttr(col)) },
                )
            }
        }

        @Test
        fun `startCol 0 clears the entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearFromColumn(0, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `negative startCol is clamped to 0 and clears entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearFromColumn(-5, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `startCol beyond width is a no-op`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearFromColumn(10, 42)
            for (col in 0 until 5) assertEquals('X'.code, l.getCodepoint(col))
        }

        @Test
        fun `removes cluster cells in the cleared range`() {
            val l = Line(5)
            l.setCluster(3, intArrayOf(0x1F600, 0x200D), 2, 0)

            l.clearFromColumn(3, 0)

            assertEquals(0, clusterCount(l))
            assertEquals("", l.toTextTrimmed())
        }
    }

    // =========================================================================
    // clearToColumn()
    // =========================================================================

    @Nested
    @DisplayName("clearToColumn()")
    inner class ClearToColumnTests {
        @Test
        fun `clears from start through specified column leaving suffix intact`() {
            val l = line(10)
            for (col in 0 until 10) l.setCell(col, 'A'.code + col, col.toLong())

            l.clearToColumn(4, 99)

            for (col in 0..4) {
                assertAll(
                    { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col)) },
                    { assertEquals(99L, l.getPackedAttr(col)) },
                )
            }
            for (col in 5 until 10) {
                assertAll(
                    { assertEquals('A'.code + col, l.getCodepoint(col)) },
                    { assertEquals(col.toLong(), l.getPackedAttr(col)) },
                )
            }
        }

        @Test
        fun `endCol of width-1 clears the entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearToColumn(4, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `negative endCol is a no-op`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearToColumn(-1, 42)
            for (col in 0 until 5) assertEquals('X'.code, l.getCodepoint(col))
        }

        @Test
        fun `endCol beyond width clears entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearToColumn(100, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `removes cluster cells in the cleared range`() {
            val l = Line(5)
            l.setCluster(1, intArrayOf(0x1F600, 0x200D), 2, 0)

            l.clearToColumn(2, 0)

            assertEquals(0, clusterCount(l))
            assertEquals("", l.toTextTrimmed())
        }
    }

    // =========================================================================
    // fill()
    // =========================================================================

    @Nested
    @DisplayName("fill()")
    inner class FillTests {
        @Test
        fun `fills all cells with given codepoint and attr`() {
            val l = line(5)
            l.fill('-'.code, 99)
            for (col in 0 until 5) {
                assertAll(
                    { assertEquals('-'.code, l.getCodepoint(col)) },
                    { assertEquals(99L, l.getPackedAttr(col)) },
                )
            }
        }

        @Test
        fun `fill with EMPTY zeroes all codepoints`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'A'.code + col, col.toLong())
            l.fill(TerminalConstants.EMPTY, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `fill removes all cluster cells`() {
            val l = Line(4)
            l.setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            l.setCluster(2, intArrayOf(0xCCCC), 1, 0)
            l.fill('Z'.code, 0)
            assertEquals("ZZZZ", l.toText())
            assertEquals(0, clusterCount(l))
        }
    }

    // =========================================================================
    // insertCells()
    // =========================================================================

    @Nested
    @DisplayName("insertCells()")
    inner class InsertCellsTests {
        @Test
        fun `inserts blanks and shifts content right`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 1)
            l.setCell(1, 'B'.code, 2)
            l.setCell(2, 'C'.code, 3)

            l.insertCells(col = 1, count = 2, defaultAttr = 99)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals('B'.code, l.getCodepoint(3)) },
                { assertEquals('C'.code, l.getCodepoint(4)) },
                { assertEquals(99L, l.getPackedAttr(1)) },
                { assertEquals(99L, l.getPackedAttr(2)) },
            )
        }

        @Test
        fun `insertion discards clusters pushed off the right edge`() {
            val l = Line(3)
            l.setCluster(2, intArrayOf(0x1F600, 0x200D), 2, 0) // will fall off on insert at 0

            l.insertCells(col = 0, count = 1, defaultAttr = 0)

            // Cluster at col 2 was shifted off and freed
            assertEquals(0, clusterCount(l))
            assertEquals("", l.toTextTrimmed())
        }

        @Test
        fun `insert at col 0 shifts entire line right`() {
            val l = line(4)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, 'B'.code, 0)
            l.setCell(2, 'C'.code, 0)

            l.insertCells(col = 0, count = 1, defaultAttr = 0)

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(0)) },
                { assertEquals('A'.code, l.getCodepoint(1)) },
                { assertEquals('B'.code, l.getCodepoint(2)) },
                { assertEquals('C'.code, l.getCodepoint(3)) },
            )
        }

        @Test
        fun `insert count larger than remaining space clamps correctly`() {
            val l = line(4)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, 'B'.code, 0)

            // inserting 10 at col 1 on a width-4 line: only 3 free cells to the right
            l.insertCells(col = 1, count = 10, defaultAttr = 55)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
            )
        }

        @Test
        fun `no-op for negative col`() {
            val l = line(3)
            l.setCell(0, 'X'.code, 7)
            l.insertCells(col = -1, count = 1, defaultAttr = 9)
            assertEquals('X'.code, l.getCodepoint(0))
        }

        @Test
        fun `no-op for zero count`() {
            val l = line(3)
            l.setCell(0, 'X'.code, 7)
            l.insertCells(col = 0, count = 0, defaultAttr = 9)
            assertEquals('X'.code, l.getCodepoint(0))
        }
    }

    // =========================================================================
    // deleteCells()
    // =========================================================================

    @Nested
    @DisplayName("deleteCells()")
    inner class DeleteCellsTests {
        @Test
        fun `deletes middle range, shifts tail left, and fills trailing attrs`() {
            val l = line(6)
            l.setCell(0, 'A'.code, 10)
            l.setCell(1, 'B'.code, 11)
            l.setCell(2, 'C'.code, 12)
            l.setCell(3, 'D'.code, 13)
            l.setCell(4, 'E'.code, 14)
            l.setCell(5, 'F'.code, 15)

            l.deleteCells(col = 1, count = 2, defaultAttr = 99)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals('D'.code, l.getCodepoint(1)) },
                { assertEquals('E'.code, l.getCodepoint(2)) },
                { assertEquals('F'.code, l.getCodepoint(3)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(4)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(5)) },
                { assertEquals(13L, l.getPackedAttr(1)) },
                { assertEquals(14L, l.getPackedAttr(2)) },
                { assertEquals(15L, l.getPackedAttr(3)) },
                { assertEquals(99L, l.getPackedAttr(4)) },
                { assertEquals(99L, l.getPackedAttr(5)) },
            )
        }

        @Test
        fun `delete from col zero shifts whole line left`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 1)
            l.setCell(1, 'B'.code, 2)
            l.setCell(2, 'C'.code, 3)

            l.deleteCells(col = 0, count = 1, defaultAttr = 77)

            assertAll(
                { assertEquals('B'.code, l.getCodepoint(0)) },
                { assertEquals('C'.code, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(4)) },
                { assertEquals(77L, l.getPackedAttr(4)) },
            )
        }

        @Test
        fun `delete at last column only clears that cell`() {
            val l = line(4)
            l.setCell(0, 'A'.code, 1)
            l.setCell(1, 'B'.code, 2)
            l.setCell(2, 'C'.code, 3)
            l.setCell(3, 'D'.code, 4)

            l.deleteCells(col = 3, count = 1, defaultAttr = 55)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals('B'.code, l.getCodepoint(1)) },
                { assertEquals('C'.code, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
                { assertEquals(55L, l.getPackedAttr(3)) },
            )
        }

        @Test
        fun `count larger than remaining cells is clamped`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, 'B'.code, 0)
            l.setCell(2, 'C'.code, 0)
            l.setCell(3, 'D'.code, 0)

            l.deleteCells(col = 2, count = 99, defaultAttr = 42)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals('B'.code, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(4)) },
                { assertEquals(42L, l.getPackedAttr(2)) },
                { assertEquals(42L, l.getPackedAttr(4)) },
            )
        }

        @Test
        fun `no-op for invalid arguments`() {
            val l = line(4)
            l.setCell(0, 'X'.code, 7)
            l.setCell(1, 'Y'.code, 8)

            l.deleteCells(col = -1, count = 1, defaultAttr = 99)
            l.deleteCells(col = 4, count = 1, defaultAttr = 99)
            l.deleteCells(col = 0, count = 0, defaultAttr = 99)
            l.deleteCells(col = 0, count = -2, defaultAttr = 99)

            assertAll(
                { assertEquals('X'.code, l.getCodepoint(0)) },
                { assertEquals('Y'.code, l.getCodepoint(1)) },
                { assertEquals(7L, l.getPackedAttr(0)) },
                { assertEquals(8L, l.getPackedAttr(1)) },
            )
        }

        @Test
        fun `deletion removes the cluster in its range`() {
            val l = Line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCluster(1, intArrayOf(0x1F600, 0x200D), 2, 0)

            l.deleteCells(col = 1, count = 1, defaultAttr = 0)

            assertEquals(0, clusterCount(l))
            assertEquals("A", l.toTextTrimmed())
        }

        @Test
        fun `shifted cluster handle remains live after delete`() {
            val l = Line(5)
            l.setCluster(1, intArrayOf(0xAAAA), 1, 0) // Deleted occupant.
            l.setCluster(3, intArrayOf(0xBBBB, 0x200D), 2, 0) // Surviving occupant shifts left.

            l.deleteCells(col = 1, count = 1, defaultAttr = 0)

            assertEquals(1, clusterCount(l))

            // Shifted handle remains readable at its new column.
            val shiftedCol = 2
            assertTrue(l.isCluster(shiftedCol))
            val dest = IntArray(4)
            val len = l.readCluster(shiftedCol, dest)
            assertAll(
                { assertEquals(2, len) },
                { assertEquals(0xBBBB, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
            )
        }
    }

    // =========================================================================
    // toText / toTextTrimmed
    // =========================================================================

    @Nested
    @DisplayName("toText / toTextTrimmed")
    inner class ToTextTests {
        @Test
        fun `plain ascii line renders correctly`() {
            val l = line(5)
            "Hello".forEachIndexed { i, c -> l.setCell(i, c.code, 0) }
            assertEquals("Hello", l.toText())
        }

        @Test
        fun `empty cells render as spaces in toText`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCell(4, 'B'.code, 0)
            assertEquals("A   B", l.toText())
        }

        @Test
        fun `toTextTrimmed strips trailing empty cells`() {
            val l = line(10)
            l.setCell(0, 'H'.code, 0)
            l.setCell(1, 'i'.code, 0)
            assertEquals("Hi        ", l.toText())
            assertEquals("Hi", l.toTextTrimmed())
        }

        @Test
        fun `fully empty line returns spaces for toText and empty string for toTextTrimmed`() {
            val l = line(5)
            assertEquals("     ", l.toText())
            assertEquals("", l.toTextTrimmed())
        }

        @Test
        fun `wide char spacer is skipped in rendered output`() {
            val l = line(4)
            l.setCell(0, '你'.code, 0)
            l.setCell(1, TerminalConstants.WIDE_CHAR_SPACER, 0)
            l.setCell(2, 'X'.code, 0)
            assertAll(
                { assertEquals("你X ", l.toText()) },
                { assertEquals("你X", l.toTextTrimmed()) },
            )
        }

        @Test
        fun `cluster cell emits all codepoints in sequence`() {
            val l = line(5)
            // Family emoji: man + ZWJ + woman
            l.setCluster(0, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)
            l.setCell(1, 'X'.code, 0)

            val text = l.toTextTrimmed()
            // Must contain the base codepoint and all combining codepoints
            assertTrue(text.codePoints().toArray().contains(0x1F468), "Base must be present")
            assertTrue(text.codePoints().toArray().contains(0x200D), "ZWJ must be present")
            assertTrue(text.codePoints().toArray().contains(0x1F469), "Second emoji must be present")
            assertTrue(text.contains('X'), "'X' after cluster must be present")
        }

        @Test
        fun `cluster at last column is not trimmed by toTextTrimmed`() {
            val l = line(5)
            l.setCluster(4, intArrayOf(0x1F600, 0x200D), 2, 0)

            val trimmed = l.toTextTrimmed()
            assertTrue(
                trimmed.codePoints().toArray().contains(0x1F600),
                "Cluster at last column must not be trimmed",
            )
        }
    }
}
