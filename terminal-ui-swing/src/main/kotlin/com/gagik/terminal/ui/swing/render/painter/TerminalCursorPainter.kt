package com.gagik.terminal.ui.swing.render.painter

import com.gagik.terminal.render.api.TerminalColorPalette
import com.gagik.terminal.render.api.TerminalRenderCursorShape
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.render.cache.AwtColorCache
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext

/**
 * Paints terminal cursor shapes and block-cursor foreground text.
 */
internal class TerminalCursorPainter(
    private val colorCache: AwtColorCache,
    private val textPainter: TerminalTextPainter,
) {
    /**
     * Paints the current cursor from [cache].
     */
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        cursorBlinkVisible: Boolean,
        fontRenderContext: FontRenderContext,
    ) {
        if (!cache.cursorVisible || (cache.cursorBlinking && !cursorBlinkVisible)) return
        if (cache.cursorColumn !in 0 until cache.columns || cache.cursorRow !in 0 until cache.rows) return

        val x = cache.cursorColumn * metrics.cellWidth
        val y = cache.cursorRow * metrics.cellHeight
        g.color = colorCache.color(palette.cursorBackground)

        when (cache.cursorShape) {
            TerminalRenderCursorShape.BLOCK -> g.fillRect(x, y, metrics.cellWidth, metrics.cellHeight)
            TerminalRenderCursorShape.UNDERLINE -> {
                g.fillRect(
                    x,
                    y + metrics.cellHeight - metrics.cursorStrokeWidth,
                    metrics.cellWidth,
                    metrics.cursorStrokeWidth,
                )
            }
            TerminalRenderCursorShape.BAR -> {
                g.fillRect(x, y, metrics.cursorStrokeWidth, metrics.cellHeight)
            }
        }

        if (cache.cursorShape == TerminalRenderCursorShape.BLOCK) {
            textPainter.paintCellForeground(
                g = g,
                cache = cache,
                metrics = metrics,
                column = cache.cursorColumn,
                row = cache.cursorRow,
                foreground = palette.cursorForeground,
                fontRenderContext = fontRenderContext,
            )
        }
    }
}
