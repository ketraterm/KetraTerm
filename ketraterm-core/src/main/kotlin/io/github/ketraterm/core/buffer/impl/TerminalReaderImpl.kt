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
package io.github.ketraterm.core.buffer.impl

import io.github.ketraterm.core.api.TerminalLine
import io.github.ketraterm.core.api.TerminalReader
import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.model.VoidLine
import io.github.ketraterm.core.state.TerminalState

internal class TerminalReaderImpl(
    private val state: TerminalState,
) : TerminalReader {
    override val width: Int get() = state.dimensions.width
    override val height: Int get() = state.dimensions.height
    override val windowTitle: String get() = state.windowTitle
    override val iconTitle: String get() = state.iconTitle
    override val cursorCol: Int get() = state.cursor.col
    override val cursorRow: Int get() = state.cursor.row
    override val historySize: Int
        get() = state.historySize

    override fun getLine(row: Int): TerminalLine {
        if (!state.dimensions.isValidRow(row)) return VoidLine
        return state.ring[state.resolveRingIndex(row)]
    }

    override fun getCodepointAt(
        col: Int,
        row: Int,
    ): Int {
        if (!state.dimensions.isValidCol(col)) return TerminalConstants.EMPTY
        val line = getLine(row)
        return if (line.width == 0) TerminalConstants.EMPTY else line.getCodepoint(col)
    }

    override fun getPackedAttrAt(
        col: Int,
        row: Int,
    ): Long {
        if (!state.dimensions.isValidCol(col)) return state.pen.currentAttr
        val line = getLine(row)
        return if (line.width == 0) state.pen.currentAttr else line.getPackedAttr(col)
    }

    override fun getPackedExtendedAttrAt(
        col: Int,
        row: Int,
    ): Long {
        if (!state.dimensions.isValidCol(col)) return state.pen.currentExtendedAttr
        val line = getLine(row)
        return if (line.width == 0) state.pen.currentExtendedAttr else line.getPackedExtendedAttr(col)
    }
}
