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

import io.github.jvterm.core.api.TerminalInspector
import io.github.jvterm.core.codec.AttributeCodec
import io.github.jvterm.core.model.Attributes
import io.github.jvterm.core.model.Line
import io.github.jvterm.core.state.TerminalState

internal class TerminalInspectorImpl(
    private val state: TerminalState,
) : TerminalInspector {
    override fun getAttrAt(
        col: Int,
        row: Int,
    ): Attributes? {
        if (!state.dimensions.isValidCol(col) || !state.dimensions.isValidRow(row)) return null
        val line = visibleLine(row) ?: return null
        val rawAttr = if (line.width == 0) state.pen.currentAttr else line.getPackedAttr(col)
        val rawExtendedAttr =
            if (line.width == 0) {
                state.pen.currentExtendedAttr
            } else {
                line.getPackedExtendedAttr(col)
            }
        return AttributeCodec.unpack(rawAttr, rawExtendedAttr)
    }

    override fun getLineAsString(row: Int): String = visibleLine(row)?.toTextTrimmed() ?: ""

    override fun getScreenAsString(): String =
        buildString {
            for (row in 0 until state.dimensions.height) {
                if (row > 0) append('\n')
                append(getLineAsString(row))
            }
        }

    override fun getAllAsString(): String {
        val sb = StringBuilder()
        for (row in 0 until state.ring.size) {
            if (row > 0) sb.append('\n')
            sb.append(state.ring[row].toTextTrimmed())
        }
        return sb.toString()
    }

    private fun visibleLine(row: Int): Line? {
        if (!state.dimensions.isValidRow(row)) return null
        return state.ring[state.resolveRingIndex(row)]
    }
}
