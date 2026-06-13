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

import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_FALLING
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_RISING
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOUBLE
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOWN_SHIFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.HEAVY
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LEFT_SHIFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LIGHT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.NONE
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.RIGHT_SHIFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.UP_SHIFT
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Paints Unicode box-drawing glyphs from packed glyph metadata.
 * Engineered for sub-pixel accuracy, dynamic DPI scaling, and zero-allocation hot paths.
 */
internal class TerminalBoxDrawingPainter {
    fun paint(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val xD = x.toDouble()
        val yD = y.toDouble()
        val wD = width.toDouble()
        val hD = height.toDouble()

        // Mutually exclusive routing. Evaluated in order of complexity/probability.
        val roundedFallback = TerminalBoxDrawingGlyphs.roundedFallbackEdges(codePoint)
        if (roundedFallback != NONE) {
            paintRoundedCorner(g, codePoint, xD, yD, wD, hD, roundedFallback)
            return
        }

        val horizontalDashStyle = TerminalBoxDrawingGlyphs.horizontalDashStyle(codePoint)
        if (horizontalDashStyle != NONE) {
            paintDashedHorizontal(g, xD, yD, wD, hD, horizontalDashStyle, TerminalBoxDrawingGlyphs.dashCount(codePoint))
            return
        }

        val verticalDashStyle = TerminalBoxDrawingGlyphs.verticalDashStyle(codePoint)
        if (verticalDashStyle != NONE) {
            paintDashedVertical(g, xD, yD, wD, hD, verticalDashStyle, TerminalBoxDrawingGlyphs.dashCount(codePoint))
            return
        }

        val diagonalMask = TerminalBoxDrawingGlyphs.diagonalMask(codePoint)
        if (diagonalMask != NONE) {
            paintDiagonal(g, xD, yD, wD, hD, diagonalMask)
            return
        }

        val packedEdges = TerminalBoxDrawingGlyphs.edges(codePoint)
        if (packedEdges != NONE) {
            paintPackedEdges(g, xD, yD, wD, hD, packedEdges)
        }
    }

    private fun paintPackedEdges(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        packed: Int,
    ) {
        val left = TerminalBoxDrawingGlyphs.edge(packed, LEFT_SHIFT)
        val right = TerminalBoxDrawingGlyphs.edge(packed, RIGHT_SHIFT)
        val up = TerminalBoxDrawingGlyphs.edge(packed, UP_SHIFT)
        val down = TerminalBoxDrawingGlyphs.edge(packed, DOWN_SHIFT)

        val hasDoubleH = left == DOUBLE || right == DOUBLE
        val hasDoubleV = up == DOUBLE || down == DOUBLE

        if (hasDoubleH || hasDoubleV) {
            paintDoubleEdges(g, x, y, w, h, left, right, up, down)
        } else {
            paintSingleEdges(g, x, y, w, h, left, right, up, down)
        }
    }

    private fun paintSingleEdges(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        left: Int,
        right: Int,
        up: Int,
        down: Int,
    ) {
        val cx = x + w / 2.0
        val cy = y + h / 2.0

        val tL = if (left != NONE) thickness(left, w, h) else 0.0
        val tR = if (right != NONE) thickness(right, w, h) else 0.0
        val tU = if (up != NONE) thickness(up, w, h) else 0.0
        val tD = if (down != NONE) thickness(down, w, h) else 0.0

        // The maximum thickness of the perpendicular axis dictates how far
        // a line must cross the center to perfectly seal the outer corner gap.
        val maxV = maxOf(tU, tD)
        val maxH = maxOf(tL, tR)

        if (left != NONE) {
            fillRectBounds(g, x, cy - tL / 2.0, cx + maxV / 2.0, cy + tL / 2.0)
        }
        if (right != NONE) {
            fillRectBounds(g, cx - maxV / 2.0, cy - tR / 2.0, x + w, cy + tR / 2.0)
        }
        if (up != NONE) {
            fillRectBounds(g, cx - tU / 2.0, y, cx + tU / 2.0, cy + maxH / 2.0)
        }
        if (down != NONE) {
            fillRectBounds(g, cx - tD / 2.0, cy - maxH / 2.0, cx + tD / 2.0, y + h)
        }
    }

