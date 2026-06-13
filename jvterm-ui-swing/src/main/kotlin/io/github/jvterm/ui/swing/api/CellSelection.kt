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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.render.api.TerminalRenderCellFlags
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.api.CellSelection.Companion.NO_RANGE

/**
 * Half-open terminal cell selection in visible render-cache coordinates.
 *
 * [anchorColumn] and [anchorRow] identify the fixed selection edge.
 * [caretColumn] and [caretRow] identify the moving edge and may be before the
 * anchor for backward selections. Columns are caret positions between cells, so
 * selecting cells 2 through 4 on one row is represented as columns 2..5.
 *
 * @property anchorColumn zero-based anchor caret column.
 * @property anchorRow zero-based anchor row.
 * @property caretColumn zero-based moving caret column.
 * @property caretRow zero-based moving row.
 */
data class CellSelection(
    val anchorColumn: Int,
    val anchorRow: Int,
    val caretColumn: Int,
    val caretRow: Int,
    val isBlock: Boolean = false,
) {
    init {
        require(anchorColumn >= 0) { "anchorColumn must be >= 0, was $anchorColumn" }
        require(anchorRow >= 0) { "anchorRow must be >= 0, was $anchorRow" }
        require(caretColumn >= 0) { "caretColumn must be >= 0, was $caretColumn" }
        require(caretRow >= 0) { "caretRow must be >= 0, was $caretRow" }
    }

    /**
     * Returns true when the selection covers no cells.
     */
    val isEmpty: Boolean
        get() = anchorColumn == caretColumn && anchorRow == caretRow

    /**
     * Returns the selected half-open column range for [row], packed as
     * `start shl 32 | end`, or [NO_RANGE] when [row] is outside the selection.
     *
     * @param row visible render-cache row.
     * @param columns visible render-cache column count.
     * @return packed half-open range, or [NO_RANGE].
     */
    fun packedColumnRange(
        row: Int,
        columns: Int,
        cache: TerminalRenderCache? = null,
    ): Long {
        if (isEmpty || row < startRow || row > endRow) return NO_RANGE

        var start =
            when {
                isBlock -> minOf(anchorColumn, caretColumn)
                startRow == endRow -> startColumn
                row == startRow -> startColumn
                else -> 0
            }.coerceIn(0, columns)

        val endVal =
            when {
                isBlock -> maxOf(anchorColumn, caretColumn)
                startRow == endRow -> endColumn
                row == endRow -> endColumn
                else -> columns
            }.coerceIn(0, columns)
        var end = endVal

        if (cache != null && row in 0 until cache.rows) {
            val rowOffset = cache.rowOffset(row)
            val flags = cache.flags

            if (start in 0 until columns) {
                val startIdx = rowOffset + start
                if (startIdx in flags.indices && (flags[startIdx] and TerminalRenderCellFlags.WIDE_TRAILING) != 0) {
                    start = (start - 1).coerceAtLeast(0)
                }
            }

            if (end in 1..columns) {
                val endIdx = rowOffset + (end - 1)
                if (endIdx in flags.indices && (flags[endIdx] and TerminalRenderCellFlags.WIDE_LEADING) != 0) {
                    end = (end + 1).coerceAtMost(columns)
                }
            }
        }

        if (start >= end) return NO_RANGE
        return packRange(start, end)
    }

    /**
     * First selected row after normalizing anchor and caret order.
     */
    val startRow: Int
        get() = if (isForward) anchorRow else caretRow

    /**
     * Last selected row after normalizing anchor and caret order.
     */
    val endRow: Int
        get() = if (isForward) caretRow else anchorRow

    /**
     * First selected caret column on [startRow].
     */
    val startColumn: Int
        get() = if (isForward) anchorColumn else caretColumn

    /**
     * End-exclusive selected caret column on [endRow].
     */
    val endColumn: Int
        get() = if (isForward) caretColumn else anchorColumn

    private val isForward: Boolean
        get() = caretRow > anchorRow || caretRow == anchorRow && caretColumn >= anchorColumn

    companion object {
        /**
         * Sentinel returned when a row is not selected.
         */
        const val NO_RANGE: Long = -1L

        /**
         * Packs a half-open column range.
         */
        fun packRange(
            startColumn: Int,
            endColumn: Int,
        ): Long = (startColumn.toLong() shl 32) or (endColumn.toLong() and 0xffff_ffffL)

        /**
         * Extracts the start column from a packed range.
         */
        fun rangeStart(range: Long): Int = (range ushr 32).toInt()

        /**
         * Extracts the end-exclusive column from a packed range.
         */
        fun rangeEnd(range: Long): Int = range.toInt()
    }
}
