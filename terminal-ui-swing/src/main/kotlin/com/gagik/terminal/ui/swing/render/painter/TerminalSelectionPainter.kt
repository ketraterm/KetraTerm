package com.gagik.terminal.ui.swing.render.painter

import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.api.CellSelection
import com.gagik.terminal.ui.swing.render.cache.AwtColorCache
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
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
        metrics: TerminalSwingMetrics,
        row: Int,
        selection: CellSelection?,
        selectionBackground: Int,
    ) {
        if (selection == null) return

        val range = selection.packedColumnRange(row, cache.columns, cache)
        if (range == CellSelection.NO_RANGE) return

        val startColumn = CellSelection.rangeStart(range)
        val endColumn = CellSelection.rangeEnd(range)
        g.color = colorCache.color(selectionBackground)
        g.fillRect(
            startColumn * metrics.cellWidth,
            row * metrics.cellHeight,
            (endColumn - startColumn) * metrics.cellWidth,
            metrics.cellHeight,
        )
    }
}
