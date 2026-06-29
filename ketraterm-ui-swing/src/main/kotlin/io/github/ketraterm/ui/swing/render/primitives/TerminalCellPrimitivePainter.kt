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
package io.github.ketraterm.ui.swing.render.primitives

import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.geom.AffineTransform

/**
 * Routes terminal cell-native glyphs to allocation-free primitive painters.
 */
internal class TerminalCellPrimitivePainter {
    private val boxDrawingPainter = TerminalBoxDrawingPainter()
    private val blockElementPainter = TerminalBlockElementPainter()
    private val geometricShapePainter = TerminalGeometricShapePainter()

    /**
     * Returns true when [codePoint] is handled by this primitive painter.
     */
    fun canPaint(codePoint: Int): Boolean =
        TerminalBoxDrawingGlyphs.canPaint(codePoint) ||
            TerminalBlockElementGlyphs.canPaint(codePoint) ||
            TerminalGeometricShapeGlyphs.canPaint(codePoint)

    /**
     * Paints one supported cell-native glyph.
     */
    fun paint(
        g: Graphics2D,
        codePoint: Int,
        column: Int,
        row: Int,
        metrics: SwingMetrics,
    ) {
        val transform = g.transform
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY
        val transX = transform.translateX
        val transY = transform.translateY

        val left = column * metrics.cellWidth
        val top = row * metrics.cellHeight
        val right = (column + 1) * metrics.cellWidth
        val bottom = (row + 1) * metrics.cellHeight

        val x1 = kotlin.math.round(left * scaleX + transX).toInt()
        val y1 = kotlin.math.round(top * scaleY + transY).toInt()
        val x2 = kotlin.math.round(right * scaleX + transX).toInt()
        val y2 = kotlin.math.round(bottom * scaleY + transY).toInt()

        val oldTransform = g.transform
        g.transform = IDENTITY_TRANSFORM
        try {
            when {
                TerminalBoxDrawingGlyphs.canPaint(codePoint) -> {
                    boxDrawingPainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1)
                }

                TerminalBlockElementGlyphs.canPaint(codePoint) -> {
                    blockElementPainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1)
                }

                TerminalGeometricShapeGlyphs.canPaint(codePoint) -> {
                    val nominalCellWidth = maxOf(1, kotlin.math.round(metrics.cellWidth * scaleX).toInt())
                    geometricShapePainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1, nominalCellWidth)
                }
            }
        } finally {
            g.transform = oldTransform
        }
    }

    private companion object {
        private val IDENTITY_TRANSFORM = AffineTransform()
    }
}
