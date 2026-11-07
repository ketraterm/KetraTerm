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

import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import kotlin.math.roundToInt

/** Cell-fitted Powerline separators; paths and the current outline stroke are reused. */
internal class TerminalPowerlinePainter {
    private val path = Path2D.Double()
    private var outlineStroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)

    /** Paints device-pixel bounds; nominal width keeps outline weight stable across HiDPI cells. */
    fun paint(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        nominalCellWidth: Int = width,
    ) {
        if (!canPaint(codePoint) || width <= 0 || height <= 0) return
        val left = x.toDouble()
        val top = y.toDouble()
        val right = left + width
        val bottom = top + height
        val middle = top + height / 2.0
        val outline = codePoint and 1 != 0
        path.reset()
        when (codePoint and 0xFFFE) {
            0xE0B0, 0xE0B2 -> {
                val base = if (codePoint and 2 == 0) left else right
                val tip = if (codePoint and 2 == 0) right else left
                path.moveTo(base, top)
                path.lineTo(tip, middle)
                path.lineTo(base, bottom)
            }
            0xE0B4, 0xE0B6 -> {
                val base = if (codePoint and 2 == 0) left else right
                val tip = if (codePoint and 2 == 0) right else left
                val controlX = base + (tip - base) * KAPPA
                val controlY = height / 2.0 * KAPPA
                path.moveTo(base, top)
                path.curveTo(controlX, top, tip, middle - controlY, tip, middle)
                path.curveTo(tip, middle + controlY, controlX, bottom, base, bottom)
            }
            0xE0B8 -> {
                path.moveTo(left, top)
                path.lineTo(right, bottom)
                if (!outline) path.lineTo(left, bottom)
            }
            0xE0BA -> {
                path.moveTo(right, top)
                path.lineTo(left, bottom)
                if (!outline) path.lineTo(right, bottom)
            }
            0xE0BC -> {
                path.moveTo(left, bottom)
                path.lineTo(right, top)
                if (!outline) path.lineTo(left, top)
            }
            0xE0BE -> {
                path.moveTo(left, top)
                path.lineTo(right, bottom)
                if (!outline) path.lineTo(right, top)
            }
        }

        val oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING) ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT
        val oldStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL) ?: RenderingHints.VALUE_STROKE_DEFAULT
        val oldStroke = g.stroke
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (outline) {
                val thickness = maxOf(1, (nominalCellWidth / 8.0).roundToInt()).toFloat()
                if (outlineStroke.lineWidth != thickness) {
                    outlineStroke = BasicStroke(thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                }
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g.stroke = outlineStroke
                g.draw(path)
            } else {
                path.closePath()
                g.fill(path)
            }
        } finally {
            g.stroke = oldStroke
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, oldStrokeControl)
        }
    }

    companion object {
        fun canPaint(codePoint: Int): Boolean = codePoint in 0xE0B0..0xE0BF

        private const val KAPPA = 0.5522847498307936
    }
}