    private fun paintDoubleEdges(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        left: Int,
        right: Int,
        up: Int,
        down: Int,
    ) {
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val off = doubleOffset(w, h)
        val t = thin(w, h)
        val ext = t / 2.0

        val x1 = cx - off
        val x2 = cx + off
        val y1 = cy - off
        val y2 = cy + off

        val l = left == DOUBLE
        val r = right == DOUBLE
        val u = up == DOUBLE
        val d = down == DOUBLE

        // --- LEFT edges ---
        if (l) {
            val endTop =
                if (u) {
                    x1 + ext
                } else if (d) {
                    x2 + ext
                } else {
                    cx
                }
            val endBot =
                if (d) {
                    x1 + ext
                } else if (u) {
                    x2 + ext
                } else {
                    cx
                }
            fillRectBounds(g, x, y1 - ext, endTop, y1 + ext)
            fillRectBounds(g, x, y2 - ext, endBot, y2 + ext)
        } else if (left != NONE) {
            val lt = thickness(left, w, h)
            // Mixed junction topology: Stop at inner track for cross, wrap to outer track for corner.
            val endL =
                if (u && d) {
                    x1 + ext
                } else if (u || d) {
                    x2 + ext
                } else {
                    cx
                }
            fillRectBounds(g, x, cy - lt / 2.0, endL, cy + lt / 2.0)
        }

        // --- RIGHT edges ---
        if (r) {
            val startTop =
                if (u) {
                    x2 - ext
                } else if (d) {
                    x1 - ext
                } else {
                    cx
                }
            val startBot =
                if (d) {
                    x2 - ext
                } else if (u) {
                    x1 - ext
                } else {
                    cx
                }
            fillRectBounds(g, startTop, y1 - ext, x + w, y1 + ext)
            fillRectBounds(g, startBot, y2 - ext, x + w, y2 + ext)
        } else if (right != NONE) {
            val rt = thickness(right, w, h)
            val startR =
                if (u && d) {
                    x2 - ext
                } else if (u || d) {
                    x1 - ext
                } else {
                    cx
                }
            fillRectBounds(g, startR, cy - rt / 2.0, x + w, cy + rt / 2.0)
        }

        // --- UP edges ---
        if (u) {
            val endLeft =
                if (l) {
                    y1 + ext
                } else if (r) {
                    y2 + ext
                } else {
                    cy
                }
            val endRight =
                if (r) {
                    y1 + ext
                } else if (l) {
                    y2 + ext
                } else {
                    cy
                }
            fillRectBounds(g, x1 - ext, y, x1 + ext, endLeft)
            fillRectBounds(g, x2 - ext, y, x2 + ext, endRight)
        } else if (up != NONE) {
            val ut = thickness(up, w, h)
            val endU =
                if (l && r) {
                    y1 + ext
                } else if (l || r) {
                    y2 + ext
                } else {
                    cy
                }
            fillRectBounds(g, cx - ut / 2.0, y, cx + ut / 2.0, endU)
        }

        // --- DOWN edges ---
        if (d) {
            val startLeft =
                if (l) {
                    y2 - ext
                } else if (r) {
                    y1 - ext
                } else {
                    cy
                }
            val startRight =
                if (r) {
                    y2 - ext
                } else if (l) {
                    y2 - ext
                } else {
                    cy
                }
            fillRectBounds(g, x1 - ext, startLeft, x1 + ext, y + h)
            fillRectBounds(g, x2 - ext, startRight, x2 + ext, y + h)
        } else if (down != NONE) {
            val dt = thickness(down, w, h)
            val startD =
                if (l && r) {
                    y2 - ext
                } else if (l || r) {
                    y1 - ext
                } else {
                    cy
                }
            fillRectBounds(g, cx - dt / 2.0, startD, cx + dt / 2.0, y + h)
        }
    }

    private fun paintDashedHorizontal(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        style: Int,
        dashCount: Int,
    ) {
        val t = thickness(style, w, h)
        val cy = y + h / 2.0

        iterateDashes(dashCount, w) { startOffset, endOffset ->
            fillRectBounds(g, x + startOffset, cy - t / 2.0, x + endOffset, cy + t / 2.0)
        }
    }

    private fun paintDashedVertical(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        style: Int,
        dashCount: Int,
    ) {
        val t = thickness(style, w, h)
        val cx = x + w / 2.0

        iterateDashes(dashCount, h) { startOffset, endOffset ->
            fillRectBounds(g, cx - t / 2.0, y + startOffset, cx + t / 2.0, y + endOffset)
        }
    }

    /**
     * Calculates absolute mathematical bounds for half-dash topology.
     * Inlined to avoid object allocation during the render loop.
     */
    private inline fun iterateDashes(
        dashCount: Int,
        dimension: Double,
        block: (start: Double, end: Double) -> Unit,
    ) {
        val units = dashCount * 2.0
        for (i in 0..dashCount) {
            val startUnit = if (i == 0) 0.0 else i * 2.0 - 0.5
            val endUnit = if (i == dashCount) units else i * 2.0 + 0.5

            val startPx = (startUnit * dimension) / units
            val endPx = (endUnit * dimension) / units

            block(startPx, endPx)
        }
    }

