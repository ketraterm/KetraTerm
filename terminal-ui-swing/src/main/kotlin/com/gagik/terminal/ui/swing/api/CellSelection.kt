package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.ui.swing.api.CellSelection.Companion.NO_RANGE


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
    fun packedColumnRange(row: Int, columns: Int): Long {
        if (isEmpty || row < startRow || row > endRow) return NO_RANGE

        val start = when {
            startRow == endRow -> startColumn
            row == startRow -> startColumn
            else -> 0
        }.coerceIn(0, columns)
        val end = when {
            startRow == endRow -> endColumn
            row == endRow -> endColumn
            else -> columns
        }.coerceIn(0, columns)

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
        fun packRange(startColumn: Int, endColumn: Int): Long {
            return (startColumn.toLong() shl 32) or (endColumn.toLong() and 0xffff_ffffL)
        }

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
