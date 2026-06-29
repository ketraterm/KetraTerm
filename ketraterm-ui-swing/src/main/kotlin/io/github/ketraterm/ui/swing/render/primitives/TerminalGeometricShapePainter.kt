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

import io.github.ketraterm.ui.swing.render.primitives.TerminalGeometricShapeGlyphs.BLACK_SQUARE
import io.github.ketraterm.ui.swing.render.primitives.TerminalGeometricShapeGlyphs.WHITE_SQUARE
import java.awt.Graphics2D

/**
 * Paints terminal-relevant Unicode Geometric Shapes glyphs.
 */
internal class TerminalGeometricShapePainter {
    fun paint(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        nominalCellWidth: Int = width,
    ) {
        when (codePoint) {
            BLACK_SQUARE -> paintBlackSquare(g, x, y, width, height, nominalCellWidth)
            WHITE_SQUARE -> paintWhiteSquare(g, x, y, width, height, nominalCellWidth)
        }
    }

    private fun paintBlackSquare(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        nominalCellWidth: Int,
    ) {
        val blockHeight = squareHeight(nominalCellWidth, height)
        g.fillRect(x, y + centeredOffset(height, blockHeight), width, blockHeight)
    }

    private fun paintWhiteSquare(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        nominalCellWidth: Int,
    ) {
        val blockHeight = squareHeight(nominalCellWidth, height)
        val top = y + centeredOffset(height, blockHeight)
        val stroke = maxOf(1, minOf(width, blockHeight) / 8)
        val right = x + width - stroke
        val bottom = top + blockHeight - stroke

        g.fillRect(x, top, width, stroke)
        g.fillRect(x, bottom, width, stroke)
        if (width > stroke && blockHeight > stroke) {
            val verticalHeight = blockHeight - stroke * 2
            if (verticalHeight > 0) {
                g.fillRect(x, top + stroke, stroke, verticalHeight)
                g.fillRect(right, top + stroke, stroke, verticalHeight)
            }
        }
    }

    private fun squareHeight(
        nominalCellWidth: Int,
        height: Int,
    ): Int = maxOf(1, minOf(nominalCellWidth, height))

    private fun centeredOffset(
        extent: Int,
        side: Int,
    ): Int = (extent - side) / 2
}
