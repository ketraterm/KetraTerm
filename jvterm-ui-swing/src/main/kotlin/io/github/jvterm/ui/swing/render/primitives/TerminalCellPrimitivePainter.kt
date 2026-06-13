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
package io.github.jvterm.ui.swing.render.primitives

import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D

/**
 * Routes terminal cell-native glyphs to allocation-free primitive painters.
 */
internal class TerminalCellPrimitivePainter {
    private val boxDrawingPainter = TerminalBoxDrawingPainter()
    private val blockElementPainter = TerminalBlockElementPainter()

    /**
     * Returns true when [codePoint] is handled by this primitive painter.
     */
    fun canPaint(codePoint: Int): Boolean =
        TerminalBoxDrawingGlyphs.canPaint(codePoint) ||
            TerminalBlockElementGlyphs.canPaint(codePoint)

    /**
     * Paints one supported cell-native glyph.
     */
    fun paint(
        g: Graphics2D,
        codePoint: Int,
        column: Int,
        row: Int,
        metrics: TerminalSwingMetrics,
    ) {
        val x = column * metrics.cellWidth
        val y = row * metrics.cellHeight
        when {
            TerminalBoxDrawingGlyphs.canPaint(codePoint) -> {
                boxDrawingPainter.paint(g, codePoint, x, y, metrics.cellWidth, metrics.cellHeight)
            }

            TerminalBlockElementGlyphs.canPaint(codePoint) -> {
                blockElementPainter.paint(g, codePoint, x, y, metrics.cellWidth, metrics.cellHeight)
            }
        }
    }
}
