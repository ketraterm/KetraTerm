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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Rectangle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * EDT-owned row geometry shared by paint, pointer input, cursor and repaint planning.
 * Terminal cell height stays fixed. Optional prompt bands add presentation-only offsets.
 */
internal class TerminalVisualViewportGeometry {
    /** Cell permutation shared by rendering, pointer input and repaint planning. */
    val bidiLayout = TerminalBidiLayout()

    private var rowOffsets = IntArray(0)
    private var dividerBands = BooleanArray(0)
    private var hasDividers = false

    var rowCount: Int = 0
        private set
    var cellHeight: Int = 0
        private set
    var visualHeight: Int = 0
        private set
    var viewportPixelHeight: Int = 0
        private set
    var contentOriginY: Double = 0.0
        private set

    /**
     * Rebuilds row positions for the current render cache.
     *
     * @return true when row geometry changed.
     */
    fun updateLayout(
        metrics: SwingMetrics,
        rows: Int,
        viewportPixelHeight: Int,
        dividers: PromptDividerLayout? = null,
        firstAbsoluteRow: Long = 0,
    ): Boolean {
        require(rows >= 0) { "rows must be >= 0, was $rows" }
        require(viewportPixelHeight >= 0) { "viewportPixelHeight must be >= 0, was $viewportPixelHeight" }

        val previousRowCount = rowCount
        val previousCellHeight = cellHeight
        val previousVisualHeight = visualHeight
        val previousViewportPixelHeight = this.viewportPixelHeight

        if (dividers != null && rowOffsets.size < rows) {
            rowOffsets = IntArray(rows)
            dividerBands = BooleanArray(rows)
        }
        val previouslyHadDividers = hasDividers
        var offsetsChanged = false
        var offset = 0L
        if (dividers != null) {
            for (row in 0 until rows) {
                val band = dividers.hasDividerAt(firstAbsoluteRow + row)
                if (band) offset += metrics.cellHeight
                require(
                    offset + (row.toLong() + 1) * metrics.cellHeight <= Int.MAX_VALUE,
                ) { "visual row geometry exceeds integer pixel range" }
                if (rowOffsets[row] != offset.toInt() || dividerBands[row] != band) offsetsChanged = true
                rowOffsets[row] = offset.toInt()
                dividerBands[row] = band
            }
        }
        require(rows.toLong() * metrics.cellHeight + offset <= Int.MAX_VALUE) { "visual row geometry exceeds integer pixel range" }
        hasDividers = offset != 0L
        offsetsChanged = offsetsChanged || previouslyHadDividers != hasDividers
        rowCount = rows
        cellHeight = metrics.cellHeight
        this.viewportPixelHeight = viewportPixelHeight
        visualHeight = (rows.toLong() * metrics.cellHeight + offset).toInt()

        val changed =
            previousRowCount != rows ||
                previousCellHeight != metrics.cellHeight ||
                previousVisualHeight != visualHeight ||
                previousViewportPixelHeight != viewportPixelHeight ||
                offsetsChanged
        return changed
    }

    /**
     * Updates the current content origin.
     *
     * @return true when the origin changed.
     */
    fun updateContentOrigin(contentOriginY: Double): Boolean {
        require(!contentOriginY.isNaN()) { "contentOriginY must not be NaN" }
        if (this.contentOriginY == contentOriginY) return false
        this.contentOriginY = contentOriginY
        return true
    }

    /**
     * Clears retained row geometry.
     */
    fun reset() {
        bidiLayout.reset()
        hasDividers = false
        rowCount = 0
        cellHeight = 0
        visualHeight = 0
        viewportPixelHeight = 0
        contentOriginY = 0.0
    }

    /** Copies one logical cell's visual bounds, clipped to the component's content area. */
    fun copyCellBounds(
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        column: Int,
        row: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        destination: Rectangle,
    ): Boolean {
        destination.setBounds(0, 0, 0, 0)
        if (!cache.hasFrame ||
            column !in 0 until cache.columns ||
            row !in 0 until cache.rows ||
            right <= left ||
            bottom <= top
        ) {
            return false
        }
        val visualColumn = bidiLayout.row(cache, row)?.visualColumn(column) ?: column
        val x = left.toLong() + visualColumn.toLong() * metrics.cellWidth
        val xStart = maxOf(left.toLong(), x)
        val xEnd = minOf(right.toLong(), x + metrics.cellWidth)
        val origin = if (rowCount == cache.rows) contentOriginY else 0.0
        val cellTop = if (rowCount == cache.rows) rowTop(row) else row * metrics.cellHeight
        val yStart = maxOf(top, floor(top.toDouble() + origin + cellTop).toInt())
        val yEnd = minOf(bottom, ceil(top.toDouble() + origin + cellTop + metrics.cellHeight).toInt())
        if (xEnd <= xStart || yEnd <= yStart) return false
        destination.setBounds(xStart.toInt(), yStart, (xEnd - xStart).toInt(), yEnd - yStart)
        return true
    }

