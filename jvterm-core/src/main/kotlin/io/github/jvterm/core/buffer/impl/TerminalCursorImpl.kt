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

import io.github.jvterm.core.api.TerminalCursor
import io.github.jvterm.core.engine.CursorEngine
import io.github.jvterm.core.state.TerminalState

internal class TerminalCursorImpl(
    private val state: TerminalState,
    private val cursorEngine: CursorEngine,
) : TerminalCursor {
    override fun positionCursor(
        col: Int,
        row: Int,
    ) = cursorEngine.setCursor(col, row)

    override fun cursorUp(n: Int) = cursorEngine.cursorUp(n)

    override fun cursorDown(n: Int) = cursorEngine.cursorDown(n)

    override fun cursorLeft(n: Int) = cursorEngine.cursorLeft(n)

    override fun cursorRight(n: Int) = cursorEngine.cursorRight(n)

    override fun saveCursor() = cursorEngine.saveCursor()

    override fun restoreCursor() = cursorEngine.restoreCursor()

    override fun resetCursor() = cursorEngine.setCursorAbsolute(0, 0)

    override fun setTabStop() {
        state.cancelPendingWrap()
        state.tabStops.setStop(state.cursor.col)
    }

    override fun clearTabStop() {
        state.cancelPendingWrap()
        state.tabStops.clearStop(state.cursor.col)
    }

    override fun clearAllTabStops() {
        state.cancelPendingWrap()
        state.tabStops.clearAll()
    }

    override fun horizontalTab() = cursorEngine.horizontalTab()

    override fun cursorForwardTab(count: Int) = cursorEngine.cursorForwardTab(count)

    override fun cursorBackwardTab(count: Int) = cursorEngine.cursorBackwardTab(count)
}
