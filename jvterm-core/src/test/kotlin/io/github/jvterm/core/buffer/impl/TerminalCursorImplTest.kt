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
package io.github.jvterm.core.buffer.impl

import io.github.jvterm.core.engine.CursorEngine
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalCursorImplTest {
    @Test
    fun `moves the cursor and clamps to bounds`() {
        val state = TerminalState(5, 4, 2)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))

        cursor.positionCursor(2, 1)
        cursor.cursorRight(10)
        cursor.cursorDown(10)
        cursor.cursorLeft(99)
        cursor.cursorUp(99)

        assertAll(
            { assertEquals(0, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
        )
    }

    @Test
    fun `save and restore cursor round trip saved state`() {
        val state = TerminalState(4, 3, 2)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))

        state.pen.setAttributes(2, 3, bold = true, italic = false, underlineStyle = UnderlineStyle.SINGLE)
        cursor.positionCursor(3, 2)
        cursor.saveCursor()

        cursor.positionCursor(0, 0)
        state.pen.reset()
        cursor.restoreCursor()

        assertAll(
            { assertEquals(3, state.cursor.col) },
            { assertEquals(2, state.cursor.row) },
        )
    }

    @Test
    fun `tab stop helpers delegate to shared tab stop state`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))

        cursor.positionCursor(10, 0)
        cursor.setTabStop()
        cursor.resetCursor()
        cursor.horizontalTab()

        assertEquals(8, state.cursor.col)
    }

    @Test
    fun `set clear and clearAll tab stop helpers round trip through the facade semantics`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))

        cursor.clearAllTabStops()
        cursor.positionCursor(5, 0)
        cursor.setTabStop()
        cursor.resetCursor()
        cursor.horizontalTab()
        assertEquals(5, state.cursor.col)

        cursor.positionCursor(5, 0)
        cursor.clearTabStop()
        cursor.resetCursor()
        cursor.horizontalTab()
        assertEquals(19, state.cursor.col)
    }

    @Test
    fun `cursorForwardTab advances through multiple stops via the facade`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))

        cursor.positionCursor(0, 0)
        cursor.cursorForwardTab(2)

        assertEquals(16, state.cursor.col)
    }

    @Test
    fun `cursorBackwardTab moves through stops via the facade`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))

        cursor.positionCursor(19, 0)
        cursor.cursorBackwardTab(2)

        assertEquals(8, state.cursor.col)
    }

    @Test
    fun `setTabStop_cancelsPendingWrap`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))
        state.cursor.col = 19
        state.cursor.pendingWrap = true

        cursor.setTabStop()

        assertFalse(state.cursor.pendingWrap)
    }

    @Test
    fun `clearTabStop_cancelsPendingWrap`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))
        state.cursor.col = 8
        state.cursor.pendingWrap = true

        cursor.clearTabStop()

        assertFalse(state.cursor.pendingWrap)
    }

    @Test
    fun `clearAllTabStops_cancelsPendingWrap`() {
        val state = TerminalState(20, 1, 0)
        val cursor = TerminalCursorImpl(state, CursorEngine(state))
        state.cursor.pendingWrap = true

        cursor.clearAllTabStops()

        assertFalse(state.cursor.pendingWrap)
    }
}