    /**
     * Replaces fillHorizontal and fillVertical.
     * Takes exact bounding box coordinates, rounds to integer boundaries at the final step,
     * guaranteeing no lines can ever overshoot the terminal cell dimensions.
     */
    private fun fillRectBounds(
        g: Graphics2D,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ) {
        val x1 = left.roundToInt()
        val y1 = top.roundToInt()
        val x2 = right.roundToInt()
        val y2 = bottom.roundToInt()
        g.fillRect(x1, y1, maxOf(1, x2 - x1), maxOf(1, y2 - y1))
    }

    private fun paintRoundedCorner(
        g: Graphics2D,
        codePoint: Int,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        fallbackEdges: Int,
    ) {
        val strokeThickness = thickness(LIGHT, w, h)
        if (w <= strokeThickness || h <= strokeThickness) {
            paintPackedEdges(g, x, y, w, h, fallbackEdges)
            return
        }

        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val rx = w / 2.0
        val ry = h / 2.0

        val path = pathLocal.get().apply { reset() }

        when (codePoint) {
            0x256D -> { // ╭ Top-Left
                path.moveTo(cx, y + h)
                path.curveTo(cx, (y + h) - ry * KAPPA, (x + w) - rx * KAPPA, cy, x + w, cy)
            }
            0x256E -> { // ╮ Top-Right
                path.moveTo(cx, y + h)
                path.curveTo(cx, (y + h) - ry * KAPPA, x + rx * KAPPA, cy, x, cy)
            }
            0x256F -> { // ╯ Bottom-Right
                path.moveTo(cx, y)
                path.curveTo(cx, y + ry * KAPPA, x + rx * KAPPA, cy, x, cy)
            }
            0x2570 -> { // ╰ Bottom-Left
                path.moveTo(cx, y)
                path.curveTo(cx, y + ry * KAPPA, (x + w) - rx * KAPPA, cy, x + w, cy)
            }
            else -> return
        }

        withAntialiasing(g, strokeThickness.toFloat()) {
            g.draw(path)
        }
    }

    private fun paintDiagonal(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        mask: Int,
    ) {
        val path = pathLocal.get().apply { reset() }

        if (mask and DIAGONAL_RISING != 0) {
            path.moveTo(x, y + h)
            path.lineTo(x + w, y)
        }
        if (mask and DIAGONAL_FALLING != 0) {
            path.moveTo(x, y)
            path.lineTo(x + w, y + h)
        }

        val strokeThickness = thickness(LIGHT, w, h)
        withAntialiasing(g, strokeThickness.toFloat()) {
            g.draw(path)
        }
    }

    /**
     * Executes drawing commands with required anti-aliasing and stroke geometry, safely restoring
     * the prior context state regardless of execution outcome.
     */
    private inline fun withAntialiasing(
        g: Graphics2D,
        strokeWidth: Float,
        block: () -> Unit,
    ) {
        val oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL)
        val oldStroke = g.stroke

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.stroke = getStroke(strokeWidth)

        try {
            block()
        } finally {
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
            if (oldStrokeControl != null) g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, oldStrokeControl)
            g.stroke = oldStroke
        }
    }

    // --- Dynamic Scaling Mathematics ---

    private fun thin(
        w: Double,
        h: Double,
    ): Double = maxOf(1.0, minOf(w, h) / THIN_RATIO)

    private fun lightThickness(
        w: Double,
        h: Double,
    ): Double = maxOf(1.0, minOf(w, h) / LIGHT_RATIO)

    private fun thickness(
        style: Int,
        w: Double,
        h: Double,
    ): Double =
        when (style) {
            HEAVY -> maxOf(2.0, minOf(w, h) / HEAVY_RATIO)
            DOUBLE -> thin(w, h)
            else -> lightThickness(w, h)
        }

    private fun doubleOffset(
        w: Double,
        h: Double,
    ): Double = maxOf(2.0, minOf(w, h) / DOUBLE_OFFSET_RATIO)

    private companion object {
        private const val KAPPA = 0.552284749831
        private const val LIGHT_RATIO = 16.0
        private const val THIN_RATIO = 8.0
        private const val HEAVY_RATIO = 4.0
        private const val DOUBLE_OFFSET_RATIO = 5.0

        // Prevents allocation in hot path while ensuring thread safety.
        private val pathLocal = ThreadLocal.withInitial { Path2D.Double() }

        // Caches quantized strokes to avoid rapid allocation during corner/diagonal rendering.
        private val strokeCache = ConcurrentHashMap<Float, BasicStroke>()

        private fun getStroke(thickness: Float): BasicStroke {
            val quantized = (thickness * 10f).roundToInt() / 10f
            return strokeCache.getOrPut(quantized) {
                // CAP_SQUARE ensures anti-aliased path endpoints project thickness/2 into
                // the neighboring cells, perfectly sealing the float-to-int boundaries.
                BasicStroke(quantized, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
            }
        }
    }
}