    /**
     * Returns the visual top of terminal [row], excluding [contentOriginY].
     */
    fun rowTop(row: Int): Int = row * cellHeight + if (hasDividers && row in 0 until rowCount) rowOffsets[row] else 0

    /**
     * Returns the visual bottom of terminal [row], excluding [contentOriginY].
     */
    fun rowBottom(row: Int): Int = rowTop(row) + cellHeight

    /**
     * Returns the visual height occupied by the first [rows] terminal rows.
     */
    fun visualHeightForRows(rows: Int): Int {
        val safeRows = rows.coerceIn(0, rowCount)
        return if (safeRows == 0) 0 else rowBottom(safeRows - 1)
    }

    /**
     * Maps visual local y, excluding [contentOriginY], to a terminal row.
     */
    fun rowAt(visualY: Int): Int {
        if (rowCount <= 0) return 0
        if (!hasDividers) return (visualY / cellHeight).coerceIn(0, rowCount - 1)
        var low = 0
        var high = rowCount - 1
        while (low < high) {
            val middle = (low + high) ushr 1
            if (rowBottom(middle) <= visualY) low = middle + 1 else high = middle
        }
        return low
    }

    /** Returns the prompt row owning a divider band, or -1 outside a band. */
    fun dividerRowAtComponentY(
        y: Int,
        paddingTop: Int,
    ): Int {
        if (!hasDividers || rowCount == 0) return -1
        val localY = floor(y.toDouble() - paddingTop - contentOriginY).toInt()
        val row = rowAt(localY)
        return if (dividerBands[row] && localY >= rowTop(row) - cellHeight && localY < rowTop(row)) row else -1
    }

    fun hasDividerBefore(row: Int): Boolean = hasDividers && row in 0 until rowCount && dividerBands[row]

    /**
     * Maps a component-local y coordinate to a terminal row.
     */
    fun rowAtComponentY(
        y: Int,
        paddingTop: Int,
    ): Int = rowAt(floor(y.toDouble() - paddingTop.toDouble() - contentOriginY).toInt())

    /**
     * Converts visual local y into terminal-native pixel y.
     */
    fun terminalPixelY(
        visualY: Int,
        row: Int,
    ): Int {
        val safeRow = row.coerceIn(0, maxOf(0, rowCount - 1))
        val yInCell = (visualY - rowTop(safeRow)).coerceIn(0, maxOf(0, cellHeight - 1))
        return safeRow * cellHeight + yInCell
    }

    /**
     * Converts a component-local y coordinate into terminal-native pixel y.
     */
    fun terminalPixelYAtComponentY(
        y: Int,
        paddingTop: Int,
    ): Int {
        val localY = floor(y.toDouble() - paddingTop.toDouble() - contentOriginY).toInt()
        val row = rowAt(localY)
        return terminalPixelY(localY, row)
    }

    /**
     * Returns the first row that should be considered for painting [clip].
     */
    fun firstPaintRow(
        clip: Rectangle?,
        paddingTop: Int,
    ): Int {
        if (clip == null) return 0
        val localY = floor(clip.y.toDouble() - paddingTop.toDouble() - contentOriginY).toInt()
        return rowAt(localY)
    }

    /**
     * Returns one past the last row that should be considered for painting.
     */
    fun lastPaintRowExclusive(
        clip: Rectangle?,
        componentHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
    ): Int {
        val visibleRows = visibleRowsExclusive(componentHeight, paddingTop, paddingBottom)
        if (clip == null || clip.height <= 0) return visibleRows

        val clipBottom = clip.y + clip.height
        if (clipBottom <= 0) return 0
        val localBottom = ceil(clipBottom.toDouble() - paddingTop.toDouble() - contentOriginY).toInt()
        return (rowAt(localBottom) + 1).coerceIn(0, visibleRows)
    }

    /**
     * Returns one past the last row visible in the component.
     */
    fun visibleRowsExclusive(
        componentHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
    ): Int {
        val availableHeight = componentHeight - paddingTop - paddingBottom
        if (rowCount == 0) return 0
        if (!hasDividers) return minOf(rowCount, maxOf(1, ceil((availableHeight.toDouble() - contentOriginY) / cellHeight).toInt()) + 1)
        val bottom = ceil(availableHeight - contentOriginY).toInt()
        return (rowAt(bottom) + 1).coerceAtMost(rowCount)
    }

    /**
     * Returns the first fully visible row, useful for command navigation.
     */
    fun firstFullyVisibleRow(): Int {
        if (rowCount <= 0) return 0
        val top = ceil(-contentOriginY).toInt()
        val row = rowAt(top)
        return (if (rowTop(row) < top) row + 1 else row).coerceAtMost(rowCount - 1)
    }
}
