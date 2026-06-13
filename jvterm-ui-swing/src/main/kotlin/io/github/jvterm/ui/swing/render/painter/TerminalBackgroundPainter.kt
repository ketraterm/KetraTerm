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
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.render.TerminalSwingColors
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D

/**
 * Paints terminal default clears and row background runs.
 */
internal class TerminalBackgroundPainter(
    private val colorCache: AwtColorCache,
) {
    /**
     * Clears a rectangular component area with the palette default background.
     */
    fun clear(
        g: Graphics2D,
        palette: TerminalColorPalette,
        width: Int,
        height: Int,
    ) {
        fill(g, 0, 0, width, height, palette.defaultBackground)
    }

    /**
     * Paints contiguous background runs for [row].
     */
    fun paintRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
    ) {
        val attrWords = cache.attrWords
        val rowOffset = cache.rowOffset(row)
        val y = row * metrics.cellHeight
        var column = 0
        while (column < cache.columns) {
            val background = TerminalSwingColors.background(palette, attrWords[rowOffset + column])
            val start = column

            column++
            while (
                column < cache.columns &&
                TerminalSwingColors.background(palette, attrWords[rowOffset + column]) == background
            ) {
                column++
            }

            fill(
                g = g,
                x = start * metrics.cellWidth,
                y = y,
                width = (column - start) * metrics.cellWidth,
                height = metrics.cellHeight,
                argb = background,
            )
        }
    }

    private fun fill(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        argb: Int,
    ) {
        g.color = colorCache.color(argb)
        g.fillRect(x, y, width, height)
    }
}
