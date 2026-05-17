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
package com.gagik.core.engine

import com.gagik.core.model.TerminalConstants
import com.gagik.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun buildState(
    cols: Int,
    rows: Int,
    history: Int = 0,
): TerminalState = TerminalState(maxHistory = history, initialWidth = cols, initialHeight = rows)

private fun resizeState(
    state: TerminalState,
    newWidth: Int,
    newHeight: Int,
) {
    val oldWidth = state.dimensions.width
    val oldHeight = state.dimensions.height
    TerminalResizer.resizeBuffer(state.primaryBuffer, oldWidth, oldHeight, newWidth, newHeight)
    state.dimensions.width = newWidth
    state.dimensions.height = newHeight
    state.tabStops.resize(newWidth)
}

private fun TerminalState.writeLine(
    screenRow: Int,
    text: String,
) {
    val top = (ring.size - dimensions.height).coerceAtLeast(0)
    val line = ring[top + screenRow]
    for ((col, ch) in text.withIndex()) {
        if (col >= dimensions.width) break
        line.setCell(col, ch.code, 0)
    }
}

private fun TerminalState.screenLines(): List<String> {
    val top = (ring.size - dimensions.height).coerceAtLeast(0)
    return (0 until dimensions.height).map { r -> ring[top + r].toTextTrimmed() }
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

@DisplayName("TerminalResizer Test Suite")
class TerminalResizerTest {
    // =========================================================================
    // Same-size resize
    // =========================================================================

    @Nested
    @DisplayName("Same-size resize")
    inner class SameSizeTests {
        @Test
        fun `resize to same size preserves content and cursor`() {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(4, "Hello")
            state.cursor.col = 3
            state.cursor.row = 4

            resizeState(state, 10, 5)

            assertAll(
                { assertEquals(10, state.dimensions.width) },
                { assertEquals(5, state.dimensions.height) },
                { assertEquals(3, state.cursor.col) },
                { assertEquals(4, state.cursor.row) },
                { assertEquals("Hello", state.screenLines()[4]) },
            )
        }

        @Test
        fun `resize to same size with multiple content rows preserves all rows`() {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(2, "Foo")
            state.writeLine(3, "Bar")
            state.writeLine(4, "Baz")
            state.cursor.row = 4

            resizeState(state, 10, 5)

            val lines = state.screenLines()
            assertAll(
                { assertEquals("Foo", lines[2]) },
                { assertEquals("Bar", lines[3]) },
                { assertEquals("Baz", lines[4]) },
            )
        }
    }

    // =========================================================================
    // Width changes - reflow
    // =========================================================================

    @Nested
    @DisplayName("Width changes (reflow)")
    inner class WidthChangeTests {
        @Test
        fun `narrowing splits a long line into two physical lines`() {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(4, "HelloWorld")
            state.cursor.row = 4

            resizeState(state, 5, 5)

            val lines = state.screenLines()
            assertAll(
                { assertEquals("Hello", lines[3]) },
                { assertEquals("World", lines[4]) },
            )
        }

        @Test
        fun `widening merges two wrapped physical lines into one`() {
            val state = buildState(cols = 5, rows = 5)
            val top = (state.ring.size - 5).coerceAtLeast(0)
            state.ring[top + 3].apply {
                "Hello".forEachIndexed { i, c -> setCell(i, c.code, 0) }
                wrapped = true
            }
            state.ring[top + 4].apply {
                "World".forEachIndexed { i, c -> setCell(i, c.code, 0) }
                wrapped = false
            }
            state.cursor.row = 4

            resizeState(state, 10, 5)

            assertEquals("HelloWorld", state.screenLines()[3])
        }

        @ParameterizedTest(name = "Content survives resize to width={0}")
        @CsvSource("1", "3", "7", "20", "80")
        fun `content is preserved across arbitrary width changes`(newWidth: Int) {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(4, "ABCDE")
            state.cursor.row = 4

            resizeState(state, newWidth, 5)

            val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
            val allText =
                (0 until state.dimensions.height)
                    .joinToString("") { state.ring[top + it].toText() }
            assertTrue(
                allText.contains("ABCDE"),
                "Expected 'ABCDE' somewhere in the screen after resize to width $newWidth",
            )
        }

        @Test
        fun `narrowing to width 1 spreads each character onto its own row`() {
            val state = buildState(cols = 4, rows = 5)
            state.writeLine(4, "ABCD")
            state.cursor.row = 4

            resizeState(state, 1, 5)

            val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
            val allText =
                (0 until state.dimensions.height)
                    .joinToString("") { state.ring[top + it].toTextTrimmed() }
            assertEquals("ABCD", allText)
        }

        @Test
        fun `multiple adjacent content lines each reflow independently`() {
            val state = buildState(cols = 6, rows = 5)
            val top = (state.ring.size - 5).coerceAtLeast(0)
            state.ring[top + 3].apply {
                "AAABBB".forEachIndexed { i, c -> setCell(i, c.code, 0) }
            }
            state.ring[top + 4].apply {
                "CCCDDD".forEachIndexed { i, c -> setCell(i, c.code, 0) }
            }
            state.cursor.row = 4

            resizeState(state, 3, 5)

            val newTop = (state.ring.size - 5).coerceAtLeast(0)
            // 4 chunks: AAA, BBB, CCC, DDD land in rows 1..4
            assertAll(
                { assertEquals("AAA", state.ring[newTop + 1].toTextTrimmed()) },
                { assertEquals("BBB", state.ring[newTop + 2].toTextTrimmed()) },
                { assertEquals("CCC", state.ring[newTop + 3].toTextTrimmed()) },
                { assertEquals("DDD", state.ring[newTop + 4].toTextTrimmed()) },
            )
        }
    }

    // =========================================================================
    // Wide character boundary handling
    // =========================================================================

    @Nested
    @DisplayName("Wide character boundary handling")
    inner class WideCharTests {
        @Test
        fun `wide character is not split across physical lines on resize`() {
            val state = buildState(cols = 6, rows = 2)
            val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
            state.ring[top + 1].apply {
                setCell(0, 'A'.code, 0)
                setCell(1, 'B'.code, 0)
                setCell(2, 'C'.code, 0)
                setCell(3, 0x4F60, 0)
                setCell(4, TerminalConstants.WIDE_CHAR_SPACER, 0)
                setCell(5, 'D'.code, 0)
            }
            state.cursor.row = 1

            resizeState(state, 4, 2)

            val visTop = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
            val first = state.ring[visTop]
            val second = state.ring[visTop + 1]

            assertAll(
                // First chunk: ABC + empty (wide leader bumped to next line)
                { assertEquals('A'.code, first.getCodepoint(0)) },
                { assertEquals('B'.code, first.getCodepoint(1)) },
                { assertEquals('C'.code, first.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, first.getCodepoint(3)) },
                // Second chunk: 0x4F60 + spacer + D
                { assertEquals(0x4F60, second.getCodepoint(0)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, second.getCodepoint(1)) },
                { assertEquals('D'.code, second.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, second.getCodepoint(3)) },
            )
        }

        @Test
        fun `wide character at exact boundary is bumped to next row and not duplicated`() {
            val state = buildState(cols = 4, rows = 2)
            val top = (state.ring.size - 2).coerceAtLeast(0)
            state.ring[top + 1].apply {
                setCell(0, 'A'.code, 0)
                setCell(1, 'B'.code, 0)
                setCell(2, 0x4F60, 0) // wide leader at col 2
                setCell(3, TerminalConstants.WIDE_CHAR_SPACER, 0) // spacer at col 3
            }
            state.cursor.row = 1

            resizeState(state, 3, 2)

            val visTop = (state.ring.size - 2).coerceAtLeast(0)
            val first = state.ring[visTop]
            val second = state.ring[visTop + 1]

            assertAll(
                // First line: AB + empty (wide char bumped)
                { assertEquals('A'.code, first.getCodepoint(0)) },
                { assertEquals('B'.code, first.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, first.getCodepoint(2)) },
                // Second line: 0x4F60 + spacer
                { assertEquals(0x4F60, second.getCodepoint(0)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, second.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, second.getCodepoint(2)) },
            )
        }

        @Test
        fun `wide char not at boundary is not disturbed`() {
            val state = buildState(cols = 6, rows = 2)
            val top = (state.ring.size - 2).coerceAtLeast(0)
            state.ring[top + 1].apply {
                setCell(0, 0x4F60, 0)
                setCell(1, TerminalConstants.WIDE_CHAR_SPACER, 0)
                setCell(2, 'A'.code, 0)
            }
            state.cursor.row = 1

            resizeState(state, 4, 2)

            val visTop = (state.ring.size - 2).coerceAtLeast(0)
            val line = state.ring[visTop + 1]

            assertAll(
                { assertEquals(0x4F60, line.getCodepoint(0)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.getCodepoint(1)) },
                { assertEquals('A'.code, line.getCodepoint(2)) },
            )
        }
    }

    // =========================================================================
    // Cluster cell preservation
    // =========================================================================

    @Nested
    @DisplayName("Cluster cell deep-copy on resize")
    inner class ClusterCellTests {
        @Test
        fun `ZWJ cluster survives resize with all codepoints intact`() {
            val state = buildState(cols = 6, rows = 2)
            val top = (state.ring.size - 2).coerceAtLeast(0)
            // Family emoji: man + ZWJ + woman
            state.ring[top + 1].setCluster(
                0,
                intArrayOf(0x1F468, 0x200D, 0x1F469),
                3,
                42,
            )
            state.cursor.row = 1

            resizeState(state, 6, 2)

            val visTop = (state.ring.size - 2).coerceAtLeast(0)
            val line = state.ring[visTop + 1]
            val dest = IntArray(4)
            val written = line.readCluster(0, dest)

            assertAll(
                { assertTrue(line.isCluster(0), "Cell must still be a cluster after resize") },
                { assertEquals(3, written) },
                { assertEquals(0x1F468, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
                { assertEquals(0x1F469, dest[2]) },
                { assertEquals(42L, line.getPackedAttr(0)) },
            )
        }

        @Test
        fun `cluster handle in new ring points into new store not old store`() {
            val state = buildState(cols = 6, rows = 2)
            val top = (state.ring.size - 2).coerceAtLeast(0)
            state.ring[top + 1].setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            val oldStore = state.primaryBuffer.store
            state.cursor.row = 1

            resizeState(state, 6, 2)

            assertNotSame(
                oldStore,
                state.primaryBuffer.store,
                "State must reference the new store after resize",
            )

            // New store must serve the handle correctly
            val visTop = (state.ring.size - 2).coerceAtLeast(0)
            val dest = IntArray(4)
            val written = state.ring[visTop + 1].readCluster(0, dest)
            assertAll(
                { assertEquals(2, written) },
                { assertEquals(0xAAAA, dest[0]) },
                { assertEquals(0xBBBB, dest[1]) },
            )
        }

        @Test
        fun `cluster split across resize boundary lands intact on new row`() {
            val state = buildState(cols = 4, rows = 2)
            val top = (state.ring.size - 2).coerceAtLeast(0)
            state.ring[top + 1].apply {
                setCell(0, 'A'.code, 0)
                setCell(1, 'B'.code, 0)
                setCell(2, 'C'.code, 0)
                // Cluster at col 3 gets split to next line when narrowing to 3
                setCluster(3, intArrayOf(0x1F600, 0x200D), 2, 7)
            }
            state.cursor.row = 1

            resizeState(state, 3, 2)

            val visTop = (state.ring.size - 2).coerceAtLeast(0)
            val secondRow = state.ring[visTop + 1]
            val dest = IntArray(4)
            val written = secondRow.readCluster(0, dest)

            assertAll(
                { assertTrue(secondRow.isCluster(0)) },
                { assertEquals(2, written) },
                { assertEquals(0x1F600, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
                { assertEquals(7L, secondRow.getPackedAttr(0)) },
            )
        }
    }

    // =========================================================================
    // Height changes
    // =========================================================================

    @Nested
    @DisplayName("Height changes")
    inner class HeightChangeTests {
        @Test
        fun `shrinking height keeps content visible in bottom rows`() {
            val state = buildState(cols = 10, rows = 10)
            state.writeLine(9, "Bottom")

            resizeState(state, 10, 5)

            assertAll(
                { assertEquals(5, state.dimensions.height) },
                { assertEquals("Bottom", state.screenLines()[4]) },
            )
        }

        @Test
        fun `growing height adds blank rows and retains existing content`() {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(4, "Content")
            state.cursor.row = 4

            resizeState(state, 10, 10)

            assertAll(
                { assertEquals(10, state.dimensions.height) },
                { assertTrue(state.screenLines().any { it == "Content" }) },
            )
        }

        @Test
        fun `growing height fills new rows with blank lines`() {
            val state = buildState(cols = 10, rows = 5)
            state.cursor.row = 4

            resizeState(state, 10, 8)

            assertEquals(8, state.dimensions.height)
            assertTrue(state.ring.size >= 8)
        }
    }

    // =========================================================================
    // Cursor tracking
    // =========================================================================

    @Nested
    @DisplayName("Cursor tracking")
    inner class CursorTrackingTests {
        @Test
        fun `cursor col is clamped to new width when narrowing`() {
            val state = buildState(cols = 20, rows = 5)
            state.cursor.col = 15
            state.cursor.row = 0

            resizeState(state, 10, 5)

            assertTrue(
                state.cursor.col < 10,
                "Cursor col ${state.cursor.col} should be < new width 10",
            )
        }

        @Test
        fun `cursor is tracked to correct physical line when its logical line is split`() {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(4, "AAABBBCCC")
            state.cursor.col = 7
            state.cursor.row = 4

            resizeState(state, 5, 5)

            // "AAABBBCCC" -> "AAABB" + "BCCC"
            // col 7 maps to col 2 on the second chunk
            assertAll(
                { assertEquals(2, state.cursor.col, "cursor col after split") },
                { assertTrue(state.cursor.row in 0 until 5) },
            )
        }

        @Test
        fun `cursor row is always within screen bounds after resize`() {
            val state = buildState(cols = 80, rows = 24)
            state.cursor.row = 20
            state.cursor.col = 5

            resizeState(state, 40, 10)

            assertAll(
                { assertTrue(state.cursor.row in 0 until 10) },
                { assertTrue(state.cursor.col in 0 until 40) },
            )
        }

        @Test
        fun `cursor on empty line at col 0 is preserved`() {
            val state = buildState(cols = 10, rows = 5)
            state.cursor.row = 0
            state.cursor.col = 0

            resizeState(state, 10, 5)

            assertAll(
                { assertEquals(0, state.cursor.row) },
                { assertEquals(0, state.cursor.col) },
            )
        }

        @Test
        fun `cursor on virtual blank row below ring content stays in bounds`() {
            val state = buildState(cols = 80, rows = 24)
            state.writeLine(0, "Line0")
            state.writeLine(1, "Line1")
            state.cursor.row = 5
            state.cursor.col = 10

            resizeState(state, 80, 24)

            assertAll(
                { assertTrue(state.cursor.row in 0 until 24) },
                { assertTrue(state.cursor.col in 0 until 80) },
            )
        }

        @Test
        fun `cursor at last col of wide char leader stays valid after resize`() {
            val state = buildState(cols = 4, rows = 2)
            val top = (state.ring.size - 2).coerceAtLeast(0)
            state.ring[top + 1].apply {
                setCell(0, 0x4F60, 0)
                setCell(1, TerminalConstants.WIDE_CHAR_SPACER, 0)
            }
            state.cursor.row = 1
            state.cursor.col = 0

            resizeState(state, 4, 2)

            assertAll(
                { assertTrue(state.cursor.row in 0 until 2) },
                { assertTrue(state.cursor.col in 0 until 4) },
            )
        }
    }

    // =========================================================================
    // Wrapped flag correctness
    // =========================================================================

    @Nested
    @DisplayName("Wrapped flag handling")
    inner class WrappedFlagTests {
        @Test
        fun `last physical chunk of a reflowed logical line is not wrapped`() {
            val state = buildState(cols = 10, rows = 5)
            state.writeLine(4, "HelloWorld")
            state.cursor.row = 4

            resizeState(state, 5, 5)

            val top = (state.ring.size - 5).coerceAtLeast(0)
            assertAll(
                { assertTrue(state.ring[top + 3].wrapped, "first chunk should be wrapped") },
                { assertFalse(state.ring[top + 4].wrapped, "last chunk must not be wrapped") },
            )
        }

        @Test
        fun `a logical line split into three chunks has first two wrapped`() {
            val state = buildState(cols = 9, rows = 5)
            state.writeLine(4, "AAABBBCCC")
            state.cursor.row = 4

            resizeState(state, 3, 5)

            val top = (state.ring.size - 5).coerceAtLeast(0)
            assertAll(
                { assertTrue(state.ring[top + 2].wrapped, "chunk 1 should be wrapped") },
                { assertTrue(state.ring[top + 3].wrapped, "chunk 2 should be wrapped") },
                { assertFalse(state.ring[top + 4].wrapped, "chunk 3 must not be wrapped") },
            )
        }

        @Test
        fun `single-line content that fits in new width is not wrapped`() {
            val state = buildState(cols = 5, rows = 5)
            state.writeLine(4, "Hi")
            state.cursor.row = 4

            resizeState(state, 10, 5)

            val top = (state.ring.size - 5).coerceAtLeast(0)
            assertFalse(
                state.ring[top + 4].wrapped,
                "A line that fits entirely in the new width must not be wrapped",
            )
        }

        @Test
        fun `orphaned wrap flag on empty line does not produce phantom content`() {
            val state = buildState(cols = 10, rows = 5)
            val top = (state.ring.size - 5).coerceAtLeast(0)
            state.ring[top].wrapped = true // empty line with orphan wrap flag

            resizeState(state, 10, 5)

            assertEquals("", state.screenLines()[0])
        }
    }

    // =========================================================================
    // Scrollback history
    // =========================================================================

    @Nested
    @DisplayName("Scrollback history")
    inner class ScrollbackTests {
        @Test
        fun `history lines are preserved after resize`() {
            val state = buildState(cols = 10, rows = 3, history = 5)
            for (r in 0 until 3) state.writeLine(r, "Row$r")
            repeat(2) { state.ring.push().clear(0) }

            resizeState(state, 10, 3)

            assertTrue(state.ring.size >= 3)
        }

        @Test
        fun `ring capacity honours maxHistory + newHeight`() {
            val state = buildState(cols = 10, rows = 5, history = 100)

            resizeState(state, 10, 10)

            assertTrue(state.ring.size >= 10)
        }
    }

    // =========================================================================
    // Dimensions update
    // =========================================================================

    @Nested
    @DisplayName("Dimensions update")
    inner class DimensionsTests {
        @ParameterizedTest(name = "Resize {0}x{1} -> {2}x{3}")
        @CsvSource(
            "80, 24, 40, 12",
            "40, 12, 80, 24",
            "10,  5,  1,  1",
            " 1,  1, 80, 24",
        )
        fun `dimensions are updated correctly`(
            oldW: Int,
            oldH: Int,
            newW: Int,
            newH: Int,
        ) {
            val state = buildState(cols = oldW, rows = oldH)

            resizeState(state, newW, newH)

            assertAll(
                { assertEquals(newW, state.dimensions.width) },
                { assertEquals(newH, state.dimensions.height) },
            )
        }

        @Test
        fun `state clusterStore is replaced with a new instance after resize`() {
            val state = buildState(cols = 10, rows = 5)
            val oldStore = state.primaryBuffer.store

            resizeState(state, 10, 5)

            assertNotSame(
                oldStore,
                state.primaryBuffer.store,
                "clusterStore must be a new instance after resize",
            )
        }
    }
}
