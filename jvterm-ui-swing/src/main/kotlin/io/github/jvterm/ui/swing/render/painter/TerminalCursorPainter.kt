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
package io.github.jvterm.ui.swing.render.painter

import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.api.TerminalRenderCursorShape
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.render.visualCellRangeSpan
import io.github.jvterm.ui.swing.render.visualCellRangeStart
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
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
        textBlinkVisible: Boolean,
        fontRenderContext: FontRenderContext,
        cursorVisible: Boolean = true,
    ) {
        if (!cursorVisible || !cache.cursorVisible || (cache.cursorBlinking && !cursorBlinkVisible)) return
        if (cache.cursorColumn !in 0 until cache.columns || cache.cursorRow !in 0 until cache.rows) return

        val cursorIndex = cache.rowOffset(cache.cursorRow) + cache.cursorColumn
        val cursorFlags = cache.flags[cursorIndex]
        val startColumn = visualCellRangeStart(cursorFlags, cache.cursorColumn)
        val columnSpan = visualCellRangeSpan(cursorFlags, cache.cursorColumn, cache.columns)
        val x = startColumn * metrics.cellWidth
        val y = cache.cursorRow * metrics.cellHeight
        val width = columnSpan * metrics.cellWidth
        g.color = colorCache.color(palette.cursorBackground)

        when (cache.cursorShape) {
            TerminalRenderCursorShape.BLOCK -> g.fillRect(x, y, width, metrics.cellHeight)
            TerminalRenderCursorShape.UNDERLINE -> {
                g.fillRect(
                    x,
                    y + metrics.cellHeight - metrics.cursorStrokeWidth,
                    width,
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
                column = startColumn,
                row = cache.cursorRow,
                columnSpan = columnSpan,
                foreground = palette.cursorForeground,
                fontRenderContext = fontRenderContext,
                textBlinkVisible = textBlinkVisible,
            )
        }
    }
}
