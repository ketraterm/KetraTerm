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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.render.SelectionColors
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.forEachVisualCellSpan
import io.github.ketraterm.ui.swing.render.visualCellRangeStart
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSelectionStyle
import java.awt.Graphics2D

/**
 * Paints selected terminal cell ranges as per-row rectangles.
 */
internal class TerminalSelectionPainter(
    private val colorCache: AwtColorCache,
) {
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        row: Int,
        selection: CellSelection?,
        style: SwingSelectionStyle,
        palette: TerminalColorPalette,
        bidi: TerminalBidiLayout.Row? = null,
    ) {
        if (selection == null) return

        val range = selection.packedColumnRange(row, cache, bidi)
        if (range == CellSelection.NO_RANGE) return

        val startColumn = CellSelection.rangeStart(range)
        val endColumn = CellSelection.rangeEnd(range)

        val dynamic = SelectionColors.dynamicBackground(palette, style)
        val fixed = if (dynamic) 0 else SelectionColors.background(palette, style, 0L)
        val rowOffset = cache.rowOffset(row)
        // Block ranges are already visual; only linear selections need row reordering.
        forEachVisualCellSpan(if (selection.isBlock) null else bidi, startColumn, endColumn) { visualStart, visualEnd ->
            var column = visualStart
            while (column < visualEnd) {
                val color = if (dynamic) cellBackground(palette, style, cache, rowOffset, bidi?.logicalColumn(column) ?: column) else fixed
                val first = column++
                if (dynamic) {
                    while (column < visualEnd &&
                        cellBackground(palette, style, cache, rowOffset, bidi?.logicalColumn(column) ?: column) == color
                    ) {
                        column++
                    }
                } else {
                    column = visualEnd
                }
                g.color = colorCache.color(color)
                g.fillRect(first * metrics.cellWidth, row * metrics.cellHeight, (column - first) * metrics.cellWidth, metrics.cellHeight)
            }
        }
    }

    private fun cellBackground(
        palette: TerminalColorPalette,
        style: SwingSelectionStyle,
        cache: TerminalRenderCache,
        rowOffset: Int,
        column: Int,
    ): Int {
        val leadingColumn = visualCellRangeStart(cache.flags[rowOffset + column], column)
        return SelectionColors.background(palette, style, cache.attrWords[rowOffset + leadingColumn])
    }
}
