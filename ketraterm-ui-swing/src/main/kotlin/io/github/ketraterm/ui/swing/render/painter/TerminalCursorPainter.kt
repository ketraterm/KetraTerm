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

import io.github.ketraterm.render.api.TerminalColorBehavior
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext

/**
 * Paints application cursor shapes, replacing inactive blocks with steady outlines.
 * Inactive bars and underlines retain their shape without blinking.
 * Inactive outlines preserve the row's existing text, background, and overlays.
 */
internal class TerminalCursorPainter(
    private val colorCache: AwtColorCache,
    private val textPainter: TerminalTextPainter,
) {
    private val pixels = TerminalDeviceGeometry()

    /**
     * Paints the current cursor from [cache]. Optional [foregroundCache] supplies preview
     * text beneath a block; cursor geometry and colors still come from the live cache.
     */
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        cursorBlinkVisible: Boolean,
        textBlinkVisible: Boolean,
        fontRenderContext: FontRenderContext,
        terminalFocused: Boolean = true,
        bidi: TerminalBidiLayout.Row? = null,
        foregroundCache: TerminalRenderCache? = null,
        foregroundBidi: TerminalBidiLayout.Row? = null,
    ) {
        if (!cache.cursorVisible || (terminalFocused && cache.cursorBlinking && !cursorBlinkVisible)) return
        if (cache.cursorColumn !in 0 until cache.columns || cache.cursorRow !in 0 until cache.rows) return

        val cursorIndex = cache.rowOffset(cache.cursorRow) + cache.cursorColumn
        val cursorFlags = cache.flags[cursorIndex]
        val startColumn = visualCellRangeStart(cursorFlags, cache.cursorColumn)
        val columnSpan = visualCellRangeSpan(cursorFlags, cache.cursorColumn, cache.columns)
        val visualColumn = bidi?.visualColumn(startColumn) ?: startColumn
        val x = visualColumn * metrics.cellWidth
        val y = cache.cursorRow * metrics.cellHeight
        val width = columnSpan * metrics.cellWidth
        val cursorAttr = cache.attrWords[cache.rowOffset(cache.cursorRow) + startColumn]
        val background =
            if (palette.colorBehavior and TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND != 0) {
                palette.foreground(cursorAttr)
            } else {
                palette.cursorBackground
            }
        g.color = colorCache.color(background)

        pixels.update(g)
        if (!terminalFocused && cache.cursorShape == TerminalRenderCursorShape.BLOCK) {
            pixels.border(g, x, y, width, metrics.cellHeight, hairline = true)
            return
        }
        if (cache.cursorShape != TerminalRenderCursorShape.BLOCK) {
            pixels.cursorLine(
                g,
                x,
                y,
                width,
                metrics.cellHeight,
                metrics.cursorStrokeWidth,
                vertical = cache.cursorShape == TerminalRenderCursorShape.BAR,
            )
            return
        }

        pixels.fillRect(g, x, y, width, metrics.cellHeight)
        var textCache = cache
        var textColumn = startColumn
        var textSpan = columnSpan
        var textVisualColumn = visualColumn
        var textBidi = bidi
        if (foregroundCache != null && cache.cursorRow < foregroundCache.rows && visualColumn < foregroundCache.columns) {
            val column = foregroundBidi?.logicalColumn(visualColumn) ?: visualColumn
            val flags = foregroundCache.flags[foregroundCache.rowOffset(cache.cursorRow) + column]
            val start = visualCellRangeStart(flags, column)
            val startFlags = foregroundCache.flags[foregroundCache.rowOffset(cache.cursorRow) + start]
            if (hasDrawableText(startFlags)) {
                textCache = foregroundCache
                textColumn = start
                textSpan = visualCellRangeSpan(startFlags, start, foregroundCache.columns)
                textVisualColumn = foregroundBidi?.visualColumn(start) ?: start
                textBidi = foregroundBidi
            }
        }
        // Preview glyphs can be wider than the live blank cell. Preserve their natural
        // geometry while recoloring only the area actually covered by the cursor.
        val previewForeground = textCache !== cache
        val oldClip = if (previewForeground) pixels.saveClip(g) else null
        try {
            if (previewForeground) pixels.clipRect(g, x, y, width, metrics.cellHeight)
            textPainter.paintCellForeground(
                g = g,
                cache = textCache,
                metrics = metrics,
                column = textColumn,
                row = cache.cursorRow,
                columnSpan = textSpan,
                visualColumn = textVisualColumn,
                foreground =
                    if (palette.colorBehavior and TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND != 0) {
                        palette.background(cursorAttr)
                    } else {
                        palette.cursorForeground
                    },
                fontRenderContext = fontRenderContext,
                textBlinkVisible = textBlinkVisible,
                bidi = textBidi,
            )
        } finally {
            if (previewForeground) pixels.restoreClip(g, oldClip)
        }
    }
}
