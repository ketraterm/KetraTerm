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

import io.github.ketraterm.ui.swing.render.TerminalDeviceGeometry
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
    private val powerlinePainter = TerminalPowerlinePainter()
    private val pixels = TerminalDeviceGeometry()

    /**
     * Returns true when [codePoint] is handled by this primitive painter.
     */
    fun canPaint(codePoint: Int): Boolean =
        TerminalBoxDrawingGlyphs.canPaint(codePoint) ||
            TerminalBlockElementGlyphs.canPaint(codePoint) ||
            TerminalGeometricShapeGlyphs.canPaint(codePoint) ||
            TerminalBraillePainter.canPaint(codePoint) ||
            TerminalPowerlinePainter.canPaint(codePoint) ||
            TerminalMosaicPainter.canPaint(codePoint)

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
        val oldTransform = pixels.update(g)
        val left = column * metrics.cellWidth
        val top = row * metrics.cellHeight
        val oldClip = pixels.saveClip(g)
        val x1 = if (pixels.axisAligned) pixels.deviceX(left) else left
        val y1 = if (pixels.axisAligned) pixels.deviceY(top) else top
        val x2 = if (pixels.axisAligned) pixels.deviceX(left + metrics.cellWidth) else left + metrics.cellWidth
        val y2 = if (pixels.axisAligned) pixels.deviceY(top + metrics.cellHeight) else top + metrics.cellHeight
        val nominalCellWidth = if (pixels.axisAligned) pixels.deviceWidth(metrics.cellWidth) else metrics.cellWidth
        val nominalCellHeight = if (pixels.axisAligned) pixels.deviceHeight(metrics.cellHeight) else metrics.cellHeight
        if (pixels.axisAligned) g.transform = IDENTITY_TRANSFORM
        g.clipRect(x1, y1, maxOf(0, x2 - x1), maxOf(0, y2 - y1))
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

                TerminalBraillePainter.canPaint(codePoint) -> {
                    TerminalBraillePainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1, nominalCellWidth, nominalCellHeight)
                }

                TerminalPowerlinePainter.canPaint(codePoint) -> {
                    powerlinePainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1, nominalCellWidth)
                }

                TerminalMosaicPainter.canPaint(codePoint) -> {
                    TerminalMosaicPainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1)
                }

                TerminalGeometricShapeGlyphs.canPaint(codePoint) -> {
                    geometricShapePainter.paint(g, codePoint, x1, y1, x2 - x1, y2 - y1, nominalCellWidth)
                }
            }
        } finally {
            g.transform = oldTransform
            pixels.restoreClip(g, oldClip)
        }
    }

    private companion object {
        private val IDENTITY_TRANSFORM = AffineTransform()
    }
}
