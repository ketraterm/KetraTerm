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

import io.github.jvterm.core.model.Line
import io.github.jvterm.core.model.TerminalConstants
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GridWriter Test Suite")
class MutationEngineTest {
    private fun createState(
        width: Int = 5,
        height: Int = 2,
        history: Int = 2,
    ): TerminalState = TerminalState(width, height, maxHistory = history)

    private fun lineAt(
        state: TerminalState,
        row: Int,
    ): Line {
        val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
        return state.ring[top + row]
    }

    private fun writeAscii(
        writer: MutationEngine,
        text: String,
    ) {
        for (ch in text) {
            writer.printCodepoint(ch.code, 1)
        }
    }

    private fun assertLineCodepoints(
        state: TerminalState,
        row: Int,
        expected: IntArray,
    ) {
        val line = lineAt(state, row)
        for (i in expected.indices) {
            assertEquals(expected[i], line.getCodepoint(i), "Codepoint mismatch at row=$row col=$i")
        }
    }

    private fun assertLineAttrs(
        state: TerminalState,
        row: Int,
        expected: LongArray,
    ) {
        val line = lineAt(state, row)
        for (i in expected.indices) {
            assertEquals(expected[i], line.getPackedAttr(i), "Attr mismatch at row=$row col=$i")
        }
    }

    private fun seedLine(
        state: TerminalState,
        row: Int,
        text: String,
        attr: Long = 0,
    ) {
        val line = lineAt(state, row)
        for ((i, ch) in text.withIndex()) {
            if (i >= line.width) break
            line.setCell(i, ch.code, attr)
        }
    }

    private fun setScrollRegion(
        state: TerminalState,
        top: Int,
        bottom: Int,
    ) {
        state.activeBuffer.setScrollRegion(
            top = top + 1,
            bottom = bottom + 1,
            isOriginMode = state.modes.isOriginMode,
            viewportHeight = state.dimensions.height,
        )
    }

