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
package io.github.jvterm.ui.swing.render

import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings

/**
 * Reusable visual row geometry for shell-integration prompt dividers.
 *
 * Terminal grid coordinates remain fixed: row `n` is still terminal row `n`.
 * This layout adds optional visual divider bands before prompt-start rows and
 * maps between terminal rows and painted pixel positions. The instance is
 * component-owned and reused across frames so paint and input paths do not
 * allocate per frame.
 */
internal class TerminalShellIntegrationRowLayout {
    private var rowTops = IntArray(0)
    private var dividerBeforeRows = BooleanArray(0)

    var rowCount: Int = 0
        private set
    var cellHeight: Int = 0
        private set
    var dividerBandHeight: Int = 0
        private set
    var visualHeight: Int = 0
        private set

    /**
     * Rebuilds row geometry for the current render-cache snapshot.
     *
     * @return true when any visual row position or divider flag changed.
     */
    fun update(
        settings: SwingSettings,
        metrics: SwingMetrics,
        decorations: TerminalShellIntegrationViewportDecorations?,
        rows: Int,
    ): Boolean {
        require(rows >= 0) { "rows must be >= 0, was $rows" }
        ensureCapacity(rows)

        val previousRowCount = rowCount
        val previousCellHeight = cellHeight
        val previousDividerBandHeight = dividerBandHeight
        val previousVisualHeight = visualHeight
        val bandHeight =
            if (settings.shellIntegrationPromptDividersVisible) {
                settings.shellIntegrationPromptDividerGap
            } else {
                0
            }

        rowCount = rows
        cellHeight = metrics.cellHeight
        dividerBandHeight = bandHeight

        var changed =
            previousRowCount != rows ||
                previousCellHeight != metrics.cellHeight ||
                previousDividerBandHeight != bandHeight
        var y = 0
        var row = 0
        while (row < rows) {
            val hasDivider = bandHeight > 0 && decorations != null && decorations.hasPromptDividerAt(row)
            if (hasDivider) y += bandHeight
            if (rowTops[row] != y) {
                changed = true
                rowTops[row] = y
            }
            if (dividerBeforeRows[row] != hasDivider) {
                changed = true
                dividerBeforeRows[row] = hasDivider
            }
            y += metrics.cellHeight
            row++
        }

        visualHeight = y
        if (previousVisualHeight != visualHeight) changed = true
        clearUnusedRows(rows, previousRowCount)
        return changed
    }

    /**
     * Clears all retained row geometry.
     */
    fun reset() {
        rowCount = 0
        cellHeight = 0
        dividerBandHeight = 0
        visualHeight = 0
    }

    /**
     * Returns true when [row] has a divider band before it.
     */
    fun hasDividerBefore(row: Int): Boolean = row in 0 until rowCount && dividerBeforeRows[row]

    /**
     * Returns the visual top of terminal [row].
     */
    fun rowTop(row: Int): Int {
        if (row !in 0 until rowCount) return row * cellHeight
        return rowTops[row]
    }

    /**
     * Returns the visual bottom of terminal [row].
     */
    fun rowBottom(row: Int): Int = rowTop(row) + cellHeight

    /**
     * Returns the visual height occupied by the first [rows] terminal rows.
     */
    fun visualHeightForRows(rows: Int): Int {
        val safeRows = rows.coerceIn(0, rowCount)
        if (safeRows == 0) return 0
        return rowBottom(safeRows - 1)
    }

    /**
     * Returns the visual distance from row 0 to row 1.
     *
     * Smooth scrolling uses this value when the render cache contains one
     * leading overscan row. A divider before row 1 is part of that transition.
     */
    fun leadingVisualStride(): Int {
        if (rowCount < 2) return cellHeight
        return rowTop(1) - rowTop(0)
    }

    /**
     * Returns the graphics translation needed before painting [row] with
     * painters that still use terminal-native `row * cellHeight` coordinates.
     */
    fun translationForRow(row: Int): Int = rowTop(row) - row * cellHeight

    /**
     * Returns the visual y coordinate for the divider before [row].
     */
    fun dividerY(
        row: Int,
        thickness: Int,
    ): Int {
        val centeredInset = maxOf(0, dividerBandHeight - thickness) / 2
        return rowTop(row) - dividerBandHeight + centeredInset
    }

    /**
     * Maps a visual local y coordinate to a terminal row.
     *
     * Divider bands before a prompt row belong to that prompt row so hit tests
     * and command actions follow the block-start semantics of the divider.
     */
    fun rowAt(visualY: Int): Int {
        if (rowCount <= 0) return 0
        if (visualY <= 0) return 0
        if (visualY >= visualHeight) return rowCount - 1

        var low = 0
        var high = rowCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (visualY < rowBottom(mid)) {
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return low.coerceIn(0, rowCount - 1)
    }

    /**
     * Converts a visual local y coordinate into terminal-native pixel y.
     */
    fun terminalPixelY(
        visualY: Int,
        row: Int,
    ): Int {
        val safeRow = row.coerceIn(0, maxOf(0, rowCount - 1))
        val yInCell = (visualY - rowTop(safeRow)).coerceIn(0, maxOf(0, cellHeight - 1))
        return safeRow * cellHeight + yInCell
    }

    private fun ensureCapacity(rows: Int) {
        if (rowTops.size >= rows) return
        rowTops = rowTops.copyOf(rows)
        dividerBeforeRows = dividerBeforeRows.copyOf(rows)
    }

    private fun clearUnusedRows(
        rows: Int,
        previousRows: Int,
    ) {
        var row = rows
        while (row < previousRows && row < dividerBeforeRows.size) {
            rowTops[row] = 0
            dividerBeforeRows[row] = false
            row++
        }
    }
}
