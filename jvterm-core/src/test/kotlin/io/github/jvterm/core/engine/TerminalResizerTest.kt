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
package io.github.jvterm.core.engine

import io.github.jvterm.core.model.TerminalConstants
import io.github.jvterm.core.state.TerminalState
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

/**
 * Resizes the state and returns the new scrollback offset calculated by the resizer.
 */
private fun resizeStateReturningOffset(
    state: TerminalState,
    newWidth: Int,
    newHeight: Int,
    oldScrollbackOffset: Int,
): Int {
    val oldWidth = state.dimensions.width
    val oldHeight = state.dimensions.height
    val newOffset =
        TerminalResizer.resizeBuffer(
            state.primaryBuffer,
            oldWidth,
            oldHeight,
            newWidth,
            newHeight,
            oldScrollbackOffset,
        )
    state.dimensions.width = newWidth
    state.dimensions.height = newHeight
    state.tabStops.resize(newWidth)
    return newOffset
}

/**
 * Returns the trimmed text of the ring row that sits at the top of the viewport
 * when the user is scrolled back by [offset] lines from the live screen.
 */
private fun TerminalState.viewportTopLine(offset: Int): String {
    val liveTop = (ring.size - dimensions.height).coerceAtLeast(0)
    val idx = (liveTop - offset).coerceAtLeast(0)
    return ring[idx].toTextTrimmed()
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

private fun findLineStartingWith(
    state: TerminalState,
    codepoint: Int,
) = (0 until state.ring.size)
    .asSequence()
    .map { state.ring[it] }
    .firstOrNull { it.getCodepoint(0) == codepoint }

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

        @Test
        fun `shrinking without trailing blank rows keeps bottom content at viewport bottom`() {
            val state = buildState(cols = 6, rows = 3)
            state.writeLine(0, "top")
            state.writeLine(1, "mid")
            state.writeLine(2, "abcdef")
            state.cursor.row = 2

            resizeState(state, 3, 3)

            assertAll(
                { assertEquals("mid", state.screenLines()[0]) },
                { assertEquals("abc", state.screenLines()[1]) },
                { assertEquals("def", state.screenLines()[2]) },
                { assertEquals(2, state.cursor.row) },
            )
        }

        @Test
        fun `shrinking with trailing blank rows consumes blanks before bottom anchoring`() {
            val state = buildState(cols = 6, rows = 4)
            state.writeLine(0, "T")
            state.writeLine(1, "abcdef")
            state.cursor.row = 1

            resizeState(state, 3, 4)

            assertAll(
                { assertEquals("T", state.screenLines()[0]) },
                { assertEquals("abc", state.screenLines()[1]) },
                { assertEquals("def", state.screenLines()[2]) },
                { assertEquals("", state.screenLines()[3]) },
            )

            resizeState(state, 2, 4)

            assertAll(
                { assertEquals("T", state.screenLines()[0]) },
                { assertEquals("ab", state.screenLines()[1]) },
                { assertEquals("cd", state.screenLines()[2]) },
                { assertEquals("ef", state.screenLines()[3]) },
            )
        }

        @Test
        fun `widening after shrink preserves viewport top and creates bottom blanks`() {
            val state = buildState(cols = 6, rows = 3)
            state.writeLine(0, "top")
            state.writeLine(1, "mid")
            state.writeLine(2, "abcdef")
            state.cursor.row = 2

            resizeState(state, 3, 3)
            resizeState(state, 6, 3)

            assertAll(
                { assertEquals("mid", state.screenLines()[0]) },
                { assertEquals("abcdef", state.screenLines()[1]) },
                { assertEquals("", state.screenLines()[2]) },
                { assertEquals(1, state.cursor.row) },
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
        fun `wide boundary padding is not preserved across repeated resize`() {
            val state = buildState(cols = 6, rows = 4, history = 5)
            val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
            state.ring[top + 3].apply {
                setCell(0, 'A'.code, 0)
                setCell(1, 'B'.code, 0)
                setCell(2, 'C'.code, 0)
                setCell(3, 0x4F60, 0)
                setCell(4, TerminalConstants.WIDE_CHAR_SPACER, 0)
                setCell(5, 'D'.code, 0)
            }

            resizeState(state, 4, 4)
            resizeState(state, 3, 4)
            resizeState(state, 6, 4)

            val line =
                requireNotNull(findLineStartingWith(state, 'A'.code)) {
                    "Expected the wide-boundary line to survive repeated resize"
                }

            assertAll(
                { assertEquals('A'.code, line.getCodepoint(0)) },
                { assertEquals('B'.code, line.getCodepoint(1)) },
                { assertEquals('C'.code, line.getCodepoint(2)) },
                { assertEquals(0x4F60, line.getCodepoint(3)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.rawCodepoint(4)) },
                { assertEquals('D'.code, line.getCodepoint(5)) },
            )
        }

        @Test
        fun `explicit spaces before wide character survive repeated resize`() {
            val state = buildState(cols = 6, rows = 4, history = 5)
            val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
            state.ring[top + 3].apply {
                setCell(0, 'A'.code, 0)
                setCell(1, 'B'.code, 0)
                setCell(2, ' '.code, 0)
                setCell(3, 0x4F60, 0)
                setCell(4, TerminalConstants.WIDE_CHAR_SPACER, 0)
                setCell(5, 'D'.code, 0)
            }

            resizeState(state, 4, 4)
            resizeState(state, 3, 4)
            resizeState(state, 6, 4)

            val line =
                requireNotNull(findLineStartingWith(state, 'A'.code)) {
                    "Expected the explicit space prefix to survive repeated resize"
                }

            assertAll(
                { assertEquals('A'.code, line.getCodepoint(0)) },
                { assertEquals('B'.code, line.getCodepoint(1)) },
                { assertEquals(' '.code, line.getCodepoint(2)) },
                { assertEquals(0x4F60, line.getCodepoint(3)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.rawCodepoint(4)) },
                { assertEquals('D'.code, line.getCodepoint(5)) },
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
        fun `widening preserves live viewport top instead of pulling history into freed rows`() {
            val state = buildState(cols = 3, rows = 3, history = 5)
            state.ring.clear()
            state.ring.push().apply {
                clear(0, 0)
                "old".forEachIndexed { i, c -> setCell(i, c.code, 0) }
            }
            state.ring.push().apply {
                clear(0, 0)
                "aaa".forEachIndexed { i, c -> setCell(i, c.code, 0) }
                wrapped = true
            }
            state.ring.push().apply {
                clear(0, 0)
                "bbb".forEachIndexed { i, c -> setCell(i, c.code, 0) }
                wrapped = false
            }
            state.ring.push().apply {
                clear(0, 0)
                setCell(0, 's'.code, 0)
            }
            state.cursor.row = 2

            resizeState(state, 9, 3)

            assertAll(
                { assertEquals("old", state.ring[0].toTextTrimmed()) },
                { assertEquals("aaabbb", state.screenLines()[0]) },
                { assertEquals("s", state.screenLines()[1]) },
                { assertEquals("", state.screenLines()[2]) },
                { assertEquals(1, state.cursor.row) },
            )
        }

        @Test
        fun `widening terminates when reflowed history exceeds history capacity`() {
            val state = buildState(cols = 2, rows = 2, history = 1)
            state.ring.clear()
            repeat(6) { index ->
                state.ring.push().apply {
                    clear(0, 0)
                    setCell(0, ('a'.code + index), 0)
                    setCell(1, ('A'.code + index), 0)
                    wrapped = index < 4
                }
            }
            state.cursor.row = 1

            resizeState(state, 8, 2)

            assertTrue(state.ring.size <= state.primaryBuffer.maxHistory + state.dimensions.height)
        }

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

    // =========================================================================
    // Scrollback viewport anchoring
    // =========================================================================

    @Nested
    @DisplayName("Scrollback viewport anchoring")
    inner class ScrollbackViewportAnchoringTests {
        /**
         * Builds a state with [historyRows] history rows followed by [screenRows] live-screen
         * rows. History rows are filled with the text in [historyContent]; screen rows are filled
         * with [screenContent]. All content strings are padded/trimmed to [cols].
         */
        private fun buildScrolledState(
            cols: Int,
            height: Int,
            historyContent: List<String>,
            screenContent: List<String>,
            history: Int = 100,
        ): TerminalState {
            require(screenContent.size == height) { "screenContent.size must equal height" }
            val state = buildState(cols, height, history)
            state.ring.clear()
            for (text in historyContent) {
                state.ring.push().apply {
                    clear(0, 0)
                    text.take(cols).forEachIndexed { i, c -> setCell(i, c.code, 0) }
                }
            }
            for (text in screenContent) {
                state.ring.push().apply {
                    clear(0, 0)
                    text.take(cols).forEachIndexed { i, c -> setCell(i, c.code, 0) }
                }
            }
            // Place cursor at the last screen row so that "shouldPreserveViewportTop" logic
            // does not skip trailing-blank counting in a way that obscures the assertions.
            state.cursor.row = height - 1
            return state
        }

        @Test
        fun `zero offset always returns zero regardless of resize direction`() {
            val state =
                buildScrolledState(
                    cols = 5,
                    height = 3,
                    historyContent = listOf("hist0", "hist1"),
                    screenContent = listOf("scr0 ", "scr1 ", "scr2 "),
                )

            val widenOffset = resizeStateReturningOffset(state, 10, 3, oldScrollbackOffset = 0)
            assertEquals(0, widenOffset, "offset must be 0 when not scrolled back")
        }

        @Test
        fun `widen while scrolled back keeps viewport top line visible`() {
            // History: ["aaa", "bbb"]  |  Screen: ["c0", "c1", "c2"]
            // scrolled back by 1 → viewing: bbb | c0 | c1
            val state =
                buildScrolledState(
                    cols = 5,
                    height = 3,
                    historyContent = listOf("aaa", "bbb"),
                    screenContent = listOf("c0", "c1", "c2"),
                )
            val oldViewportTop = state.viewportTopLine(offset = 1)
            assertEquals("bbb", oldViewportTop)

            val newOffset = resizeStateReturningOffset(state, 10, 3, oldScrollbackOffset = 1)

            // The returned offset must place the same logical line at the top of the viewport.
            val newViewportTop = state.viewportTopLine(newOffset)
            assertEquals(
                "bbb",
                newViewportTop,
                "viewport top must stay on the same content after widening",
            )
        }

        @Test
        fun `narrow while scrolled back keeps viewport top line visible`() {
            // Short content that does not wrap when narrowed, so the ring structure stays the same.
            val state =
                buildScrolledState(
                    cols = 8,
                    height = 3,
                    historyContent = listOf("aa", "BB"),
                    screenContent = listOf("s0", "s1", "s2"),
                )
            val oldViewportTop = state.viewportTopLine(offset = 1)
            assertEquals("BB", oldViewportTop)

            val newOffset = resizeStateReturningOffset(state, 4, 3, oldScrollbackOffset = 1)

            val newViewportTop = state.viewportTopLine(newOffset)
            assertEquals(
                "BB",
                newViewportTop,
                "viewport top must stay on the same content after narrowing",
            )
        }

        @Test
        fun `narrow while scrolled back, viewport-top line wraps, offset increases to match`() {
            // "ABCDEFGH" is the viewport-top history line (full width = 8).
            // After narrowing to 4 it becomes two physical lines: "ABCD"(wrapped) + "EFGH".
            // The new offset must point to the FIRST physical chunk of the reflowed line.
            val state =
                buildScrolledState(
                    cols = 8,
                    height = 3,
                    historyContent = listOf("prev", "ABCDEFGH"),
                    screenContent = listOf("s0", "s1", "s2"),
                )
            assertEquals("ABCDEFGH", state.viewportTopLine(offset = 1))

            val newOffset = resizeStateReturningOffset(state, 4, 3, oldScrollbackOffset = 1)

            val newViewportTop = state.viewportTopLine(newOffset)
            assertEquals(
                "ABCD",
                newViewportTop,
                "viewport top must be the first physical chunk of the reflowed logical line",
            )
        }

        @Test
        fun `offset scrolled all the way back is clamped to new history size`() {
            // Offset that exceeds history after reflow must be clamped, not negative-index.
            val state =
                buildScrolledState(
                    cols = 10,
                    height = 3,
                    historyContent = listOf("only"),
                    screenContent = listOf("s0", "s1", "s2"),
                )
            // Pass an offset larger than available history.
            val newOffset = resizeStateReturningOffset(state, 10, 3, oldScrollbackOffset = 99)

            val newLiveTop = (state.ring.size - 3).coerceAtLeast(0)
            assertTrue(
                newOffset in 0..newLiveTop,
                "clamped offset $newOffset must be in [0, newHistorySize=$newLiveTop]",
            )
        }

        @Test
        fun `multiple resizes while scrolled back maintain viewport stability`() {
            // Widen then narrow then widen again; each time the viewport top should
            // track the same logical content ("anchor" line).
            val state =
                buildScrolledState(
                    cols = 6,
                    height = 3,
                    historyContent = listOf("before", "anchor"),
                    screenContent = listOf("s0", "s1", "s2"),
                )
            assertEquals("anchor", state.viewportTopLine(offset = 1))

            val off1 = resizeStateReturningOffset(state, 12, 3, oldScrollbackOffset = 1)
            assertEquals("anchor", state.viewportTopLine(off1), "after widen")

            val off2 = resizeStateReturningOffset(state, 4, 3, oldScrollbackOffset = off1)
            // "anchor" is 6 chars which fits in 4 only as two chunks: "anch"(wrapped)+"or".
            // viewport top must be the first chunk.
            assertEquals("anch", state.viewportTopLine(off2), "after narrow")

            val off3 = resizeStateReturningOffset(state, 12, 3, oldScrollbackOffset = off2)
            assertEquals("anchor", state.viewportTopLine(off3), "after widen again")
        }
    }
}