    @Nested
    @DisplayName("printCodepoint")
    inner class PrintCodepointTests {
        @Test
        fun `fast path appends single-width into empty cell`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint('A'.code, charWidth = 1)

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
            )
        }

        @Test
        fun `non-standard width value is treated as single-width`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint('Z'.code, charWidth = 3)

            assertAll(
                { assertEquals('Z'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
            )
        }

        @Test
        fun `overwrite on leader cell replaces only that cell`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            state.cursor.col = 0

            writer.printCodepoint('X'.code, charWidth = 1)

            assertAll(
                { assertEquals('X'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals('B'.code, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(1, state.cursor.col) },
            )
        }

        @Test
        fun `writes width-2 cluster as leader plus spacer`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint(0x1F600, charWidth = 2)

            assertAll(
                { assertEquals(0x1F600, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(2, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
            )
        }

        @Test
        fun `overwrite while cursor is on spacer clears cluster and writes at cursor column`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint(0x1F600, charWidth = 2)
            state.cursor.col = 1

            writer.printCodepoint('A'.code, charWidth = 1)

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(2, state.cursor.col) },
            )
        }

        @Test
        fun `overlap crush wide on spacer annihilates old cluster and prevents leader-leader`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint(0x1F600, charWidth = 2)
            state.cursor.col = 1

            writer.printCodepoint(0x1F923, charWidth = 2)

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(0x1F923, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals(3, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
            )
        }

        @Test
        fun `super crush wide overlap annihilates both neighboring clusters`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint(0x1F600, charWidth = 2) // A at 0-1
            writer.printCodepoint(0x1F603, charWidth = 2) // B at 2-3; cCol=4 → pendingWrap=true, col=3
            // Manual cursor reposition: clear pendingWrap too, simulating a cursor-set command
            state.cursor.col = 1
            state.cursor.pendingWrap = false

            writer.printCodepoint(0x1F923, charWidth = 2) // C at 1-2; cCol=3 < width=4 → normal commit

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(0x1F923, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals(3, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertFalse(state.cursor.pendingWrap) }, // col=3, not at edge yet
            )
        }

        @Test
        fun `single-width write at end wraps and marks line wrapped`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)

            writer.printCodepoint('A'.code, 1) // col=0 → col=1
            writer.printCodepoint('B'.code, 1) // col=1 == width-1 → pendingWrap=true, col stays 1

            // Deferred wrap: physical move happens on NEXT write, not now.
            // The line is only marked wrapped when the next character triggers wrap consumption.
            assertAll(
                { assertEquals(1, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertTrue(state.cursor.pendingWrap) },
            )

            // Confirm line is marked wrapped and cursor advances on the next write
            writer.printCodepoint('C'.code, 1)
            assertAll(
                { assertTrue(lineAt(state, 0).wrapped) },
                { assertEquals('C'.code, lineAt(state, 1).getCodepoint(0)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(1, state.cursor.row) },
            )
        }

        @Test
        fun `width-2 on last column wraps before write`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")

            writer.printCodepoint(0x1F601, 2)

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(0x1F601, lineAt(state, 1).getCodepoint(0)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 1).getCodepoint(1)) },
                { assertEquals(2, state.cursor.col) },
                { assertEquals(1, state.cursor.row) },
            )
        }

        @Test
        fun `width-2 at last column on bottom row scrolls then writes`() {
            val state = createState(width = 3, height = 1, history = 4)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")

            writer.printCodepoint(0x1F602, 2)

            assertAll(
                { assertTrue(state.ring.size >= 2) },
                { assertEquals(0x1F602, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(2, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
            )
        }

        @Test
        fun `out-of-bounds cursor is ignored without mutation`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABC")
            state.cursor.col = 99

            writer.printCodepoint('Z'.code, 1)

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals('B'.code, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals('C'.code, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(99, state.cursor.col) },
            )
        }

        @Test
        fun `row out-of-bounds cursor is ignored without mutation`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABC")
            state.cursor.row = 99

            writer.printCodepoint('Z'.code, 1)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code)) },
                { assertEquals(99, state.cursor.row) },
            )
        }

        @Test
        fun `wide character fits when starting at width minus two`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AB")
            state.cursor.row = 0
            state.cursor.col = 2

            writer.printCodepoint(0x1F923, 2) // writes cols 2-3; cCol advances to 4 >= width

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals('B'.code, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(0x1F923, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).getCodepoint(3)) },
                // cCol hit width → deferred wrap: col=width-1=3, pendingWrap=true, row stays 0
                { assertEquals(3, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertTrue(state.cursor.pendingWrap) },
            )

            // Confirm physical wrap fires on next write
            writer.printCodepoint('X'.code, 1)
            assertAll(
                { assertTrue(lineAt(state, 0).wrapped) },
                { assertEquals('X'.code, lineAt(state, 1).getCodepoint(0)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(1, state.cursor.row) },
            )
        }
    }

    @Nested
    @DisplayName("erase operations")
    inner class EraseTests {
        @Test
        fun `eraseCharacters_zeroDefaultsToOne`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")
            state.cursor.col = 2

            writer.eraseCharacters(0)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('A'.code, 'B'.code, TerminalConstants.EMPTY, 'D'.code, 'E'.code),
            )
        }

        @Test
        fun `eraseCharacters_clearsWithoutShifting`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")
            state.cursor.col = 1

            writer.eraseCharacters(2)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('A'.code, TerminalConstants.EMPTY, TerminalConstants.EMPTY, 'D'.code, 'E'.code),
            )
        }

        @Test
        fun `eraseCharacters_clampsToRightBoundary`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")
            state.cursor.col = 3

            writer.eraseCharacters(99)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('A'.code, 'B'.code, 'C'.code, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
            )
        }

        @Test
        fun `eraseCharacters_respectsLeftRightMargins`() {
            val state = createState(width = 8, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDEFGH")
            state.modes.isLeftRightMarginMode = true
            state.activeBuffer.setLeftRightMargins(left = 3, right = 6, viewportWidth = 8)
            state.cursor.col = 4

            writer.eraseCharacters(99)

            assertLineCodepoints(
                state,
                0,
                intArrayOf(
                    'A'.code,
                    'B'.code,
                    'C'.code,
                    'D'.code,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                    'G'.code,
                    'H'.code,
                ),
            )
        }

        @Test
        fun `eraseCharacters_annihilatesWideLeaderAndSpacer`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('Z'.code, 1)
            state.cursor.col = 2
            state.cursor.pendingWrap = false

            writer.eraseCharacters(1)

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals('Z'.code, lineAt(state, 0).getCodepoint(3)) },
            )
        }

        @Test
        fun `eraseLineToEnd on spacer annihilates owning cluster`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('B'.code, 1)
            state.cursor.col = 1

            writer.eraseLineToEnd()

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(3)) },
            )
        }

        @Test
        fun `eraseLineToCursor on spacer clears owning cluster and prefix`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('Z'.code, 1)
            state.cursor.col = 2

            writer.eraseLineToCursor()

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals('Z'.code, lineAt(state, 0).getCodepoint(3)) },
            )
        }

        @Test
        fun `eraseCurrentLine clears the whole active row`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABCD")
            state.cursor.row = 0

            writer.eraseCurrentLine()

            for (col in 0 until 4) {
                assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(col))
            }
        }

        @Test
        fun `eraseLineToEnd breaks the soft-wrap flag to prevent ghost concatenation`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            lineAt(state, 0).wrapped = true // Manually simulate a line that was soft-wrapped

            state.cursor.col = 2
            writer.eraseLineToEnd()

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertFalse(lineAt(state, 0).wrapped, "Erase to end MUST break the soft-wrap flag") },
            )
        }

        @Test
        fun `eraseCurrentLine breaks the soft-wrap flag`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            lineAt(state, 0).wrapped = true

            writer.eraseCurrentLine()

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertFalse(lineAt(state, 0).wrapped, "Erase whole line MUST break the soft-wrap flag") },
            )
        }
    }

    @Nested
    @DisplayName("insertBlankCharacters")
    inner class InsertBlankCharactersTests {
        @Test
        fun `non-positive count is no-op`() {
            val state = createState(width = 5, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABCDE")
            state.cursor.row = 0
            state.cursor.col = 2

            writer.insertBlankCharacters(0)
            writer.insertBlankCharacters(-1)

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals('B'.code, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals('C'.code, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals('D'.code, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals('E'.code, lineAt(state, 0).getCodepoint(4)) },
            )
        }

        @Test
        fun `insert on normal cell shifts content to the right`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABCD")
            state.cursor.col = 1

            writer.insertBlankCharacters(2)

            assertAll(
                { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals('B'.code, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals('C'.code, lineAt(state, 0).getCodepoint(4)) },
                { assertEquals('D'.code, lineAt(state, 0).getCodepoint(5)) },
            )
        }

        @Test
        fun `insert on spacer annihilates owner then shifts remainder`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)

            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('C'.code, 1)
            writer.printCodepoint('D'.code, 1)
            state.cursor.col = 1

            writer.insertBlankCharacters(1)

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals('C'.code, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals('D'.code, lineAt(state, 0).getCodepoint(4)) },
            )
        }

        @Test
        fun `insert is no-op when cursor is out of bounds`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABCD")
            state.cursor.col = 99

            writer.insertBlankCharacters(2)

            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code, 'D'.code))
        }

        @Test
        fun `insert clamps when count exceeds remaining width`() {
            val state = createState(width = 5, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")
            state.cursor.row = 0
            state.cursor.col = 3

            writer.insertBlankCharacters(99)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('A'.code, 'B'.code, 'C'.code, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
            )
        }
    }

    @Nested
    @DisplayName("deleteCharacters")
    inner class DeleteCharactersTests {
        @Test
        fun `non-positive count is no-op`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")
            state.cursor.col = 2

            writer.deleteCharacters(0)
            writer.deleteCharacters(-2)

            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code, 'D'.code, 'E'.code))
        }

        @Test
        fun `is no-op when cursor is out of bounds`() {
            val state = createState(width = 5, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")

            state.cursor.col = 99
            writer.deleteCharacters(1)
            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code, 'D'.code, 'E'.code))

            state.cursor.col = 1
            state.cursor.row = 99
            writer.deleteCharacters(1)
            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code, 'D'.code, 'E'.code))
        }

        @Test
        fun `deletes from cursor, shifts left, and fills trailing cells with pen attr`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDEF", attr = 10)
            state.pen.setAttributes(fg = 3, bg = 4, underlineStyle = io.github.jvterm.core.model.UnderlineStyle.SINGLE)
            val fillAttr = state.pen.currentAttr
            state.cursor.col = 1

            writer.deleteCharacters(2)

            assertAll(
                {
                    assertLineCodepoints(
                        state,
                        0,
                        intArrayOf('A'.code, 'D'.code, 'E'.code, 'F'.code, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
                { assertLineAttrs(state, 0, longArrayOf(10, 10, 10, 10, fillAttr, fillAttr)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
            )
        }

        @Test
        fun `count larger than remaining width is clamped`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCDE")
            state.cursor.col = 3

            writer.deleteCharacters(99)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('A'.code, 'B'.code, 'C'.code, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
            )
        }

        @Test
        fun `cursor on wide leader annihilates full cluster before delete`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('B'.code, 1)
            state.cursor.col = 1

            writer.deleteCharacters(1)

            assertLineCodepoints(
                state,
                0,
                intArrayOf(
                    'A'.code,
                    TerminalConstants.EMPTY,
                    'B'.code,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                ),
            )
        }

        @Test
        fun `cursor on wide spacer annihilates owning leader before delete`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('B'.code, 1)
            state.cursor.col = 2

            writer.deleteCharacters(1)

            assertLineCodepoints(
                state,
                0,
                intArrayOf(
                    'A'.code,
                    TerminalConstants.EMPTY,
                    'B'.code,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                ),
            )
        }

        @Test
        fun `right-boundary spacer is annihilated to prevent orphaned wide leader`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint('B'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('C'.code, 1)
            state.cursor.col = 1

            writer.deleteCharacters(2)

            assertLineCodepoints(
                state,
                0,
                intArrayOf(
                    'A'.code,
                    TerminalConstants.EMPTY,
                    'C'.code,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                ),
            )
        }

        @Test
        fun `ordinary right-boundary character is preserved and shifted`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint('B'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('C'.code, 1)
            state.cursor.col = 1

            writer.deleteCharacters(1)

            assertLineCodepoints(
                state,
                0,
                intArrayOf(
                    'A'.code,
                    0x1F600,
                    TerminalConstants.WIDE_CHAR_SPACER,
                    'C'.code,
                    TerminalConstants.EMPTY,
                    TerminalConstants.EMPTY,
                ),
            )
        }
    }

    @Nested
    @DisplayName("insertLines")
    inner class InsertLinesTests {
        @Test
        fun `insertLines non-positive count is no-op`() {
            val state = createState(width = 3, height = 3)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            state.cursor.row = 1

            writer.insertLines(0)
            writer.insertLines(-10)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                { assertLineCodepoints(state, 2, intArrayOf('C'.code, 'C'.code, 'C'.code)) },
            )
        }

        @Test
        fun `insertLines is ignored when cursor is outside active scroll region`() {
            val state = createState(width = 3, height = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 2)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            seedLine(state, 3, "DDD")

            state.cursor.row = 0
            writer.insertLines(1)
            state.cursor.row = 3
            writer.insertLines(1)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                { assertLineCodepoints(state, 2, intArrayOf('C'.code, 'C'.code, 'C'.code)) },
                { assertLineCodepoints(state, 3, intArrayOf('D'.code, 'D'.code, 'D'.code)) },
            )
        }

        @Test
        fun `insertLines shifts down from cursor through bottom and clears inserted row`() {
            val state = createState(width = 3, height = 5)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 3)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            seedLine(state, 3, "DDD")
            seedLine(state, 4, "EEE")
            state.cursor.row = 2

            writer.insertLines(1)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                {
                    assertLineCodepoints(
                        state,
                        2,
                        intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
                { assertLineCodepoints(state, 3, intArrayOf('C'.code, 'C'.code, 'C'.code)) },
                { assertLineCodepoints(state, 4, intArrayOf('E'.code, 'E'.code, 'E'.code)) },
            )
        }

        @Test
        fun `insertLines count is clamped to remaining region height`() {
            val state = createState(width = 3, height = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 3)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            seedLine(state, 3, "DDD")
            state.cursor.row = 2

            writer.insertLines(99)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                {
                    assertLineCodepoints(
                        state,
                        2,
                        intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
                {
                    assertLineCodepoints(
                        state,
                        3,
                        intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
            )
        }

        @Test
        fun `insertLines at bottom margin clears only bottom row`() {
            val state = createState(width = 3, height = 3)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            state.cursor.row = 2

            writer.insertLines(5)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                {
                    assertLineCodepoints(
                        state,
                        2,
                        intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
            )
        }

        @Test
        fun `insertLines clears inserted rows using current pen attribute`() {
            val state = createState(width = 3, height = 3)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AAA", attr = 11)
            seedLine(state, 1, "BBB", attr = 11)
            seedLine(state, 2, "CCC", attr = 11)
            state.pen.setAttributes(fg = 5, bg = 2, bold = true)
            val clearAttr = state.pen.currentAttr
            state.cursor.row = 1

            writer.insertLines(1)

            assertLineAttrs(state, 1, longArrayOf(clearAttr, clearAttr, clearAttr))
            assertLineCodepoints(state, 2, intArrayOf('B'.code, 'B'.code, 'B'.code))
        }

        @Test
        fun `insertLines uses resolveRingIndex when viewport has history offset`() {
            val state = createState(width = 2, height = 2, history = 4)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AA")
            seedLine(state, 1, "BB")
            writer.scrollUp() // grow ring so viewport start index is no longer zero
            seedLine(state, 0, "CC")
            seedLine(state, 1, "DD")
            state.cursor.row = 0
            val oldSize = state.ring.size

            writer.insertLines(1)

            assertAll(
                { assertEquals(oldSize, state.ring.size, "insertLines must not push to history") },
                { assertLineCodepoints(state, 0, intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY)) },
                { assertLineCodepoints(state, 1, intArrayOf('C'.code, 'C'.code)) },
            )
        }
    }

    @Nested
    @DisplayName("deleteLines")
    inner class DeleteLinesTests {
        @Test
        fun `deleteLines non-positive count is no-op`() {
            val state = createState(width = 3, height = 3)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            state.cursor.row = 1

            writer.deleteLines(0)
            writer.deleteLines(-3)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                { assertLineCodepoints(state, 2, intArrayOf('C'.code, 'C'.code, 'C'.code)) },
            )
        }

        @Test
        fun `deleteLines is ignored when cursor is outside active scroll region`() {
            val state = createState(width = 3, height = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 2)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            seedLine(state, 3, "DDD")

            state.cursor.row = 0
            writer.deleteLines(1)
            state.cursor.row = 3
            writer.deleteLines(1)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                { assertLineCodepoints(state, 2, intArrayOf('C'.code, 'C'.code, 'C'.code)) },
                { assertLineCodepoints(state, 3, intArrayOf('D'.code, 'D'.code, 'D'.code)) },
            )
        }

        @Test
        fun `count larger than remaining height is clamped`() {
            val state = createState(width = 3, height = 4)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            seedLine(state, 3, "DDD")
            state.cursor.row = 2

            writer.deleteLines(99)

            assertAll(
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'A'.code, 'A'.code)) },
                { assertLineCodepoints(state, 1, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                {
                    assertLineCodepoints(
                        state,
                        2,
                        intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
                {
                    assertLineCodepoints(
                        state,
                        3,
                        intArrayOf(TerminalConstants.EMPTY, TerminalConstants.EMPTY, TerminalConstants.EMPTY),
                    )
                },
            )
        }
    }

    @Nested
    @DisplayName("insert mode (IRM)")
    inner class InsertModeTests {
        @Test
        fun `replace mode default does not shift`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            state.cursor.col = 1
            state.modes.isInsertMode = false

            writer.printCodepoint('X'.code, 1)

            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'X'.code, 'C'.code, 'D'.code))
            assertEquals(2, state.cursor.col)
        }

        @Test
        fun `insert mode single width shifts right before write`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            state.cursor.col = 1
            state.modes.isInsertMode = true

            writer.printCodepoint('X'.code, 1)

            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'X'.code, 'B'.code, 'C'.code, 'D'.code))
            assertEquals(2, state.cursor.col)
        }

        @Test
        fun `insert mode at col zero shifts entire line`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            state.cursor.col = 0
            state.modes.isInsertMode = true

            writer.printCodepoint('X'.code, 1)

            assertLineCodepoints(state, 0, intArrayOf('X'.code, 'A'.code, 'B'.code, 'C'.code))
            assertEquals(1, state.cursor.col)
        }

        @Test
        fun `insert mode at last column wraps cursor after write`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            state.cursor.col = 3
            state.modes.isInsertMode = true

            writer.printCodepoint('X'.code, 1)
            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code, 'X'.code))
            assertAll(
                { assertEquals(3, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertTrue(state.cursor.pendingWrap) },
            )

            writer.printCodepoint('Y'.code, 1)
            assertAll(
                { assertTrue(lineAt(state, 0).wrapped) },
                { assertEquals('Y'.code, lineAt(state, 1).getCodepoint(0)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(1, state.cursor.row) },
            )
        }

        @Test
        fun `insert mode wide char inserts two cells`() {
            val state = createState(width = 6, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            state.cursor.col = 1
            state.modes.isInsertMode = true

            writer.printCodepoint(0x1F600, 2)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('A'.code, 0x1F600, TerminalConstants.WIDE_CHAR_SPACER, 'B'.code, 'C'.code, 'D'.code),
            )
            assertEquals(3, state.cursor.col)
        }

        @Test
        fun `insert mode on wide spacer annihilates owner then inserts`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('C'.code, 1)
            writer.printCodepoint('D'.code, 1)

            state.cursor.col = 1
            state.modes.isInsertMode = true

            writer.printCodepoint('X'.code, 1)

            assertLineCodepoints(
                state,
                0,
                intArrayOf(TerminalConstants.EMPTY, 'X'.code, TerminalConstants.EMPTY, 'C'.code, 'D'.code),
            )
        }

        @Test
        fun `insert mode before existing wide leader preserves spacer invariant`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint(0x1F600, 2)
            writer.printCodepoint('B'.code, 1)

            state.cursor.col = 0
            state.modes.isInsertMode = true

            writer.printCodepoint('X'.code, 1)

            assertLineCodepoints(
                state,
                0,
                intArrayOf('X'.code, 'A'.code, 0x1F600, TerminalConstants.WIDE_CHAR_SPACER, 'B'.code),
            )
        }

        @Test
        fun `insert mode print cluster shifts then stores cluster`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABC")
            state.cursor.col = 1
            state.modes.isInsertMode = true

            val cpArray = intArrayOf('X'.code, 0x0301)
            writer.printCluster(cpArray, 2, 1)

            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'X'.code, 'B'.code, 'C'.code))
            assertTrue(lineAt(state, 0).isCluster(1))
            assertEquals(2, state.cursor.col)
        }

        @Test
        fun `insert mode wide edge wrap mutates wrapped row not old row`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AB")
            seedLine(state, 1, "CD")
            state.cursor.row = 0
            state.cursor.col = 2
            state.modes.isInsertMode = true

            writer.printCodepoint(0x1F600, 2)

            assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, TerminalConstants.EMPTY))
            assertLineCodepoints(state, 1, intArrayOf(0x1F600, TerminalConstants.WIDE_CHAR_SPACER, 'C'.code))
            assertEquals(2, state.cursor.col)
            assertEquals(1, state.cursor.row)
        }

        @Test
        fun `insert mode wide edge wrap on bottom row scrolls then inserts on new line`() {
            val state = createState(width = 3, height = 1, history = 4)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AB")
            state.cursor.row = 0
            state.cursor.col = 2
            state.modes.isInsertMode = true

            writer.printCodepoint(0x1F600, 2)

            assertTrue(state.ring.size >= 2)
            assertLineCodepoints(state, 0, intArrayOf(0x1F600, TerminalConstants.WIDE_CHAR_SPACER, TerminalConstants.EMPTY))
            assertEquals(2, state.cursor.col)
            assertEquals(0, state.cursor.row)
        }

        @Test
        fun `insert mode preserves current pen attr on inserted blanks`() {
            val state = createState(width = 5, height = 1)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD", attr = 10)
            state.pen.setAttributes(fg = 5, bg = 2, bold = true)
            val newAttr = state.pen.currentAttr

            state.cursor.col = 1
            state.modes.isInsertMode = true

            writer.printCodepoint('X'.code, 1)

            assertLineAttrs(state, 0, longArrayOf(10, newAttr, 10, 10, 10))
        }
    }

    // ── NEW TESTS ──────────────────────────────────────────────────────────────

    // ── structuralMutation protocol ────────────────────────────────────────────

    @Nested
    @DisplayName("structuralMutation: pending wrap cancellation")
    inner class StructuralMutationProtocolTests {
        @Test
        fun `non-positive count no-op does NOT cancel pendingWrap`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABCD") // last write lands at col=3 → pendingWrap=true
            assertTrue(state.cursor.pendingWrap)

            writer.insertBlankCharacters(0)
            writer.deleteCharacters(0)
            writer.insertLines(0)
            writer.deleteLines(0)

            assertTrue(state.cursor.pendingWrap, "Pending wrap must survive no-op structural calls")
        }

        @Test
        fun `structural mutation with positive count DOES cancel pendingWrap`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "ABCD") // pendingWrap=true, col=3
            assertTrue(state.cursor.pendingWrap)

            // col=3 is valid for insertBlankCharacters — no need to move cursor
            writer.insertBlankCharacters(1)

            assertFalse(state.cursor.pendingWrap, "structuralMutation block must cancel pendingWrap")
        }

        @Test
        fun `eraseLineToEnd cancels pendingWrap`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)

            writer.eraseLineToEnd()

            assertFalse(state.cursor.pendingWrap)
        }

        @Test
        fun `eraseCurrentLine cancels pendingWrap`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)

            writer.eraseCurrentLine()

            assertFalse(state.cursor.pendingWrap)
        }

        @Test
        fun `eraseCharacters cancels pendingWrap`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)

            writer.eraseCharacters(1)

            assertFalse(state.cursor.pendingWrap)
        }

        @Test
        fun `newLine cancels pendingWrap`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)

            writer.newLine()

            assertFalse(state.cursor.pendingWrap)
        }

        @Test
        fun `reverseLineFeed cancels pendingWrap`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)
            state.cursor.row = 1

            writer.reverseLineFeed()

            assertFalse(state.cursor.pendingWrap)
        }

        @Test
        fun `scrollUp cancels pendingWrap`() {
            val state = createState(width = 2, height = 2, history = 4)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)

            writer.scrollUp()

            assertFalse(state.cursor.pendingWrap)
        }

        @Test
        fun `scrollDown cancels pendingWrap`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            writeAscii(writer, "AB")
            assertTrue(state.cursor.pendingWrap)

            writer.scrollDown()

            assertFalse(state.cursor.pendingWrap)
        }
    }

    @Nested
    @DisplayName("DECAWM disabled (no auto-wrap)")
    inner class DecawmDisabledTests {
        @Test
        fun `single-width at last col clamps in place, no pendingWrap`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            state.modes.isAutoWrap = false
            state.cursor.col = 2

            writer.printCodepoint('A'.code, 1)
            writer.printCodepoint('B'.code, 1)

            assertAll(
                { assertEquals('B'.code, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(2, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertFalse(state.cursor.pendingWrap) },
            )
        }

        @Test
        fun `wide char at col width-2 does NOT set pendingWrap`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            state.modes.isAutoWrap = false
            state.cursor.col = 2

            writer.printCodepoint(0x1F600, 2)

            assertAll(
                { assertEquals(0x1F600, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals(3, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertFalse(state.cursor.pendingWrap) },
            )
        }

        @Test
        fun `fast path single-width at last col does not set pendingWrap`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            state.modes.isAutoWrap = false
            writeAscii(writer, "ABC")
            assertEquals(3, state.cursor.col)

            writer.printCodepoint('D'.code, 1)

            assertAll(
                { assertEquals('D'.code, lineAt(state, 0).getCodepoint(3)) },
                { assertEquals(3, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertFalse(state.cursor.pendingWrap) },
            )
        }

        @Test
        fun `slow path - overwrite at last col with DECAWM-false does not wrap`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            state.modes.isAutoWrap = false
            seedLine(state, 0, "ABC")
            state.cursor.col = 2

            writer.printCodepoint('X'.code, 1)

            assertAll(
                { assertEquals('X'.code, lineAt(state, 0).getCodepoint(2)) },
                { assertEquals(2, state.cursor.col) },
                { assertEquals(0, state.cursor.row) },
                { assertFalse(state.cursor.pendingWrap) },
            )
        }

        @Test
        fun `wide char at absolute right edge with DECAWM off is safely ignored and does not crash`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            state.modes.isAutoWrap = false

            // Move cursor to the absolute final column (width - 1)
            state.cursor.col = 3

            // This would historically throw an IndexOutOfBoundsException because the wide
            // char needs 2 cells but auto-wrap is forbidden.
            assertDoesNotThrow {
                writer.printCodepoint(0x1F600, 2)
            }

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(3), "Char should be safely ignored") },
                { assertEquals(3, state.cursor.col, "Cursor must remain clamped at edge") },
                { assertFalse(state.cursor.pendingWrap, "Pending wrap must remain false") },
            )
        }
    }

    @Nested
    @DisplayName("scroll region awareness")
    inner class ScrollRegionTests {
        @Test
        fun `newLine inside region scrolls region, not full viewport`() {
            val state = createState(width = 3, height = 4, history = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 2)
            seedLine(state, 0, "AAA")
            seedLine(state, 1, "BBB")
            seedLine(state, 2, "CCC")
            seedLine(state, 3, "DDD")
            state.cursor.row = 2

            writer.newLine()

            assertAll(
                { assertLineCodepoints(state, 1, intArrayOf('C'.code, 'C'.code, 'C'.code)) },
                { assertLineCodepoints(state, 2, intArrayOf(0, 0, 0)) },
                { assertEquals(2, state.cursor.row) },
            )
        }

        @Test
        fun `newLine below scrollBottom just moves cursor down without scroll`() {
            val state = createState(width = 3, height = 4, history = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 2)
            seedLine(state, 3, "DDD")
            state.cursor.row = 3

            writer.newLine()

            assertAll(
                { assertEquals(3, state.cursor.row) },
                { assertLineCodepoints(state, 3, intArrayOf('D'.code, 'D'.code, 'D'.code)) },
            )
        }

        @Test
        fun `reverseLineFeed inside region but not at scrollTop just moves up`() {
            val state = createState(width = 3, height = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 3)
            state.cursor.row = 2
            state.cursor.col = 1

            writer.reverseLineFeed()

            assertAll(
                { assertEquals(1, state.cursor.row) },
                { assertEquals(1, state.cursor.col) },
            )
        }

        @Test
        fun `reverseLineFeed at scrollTop scrolls region down, not full viewport`() {
            val state = createState(width = 3, height = 4, history = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 2)
            seedLine(state, 1, "BBB")
            state.cursor.row = 1

            writer.reverseLineFeed()

            assertAll(
                { assertLineCodepoints(state, 1, intArrayOf(0, 0, 0)) },
                { assertLineCodepoints(state, 2, intArrayOf('B'.code, 'B'.code, 'B'.code)) },
                { assertEquals(1, state.cursor.row) },
            )
        }

        @Test
        fun `wide char write at bottom of scroll region triggers region scroll not viewport scroll`() {
            val state = createState(width = 4, height = 4, history = 4)
            val writer = MutationEngine(state)
            setScrollRegion(state, top = 1, bottom = 2)
            state.cursor.row = 2
            state.cursor.col = 3

            writer.printCodepoint(0x1F600, 2)

            assertAll(
                { assertLineCodepoints(state, 3, intArrayOf(0, 0, 0, 0)) },
            )
        }
    }

    @Nested
    @DisplayName("findClusterStart / annihilateAt edge cases")
    inner class AnnihilateEdgeCases {
        @Test
        fun `annihilate on col 0 wide leader clears both cols`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            writer.printCodepoint(0x1F600, 2)
            state.cursor.col = 0
            state.cursor.pendingWrap = false

            writer.printCodepoint('X'.code, 1)

            assertAll(
                { assertEquals('X'.code, lineAt(state, 0).getCodepoint(0)) },
                { assertEquals(0, lineAt(state, 0).getCodepoint(1)) },
            )
        }

        @Test
        fun `orphaned spacer at col 0 is cleared as standalone`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            lineAt(state, 0).setCell(0, TerminalConstants.WIDE_CHAR_SPACER, 0)
            state.cursor.col = 0

            writer.printCodepoint('A'.code, 1)

            assertEquals('A'.code, lineAt(state, 0).getCodepoint(0))
        }

        @Test
        fun `annihilate at last column wide leader does not read out of bounds`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            lineAt(state, 0).setCell(3, 0x1F600, 0)
            state.cursor.col = 3
            state.cursor.pendingWrap = false

            writer.printCodepoint('Z'.code, 1)

            assertEquals('Z'.code, lineAt(state, 0).getCodepoint(3))
        }
    }

    @Nested
    @DisplayName("deferred wrap interaction")
    inner class DeferredWrapInteractionTests {
        @Test
        fun `deleteCharacters after pendingWrap cancels wrap and mutates correct row`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABC")
            state.cursor.row = 0
            state.cursor.col = 2
            state.cursor.pendingWrap = true

            writer.deleteCharacters(1)

            assertAll(
                { assertFalse(state.cursor.pendingWrap) },
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 0)) },
                { assertEquals(0, state.cursor.row) },
                { assertEquals(2, state.cursor.col) },
            )
        }

        @Test
        fun `insertBlankCharacters after pendingWrap cancels wrap and mutates correct row`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABCD")
            state.cursor.row = 0
            state.cursor.col = 3
            state.cursor.pendingWrap = true

            writer.insertBlankCharacters(1)

            assertAll(
                { assertFalse(state.cursor.pendingWrap) },
                { assertLineCodepoints(state, 0, intArrayOf('A'.code, 'B'.code, 'C'.code, 0)) },
            )
        }

        @Test
        fun `eraseLineToCursor after pendingWrap clears through col, not next row`() {
            val state = createState(width = 3, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "ABC")
            state.cursor.row = 0
            state.cursor.col = 2
            state.cursor.pendingWrap = true

            writer.eraseLineToCursor()

            assertAll(
                { assertFalse(state.cursor.pendingWrap) },
                { assertLineCodepoints(state, 0, intArrayOf(0, 0, 0)) },
            )
        }

        @Test
        fun `next print after pendingWrap advances to new row before writing`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            seedLine(state, 0, "AB")
            state.cursor.row = 0
            state.cursor.col = 1
            state.cursor.pendingWrap = true

            writer.printCodepoint('X'.code, 1)

            assertAll(
                { assertTrue(lineAt(state, 0).wrapped) },
                { assertEquals('X'.code, lineAt(state, 1).getCodepoint(0)) },
                { assertEquals(1, state.cursor.col) },
                { assertEquals(1, state.cursor.row) },
                { assertFalse(state.cursor.pendingWrap) },
            )
        }
    }

    @Nested
    @DisplayName("clearViewport and clearAllHistory pen attribute")
    inner class ClearPenAttributeTests {
        @Test
        fun `clearViewport fills lines with current pen attribute`() {
            val state = createState(width = 2, height = 2)
            val writer = MutationEngine(state)
            state.pen.setAttributes(fg = 3, bg = 5, bold = true)
            val clearAttr = state.pen.currentAttr

            writer.clearViewport()

            assertLineAttrs(state, 0, longArrayOf(clearAttr, clearAttr))
            assertLineAttrs(state, 1, longArrayOf(clearAttr, clearAttr))
        }

        @Test
        fun `clearAllHistory fills new lines with current pen attribute`() {
            val state = createState(width = 2, height = 2, history = 4)
            val writer = MutationEngine(state)
            state.pen.setAttributes(fg = 2, bg = 7, italic = true)
            val clearAttr = state.pen.currentAttr

            writer.clearAllHistory()

            assertLineAttrs(state, 0, longArrayOf(clearAttr, clearAttr))
            assertLineAttrs(state, 1, longArrayOf(clearAttr, clearAttr))
        }
    }

    @Nested
    @DisplayName("printCluster: overwrite and arena safety")
    inner class PrintClusterArenaTests {
        @Test
        fun `overwriting a cluster slot with a regular char frees the cluster`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            val cpArray = intArrayOf('A'.code, 0x0301)
            writer.printCluster(cpArray, 2, 1)
            assertTrue(lineAt(state, 0).isCluster(0))

            state.cursor.col = 0
            state.cursor.pendingWrap = false
            writer.printCodepoint('B'.code, 1)

            assertFalse(lineAt(state, 0).isCluster(0))
            assertEquals('B'.code, lineAt(state, 0).getCodepoint(0))
        }

        @Test
        fun `overwriting a cluster with another cluster stores new cluster`() {
            val state = createState(width = 4, height = 1)
            val writer = MutationEngine(state)
            val first = intArrayOf('A'.code, 0x0301)
            val second = intArrayOf('E'.code, 0x0301)
            writer.printCluster(first, 2, 1)

            state.cursor.col = 0
            state.cursor.pendingWrap = false
            writer.printCluster(second, 2, 1)

            assertTrue(lineAt(state, 0).isCluster(0))
            assertEquals('E'.code, lineAt(state, 0).getCodepoint(0))
        }

        @Test
        fun `wide cluster at width minus two stores leader spacer and advances correctly`() {
            val state = createState(width = 4, height = 2)
            val writer = MutationEngine(state)
            state.cursor.col = 2

            val cpArray = intArrayOf(0x1F600, 0xFE0F)
            writer.printCluster(cpArray, 2, 2)

            assertAll(
                { assertTrue(lineAt(state, 0).isCluster(2)) },
                { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).rawCodepoint(3)) },
                { assertEquals(3, state.cursor.col) },
                { assertTrue(state.cursor.pendingWrap) },
            )
        }
    }

    @Nested
    @DisplayName("decaln (DEC Screen Alignment Test)")
    inner class DecalnTests {
        @Test
        fun `fills screen with E characters, resets margins, and homes cursor`() {
            val state = createState(width = 5, height = 3)
            val writer = MutationEngine(state)

            // Seed some characters
            seedLine(state, 0, "ABCDE")
            seedLine(state, 1, "FGHIJ")
            seedLine(state, 2, "KLMNO")

            // Set some non-default margins
            state.activeBuffer.setScrollRegion(2, 3, false, 3)
            state.modes.isLeftRightMarginMode = true
            state.activeBuffer.setLeftRightMargins(2, 4, 5)

            // Put cursor in an offset state
            state.cursor.row = 1
            state.cursor.col = 2
            state.cursor.pendingWrap = true

            // Trigger DECALN
            writer.decaln()

            // Verify screen contents
            for (row in 0 until 3) {
                for (col in 0 until 5) {
                    assertEquals('E'.code, lineAt(state, row).getCodepoint(col))
                }
            }

            // Verify margins are reset to full screen
            assertEquals(0, state.activeBuffer.scrollTop)
            assertEquals(2, state.activeBuffer.scrollBottom)
            assertEquals(0, state.activeBuffer.leftMargin)
            assertEquals(4, state.activeBuffer.rightMargin)

            // Verify cursor is homed
            assertEquals(0, state.cursor.row)
            assertEquals(0, state.cursor.col)
            assertFalse(state.cursor.pendingWrap)
        }
    }
}
