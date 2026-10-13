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
package io.github.ketraterm.core.engine

import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CursorReverseWrapTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 45, 1045])
    fun `cursor motion publishes one cursor generation without dirtying content`(mode: Int) {
        val state = TerminalState(4, 3, maxHistory = 0)
        val cursor = CursorEngine(state)
        val writer = MutationEngine(state)
        for (c in "ABCDE") writer.printCodepoint(c.code, 1)
        state.modes.isReverseWrap = mode == 45
        state.modes.isReverseWrapExtended = mode == 1045
        val frameGeneration = state.frameGeneration
        val cursorGeneration = state.cursorGeneration
        val contentGeneration = state.contentGeneration
        cursor.cursorLeft(2)
        assertPosition(state, if (mode == 0) 0 else 3, if (mode == 0) 1 else 0)
        assertEquals(frameGeneration + 1, state.frameGeneration)
        assertEquals(cursorGeneration + 1, state.cursorGeneration)
        assertEquals(contentGeneration, state.contentGeneration)
    }

    @Test
    fun `ordinary reverse wrap traverses only the contiguous soft wrapped rows`() {
        val state = TerminalState(4, 4, maxHistory = 0)
        val cursor = CursorEngine(state)
        val writer = MutationEngine(state)
        for (c in "ABCDEFGHI") writer.printCodepoint(c.code, 1)
        state.modes.isReverseWrap = true
        cursor.cursorLeft(2)
        assertPosition(state, 3, 1)
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 0, 0)

        cursor.setCursorAbsolute(0, 3)
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 0, 3)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pending wrap consumes one step only when reverse wrap is active`(extended: Boolean) {
        val state = TerminalState(4, 3, maxHistory = 0)
        val cursor = CursorEngine(state)
        val writer = MutationEngine(state)
        state.modes.isReverseWrap = !extended
        state.modes.isReverseWrapExtended = extended
        for (c in "ABCD") writer.printCodepoint(c.code, 1)
        cursor.cursorLeft(0)
        cursor.cursorLeft(Int.MIN_VALUE)
        assertTrue(state.cursor.pendingWrap)
        val generation = state.frameGeneration
        cursor.cursorLeft(1)
        assertPosition(state, 3, 0)
        assertFalse(state.cursor.pendingWrap)
        assertEquals(generation, state.frameGeneration)
        writer.printCodepoint('X'.code, 1)
        cursor.cursorLeft(3)
        assertPosition(state, 1, 0)
        assertEquals("ABCX", state.ring[state.resolveRingIndex(0)].toTextTrimmed())
    }

    @Test
    fun `default and disabled autowrap clamp and do not consume a phantom step`() {
        val state = TerminalState(4, 3, maxHistory = 0)
        val cursor = CursorEngine(state)
        state.cursor.col = 3
        state.cursor.pendingWrap = true
        cursor.cursorLeft(1)
        assertPosition(state, 2, 0)
        state.modes.isReverseWrap = true
        state.modes.isReverseWrapExtended = true
        state.modes.isAutoWrap = false
        cursor.setCursorAbsolute(0, 2)
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 0, 2)
    }

    @Test
    fun `ordinary mode stops at top margin even when previous viewport row wrapped`() {
        val state = TerminalState(4, 5, maxHistory = 0)
        val cursor = CursorEngine(state)
        state.activeBuffer.setScrollRegion(2, 4, false, 5)
        state.modes.isReverseWrap = true
        for (row in 0..3) state.ring[state.resolveRingIndex(row)].wrapped = true
        cursor.setCursorAbsolute(2, 3)
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 0, 1)
        cursor.setCursorAbsolute(0, 0)
        cursor.cursorLeft(1)
        assertPosition(state, 0, 1)
    }

    @Test
    fun `extended mode takes precedence and cycles inside rectangular margins`() {
        val state = TerminalState(8, 6, maxHistory = 0)
        val cursor = CursorEngine(state)
        state.activeBuffer.setScrollRegion(2, 5, false, 6)
        state.activeBuffer.setLeftRightMargins(3, 6, 8)
        state.modes.isLeftRightMarginMode = true
        state.modes.isReverseWrap = true
        state.modes.isReverseWrapExtended = true
        cursor.setCursorAbsolute(2, 1)
        cursor.cursorLeft(1)
        assertPosition(state, 5, 4)
        cursor.cursorLeft(15)
        assertPosition(state, 2, 1)
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 3, 1)
    }

    @Test
    fun `extended wrap handles positions outside all four margins`() {
        val state = TerminalState(8, 6, maxHistory = 0)
        val cursor = CursorEngine(state)
        state.activeBuffer.setScrollRegion(3, 5, false, 6)
        state.activeBuffer.setLeftRightMargins(3, 6, 8)
        state.modes.isLeftRightMarginMode = true
        state.modes.isReverseWrapExtended = true
        cursor.setCursorAbsolute(1, 3)
        cursor.cursorLeft(2)
        assertPosition(state, 5, 2)
        cursor.setCursorAbsolute(7, 3)
        cursor.cursorLeft(6)
        assertPosition(state, 5, 2)
        cursor.setCursorAbsolute(3, 1)
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 2, 0)
        cursor.setCursorAbsolute(2, 5)
        cursor.cursorLeft(1)
        assertPosition(state, 5, 4)
    }

    @Test
    fun `ordinary wrap uses column zero when starting left of margin`() {
        val state = TerminalState(8, 4, maxHistory = 0)
        val cursor = CursorEngine(state)
        state.activeBuffer.setLeftRightMargins(3, 6, 8)
        state.modes.isLeftRightMarginMode = true
        state.ring[state.resolveRingIndex(0)].wrapped = true
        cursor.setCursorAbsolute(1, 1)
        cursor.cursorLeft(2)
        assertPosition(state, 0, 1)
        state.modes.isReverseWrap = true
        cursor.setCursorAbsolute(1, 1)
        cursor.cursorLeft(2)
        assertPosition(state, 5, 0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reverse motion preserves wide cluster spans until an overwrite`(cluster: Boolean) {
        val state = TerminalState(4, 3, maxHistory = 0)
        val cursor = CursorEngine(state)
        val writer = MutationEngine(state)
        writer.printCodepoint('A'.code, 1)
        writer.printCodepoint('B'.code, 1)
        if (cluster) {
            writer.printCluster(intArrayOf(0x1F469, 0x200D, 0x1F4BB), 3, 2)
        } else {
            writer.printCodepoint(0x754C, 2)
        }
        writer.printCodepoint('C'.code, 1)
        state.modes.isReverseWrap = true
        val line = state.ring[state.resolveRingIndex(0)]
        val generation = line.renderGeneration
        val contentGeneration = state.contentGeneration
        cursor.cursorLeft(2)
        assertPosition(state, 3, 0)
        assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.rawCodepoint(3))
        assertEquals(generation, line.renderGeneration)
        assertEquals(contentGeneration, state.contentGeneration)
        writer.printCodepoint('X'.code, 1)
        assertEquals(TerminalConstants.EMPTY, line.rawCodepoint(2))
        assertEquals('X'.code, line.rawCodepoint(3))
        assertFalse(line.isCluster(2))
    }

    @Test
    fun `reverse motion never enters history or mutates rows`() {
        val state = TerminalState(4, 3, maxHistory = 8)
        val cursor = CursorEngine(state)
        val writer = MutationEngine(state)
        for (c in "ABCDEFGHIJKLMNOP") writer.printCodepoint(c.code, 1)
        state.modes.isReverseWrap = true
        val rows = Array(state.ring.size) { state.ring[it] }
        val generations = LongArray(rows.size) { rows[it].renderGeneration }
        val history = state.historySize
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 0, 0)
        assertEquals(history, state.historySize)
        state.modes.isReverseWrapExtended = true
        cursor.cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 1, 1)
        for (i in rows.indices) {
            assertSame(rows[i], state.ring[i])
            assertEquals(generations[i], rows[i].renderGeneration)
        }
    }

    @Test
    fun `minimum grid extended wrap remains bounded for maximum count`() {
        val state = TerminalState(2, 1, maxHistory = 0)
        state.modes.isReverseWrapExtended = true
        CursorEngine(state).cursorLeft(Int.MAX_VALUE)
        assertPosition(state, 1, 0)
    }

    private fun assertPosition(
        state: TerminalState,
        col: Int,
        row: Int,
    ) {
        assertEquals(col, state.cursor.col, "column")
        assertEquals(row, state.cursor.row, "row")
    }
}
