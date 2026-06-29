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
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Routes terminal cell-native glyphs to allocation-free primitive painters.
 */
internal class TerminalCellPrimitivePainter {
    private val boxDrawingPainter = TerminalBoxDrawingPainter()
    private val blockElementPainter = TerminalBlockElementPainter()
    private val geometricShapePainter = TerminalGeometricShapePainter()
    private val clipScratch = Rectangle()

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
        val oldClip = g.clip
        val clip = if (oldClip != null) g.getClipBounds(clipScratch) else null
        val nominalCellWidth = maxOf(1, kotlin.math.round(metrics.cellWidth * scaleX).toInt())
        val nominalCellHeight = maxOf(1, kotlin.math.round(metrics.cellHeight * scaleY).toInt())
        g.transform = IDENTITY_TRANSFORM
        setDeviceCellClip(g, clip, scaleX, scaleY, transX, transY, x1, y1, x2, y2)
        try {
            when {
                TerminalBoxDrawingGlyphs.canPaint(codePoint) -> {
                    boxDrawingPainter.paint(
                        g,
                        codePoint,
                        x1,
                        y1,
                        x2 - x1,
                        y2 - y1,
                        nominalCellWidth,
                        nominalCellHeight,
                    )
                }

                TerminalBlockElementGlyphs.canPaint(codePoint) -> {
                    blockElementPainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1)
                }

                TerminalGeometricShapeGlyphs.canPaint(codePoint) -> {
                    geometricShapePainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1, nominalCellWidth)
                }
            }
        } finally {
            g.transform = oldTransform
            g.clip = oldClip
        }
    }

    private fun setDeviceCellClip(
        g: Graphics2D,
        clip: Rectangle?,
        scaleX: Double,
        scaleY: Double,
        transX: Double,
        transY: Double,
        cellX1: Int,
        cellY1: Int,
        cellX2: Int,
        cellY2: Int,
    ) {
        var x1 = cellX1
        var y1 = cellY1
        var x2 = cellX2
        var y2 = cellY2

        if (clip != null) {
            x1 = maxOf(x1, floor(clip.x * scaleX + transX).toInt())
            y1 = maxOf(y1, floor(clip.y * scaleY + transY).toInt())
            x2 = minOf(x2, ceil((clip.x + clip.width) * scaleX + transX).toInt())
            y2 = minOf(y2, ceil((clip.y + clip.height) * scaleY + transY).toInt())
        }

        g.setClip(x1, y1, maxOf(0, x2 - x1), maxOf(0, y2 - y1))
    }

    private companion object {
        private val IDENTITY_TRANSFORM = AffineTransform()
    }
}
