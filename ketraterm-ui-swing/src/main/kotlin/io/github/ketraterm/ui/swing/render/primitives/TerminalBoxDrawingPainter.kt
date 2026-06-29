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

import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_FALLING
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_RISING
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOUBLE
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOWN_SHIFT
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.HEAVY
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LEFT_SHIFT
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LIGHT
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.NONE
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.RIGHT_SHIFT
import io.github.ketraterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.UP_SHIFT
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
        nominalCellWidth: Int = width,
        nominalCellHeight: Int = height,
    ) {
        val xD = x.toDouble()
        val yD = y.toDouble()
        val wD = width.toDouble()
        val hD = height.toDouble()
        val metricWD = nominalCellWidth.toDouble()
        val metricHD = nominalCellHeight.toDouble()

        // Bounds use the exact rounded device cell; stroke style uses nominal
        // device metrics so fractional HiDPI cells do not alternate thickness.
        // Mutually exclusive routing. Evaluated in order of complexity/probability.
        val roundedFallback = TerminalBoxDrawingGlyphs.roundedFallbackEdges(codePoint)
        if (roundedFallback != NONE) {
            paintRoundedCorner(g, codePoint, xD, yD, wD, hD, metricWD, metricHD, roundedFallback)
            return
        }

        val horizontalDashStyle = TerminalBoxDrawingGlyphs.horizontalDashStyle(codePoint)
        if (horizontalDashStyle != NONE) {
            paintDashedHorizontal(
                g,
                xD,
                yD,
                wD,
                hD,
                metricWD,
                metricHD,
                horizontalDashStyle,
                TerminalBoxDrawingGlyphs.dashCount(codePoint),
            )
            return
        }

        val verticalDashStyle = TerminalBoxDrawingGlyphs.verticalDashStyle(codePoint)
        if (verticalDashStyle != NONE) {
            paintDashedVertical(
                g,
                xD,
                yD,
                wD,
                hD,
                metricWD,
                metricHD,
                verticalDashStyle,
                TerminalBoxDrawingGlyphs.dashCount(codePoint),
            )
            return
        }

        val diagonalMask = TerminalBoxDrawingGlyphs.diagonalMask(codePoint)
        if (diagonalMask != NONE) {
            paintDiagonal(g, xD, yD, wD, hD, metricWD, metricHD, diagonalMask)
            return
        }

        val packedEdges = TerminalBoxDrawingGlyphs.edges(codePoint)
        if (packedEdges != NONE) {
            paintPackedEdges(g, xD, yD, wD, hD, metricWD, metricHD, packedEdges)
        }
    }

    private fun paintPackedEdges(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        metricW: Double,
        metricH: Double,
        packed: Int,
    ) {
        val left = TerminalBoxDrawingGlyphs.edge(packed, LEFT_SHIFT)
        val right = TerminalBoxDrawingGlyphs.edge(packed, RIGHT_SHIFT)
        val up = TerminalBoxDrawingGlyphs.edge(packed, UP_SHIFT)
        val down = TerminalBoxDrawingGlyphs.edge(packed, DOWN_SHIFT)

        val hasDoubleH = left == DOUBLE || right == DOUBLE
        val hasDoubleV = up == DOUBLE || down == DOUBLE

        if (hasDoubleH || hasDoubleV) {
            paintDoubleEdges(g, x, y, w, h, metricW, metricH, left, right, up, down)
        } else {
            paintSingleEdges(g, x, y, w, h, metricW, metricH, left, right, up, down)
        }
    }

    private fun paintSingleEdges(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        metricW: Double,
        metricH: Double,
        left: Int,
        right: Int,
        up: Int,
        down: Int,
    ) {
        val cx = x + w / 2.0
        val cy = y + h / 2.0

        val tL = if (left != NONE) thickness(left, metricW, metricH) else 0.0
        val tR = if (right != NONE) thickness(right, metricW, metricH) else 0.0
        val tU = if (up != NONE) thickness(up, metricW, metricH) else 0.0
        val tD = if (down != NONE) thickness(down, metricW, metricH) else 0.0

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
        metricW: Double,
        metricH: Double,
        left: Int,
        right: Int,
        up: Int,
        down: Int,
    ) {
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val off = doubleOffset(metricW, metricH)
        val t = thin(metricW, metricH)
        val ext = t / 2.0

        val x1 = cx - off
        val x2 = cx + off
        val y1 = cy - off
        val y2 = cy + off

        val l = left == DOUBLE
        val r = right == DOUBLE
        val u = up == DOUBLE
        val d = down == DOUBLE

        if (l) {
            paintDoubleHorizontalFromLeft(
                g = g,
                x = x,
                yTrackA = y1,
                yTrackB = y2,
                endA = selectTrackLimit(u, d, x1 + ext, x2 + ext, cx),
                endB = selectTrackLimit(d, u, x1 + ext, x2 + ext, cx),
                ext = ext,
            )
        } else if (left != NONE) {
            val lt = thickness(left, metricW, metricH)
            val end =
                when {
                    u && d -> x1 + ext
                    u || d -> x2 + ext
                    else -> cx
                }
            fillRectBounds(g, x, cy - lt / 2.0, end, cy + lt / 2.0)
        }

        if (r) {
            paintDoubleHorizontalToRight(
                g = g,
                xRight = x + w,
                yTrackA = y1,
                yTrackB = y2,
                startA = selectTrackLimit(u, d, x2 - ext, x1 - ext, cx),
                startB = selectTrackLimit(d, u, x2 - ext, x1 - ext, cx),
                ext = ext,
            )
        } else if (right != NONE) {
            val rt = thickness(right, metricW, metricH)
            val start =
                when {
                    u && d -> x2 - ext
                    u || d -> x1 - ext
                    else -> cx
                }
            fillRectBounds(g, start, cy - rt / 2.0, x + w, cy + rt / 2.0)
        }

        if (u) {
            paintDoubleVerticalFromTop(
                g = g,
                y = y,
                xTrackA = x1,
                xTrackB = x2,
                endA = selectTrackLimit(l, r, y1 + ext, y2 + ext, cy),
                endB = selectTrackLimit(r, l, y1 + ext, y2 + ext, cy),
                ext = ext,
            )
        } else if (up != NONE) {
            val ut = thickness(up, metricW, metricH)
            val end =
                when {
                    l && r -> y1 + ext
                    l || r -> y2 + ext
                    else -> cy
                }
            fillRectBounds(g, cx - ut / 2.0, y, cx + ut / 2.0, end)
        }

        if (d) {
            paintDoubleVerticalToBottom(
                g = g,
                yBottom = y + h,
                xTrackA = x1,
                xTrackB = x2,
                startA = selectTrackLimit(l, r, y2 - ext, y1 - ext, cy),
                startB = selectTrackLimit(r, l, y2 - ext, y1 - ext, cy),
                ext = ext,
            )
        } else if (down != NONE) {
            val dt = thickness(down, metricW, metricH)
            val start =
                when {
                    l && r -> y2 - ext
                    l || r -> y1 - ext
                    else -> cy
                }
            fillRectBounds(g, cx - dt / 2.0, start, cx + dt / 2.0, y + h)
        }
    }

    private fun paintDashedHorizontal(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        metricW: Double,
        metricH: Double,
        style: Int,
        dashCount: Int,
    ) {
        val t = thickness(style, metricW, metricH)
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
        metricW: Double,
        metricH: Double,
        style: Int,
        dashCount: Int,
    ) {
        val t = thickness(style, metricW, metricH)
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
        metricW: Double,
        metricH: Double,
        fallbackEdges: Int,
    ) {
        val strokeThickness = quantizeStrokeWidth(thickness(LIGHT, metricW, metricH))
        if (w <= strokeThickness || h <= strokeThickness) {
            paintPackedEdges(g, x, y, w, h, metricW, metricH, fallbackEdges)
            return
        }

        val path = pathLocal.get().apply { reset() }

        when (codePoint) {
            0x256D ->
                appendRoundedQuarterAligned(
                    path = path,
                    x = x,
                    y = y,
                    w = w,
                    h = h,
                    verticalFromBottom = true,
                    horizontalToRight = true,
                    strokeThickness = strokeThickness,
                ) // ╭
            0x256E ->
                appendRoundedQuarterAligned(
                    path = path,
                    x = x,
                    y = y,
                    w = w,
                    h = h,
                    verticalFromBottom = true,
                    horizontalToRight = false,
                    strokeThickness = strokeThickness,
                ) // ╮
            0x256F ->
                appendRoundedQuarterAligned(
                    path = path,
                    x = x,
                    y = y,
                    w = w,
                    h = h,
                    verticalFromBottom = false,
                    horizontalToRight = false,
                    strokeThickness = strokeThickness,
                ) // ╯
            0x2570 ->
                appendRoundedQuarterAligned(
                    path = path,
                    x = x,
                    y = y,
                    w = w,
                    h = h,
                    verticalFromBottom = false,
                    horizontalToRight = true,
                    strokeThickness = strokeThickness,
                ) // ╰
            else -> return
        }

        withAntialiasing(
            g = g,
            strokeWidth = strokeThickness.toFloat(),
            cap = BasicStroke.CAP_BUTT,
            join = BasicStroke.JOIN_ROUND,
        ) {
            g.draw(path)
        }
    }

    private fun paintDiagonal(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        metricW: Double,
        metricH: Double,
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

        val strokeThickness = thickness(LIGHT, metricW, metricH)
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
        cap: Int = BasicStroke.CAP_SQUARE,
        join: Int = BasicStroke.JOIN_MITER,
        block: () -> Unit,
    ) {
        val oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL)
        val oldStroke = g.stroke

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.stroke = getStroke(strokeWidth, cap, join)

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

    /**
     * Shared branchless-style selector for double-track junction topology.
     *
     * - [primaryConnected] chooses [primaryLimit]
     * - else if [secondaryConnected] chooses [secondaryLimit]
     * - otherwise falls back to [centerLimit]
     *
     * No allocation, no object wrappers, no Pair/Triple.
     */
    private fun selectTrackLimit(
        primaryConnected: Boolean,
        secondaryConnected: Boolean,
        primaryLimit: Double,
        secondaryLimit: Double,
        centerLimit: Double,
    ): Double =
        when {
            primaryConnected -> primaryLimit
            secondaryConnected -> secondaryLimit
            else -> centerLimit
        }

    private fun paintDoubleHorizontalFromLeft(
        g: Graphics2D,
        x: Double,
        yTrackA: Double,
        yTrackB: Double,
        endA: Double,
        endB: Double,
        ext: Double,
    ) {
        fillRectBounds(g, x, yTrackA - ext, endA, yTrackA + ext)
        fillRectBounds(g, x, yTrackB - ext, endB, yTrackB + ext)
    }

    private fun paintDoubleHorizontalToRight(
        g: Graphics2D,
        xRight: Double,
        yTrackA: Double,
        yTrackB: Double,
        startA: Double,
        startB: Double,
        ext: Double,
    ) {
        fillRectBounds(g, startA, yTrackA - ext, xRight, yTrackA + ext)
        fillRectBounds(g, startB, yTrackB - ext, xRight, yTrackB + ext)
    }

    private fun paintDoubleVerticalFromTop(
        g: Graphics2D,
        y: Double,
        xTrackA: Double,
        xTrackB: Double,
        endA: Double,
        endB: Double,
        ext: Double,
    ) {
        fillRectBounds(g, xTrackA - ext, y, xTrackA + ext, endA)
        fillRectBounds(g, xTrackB - ext, y, xTrackB + ext, endB)
    }

    private fun paintDoubleVerticalToBottom(
        g: Graphics2D,
        yBottom: Double,
        xTrackA: Double,
        xTrackB: Double,
        startA: Double,
        startB: Double,
        ext: Double,
    ) {
        fillRectBounds(g, xTrackA - ext, startA, xTrackA + ext, yBottom)
        fillRectBounds(g, xTrackB - ext, startB, xTrackB + ext, yBottom)
    }

    /**
     * Appends one rounded quarter whose centerline is snapped to the same raster lane
     * used by fillRectBounds-based light box segments.
     *
     * This removes the subtle mismatch where straight box lines are pixel-rounded
     * while rounded corners are drawn from the purely mathematical center.
     */
    private fun appendRoundedQuarterAligned(
        path: Path2D.Double,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        verticalFromBottom: Boolean,
        horizontalToRight: Boolean,
        strokeThickness: Double,
    ) {
        val cx = snappedStrokeCenter(origin = x, span = w, strokeThickness = strokeThickness)
        val cy = snappedStrokeCenter(origin = y, span = h, strokeThickness = strokeThickness)

        val startX = cx
        val startY = if (verticalFromBottom) y + h else y

        val endX = if (horizontalToRight) x + w else x
        val endY = cy

        val rx = kotlin.math.abs(endX - cx)
        val ry = kotlin.math.abs(startY - cy)

        val control1X = startX
        val control1Y =
            if (verticalFromBottom) {
                startY - ry * KAPPA
            } else {
                startY + ry * KAPPA
            }

        val control2X =
            if (horizontalToRight) {
                endX - rx * KAPPA
            } else {
                endX + rx * KAPPA
            }
        val control2Y = endY

        path.moveTo(startX, startY)
        path.curveTo(control1X, control1Y, control2X, control2Y, endX, endY)
    }

    /**
     * Quantizes stroke width exactly like the stroke cache, so geometry and stroke
     * rasterization use the same effective width.
     */
    private fun quantizeStrokeWidth(thickness: Double): Double = (thickness * 10.0).roundToInt() / 10.0

    /**
     * Returns the centerline that corresponds to the same pixel-rounded band that
     * fillRectBounds would produce for a straight light line.
     */
    private fun snappedStrokeCenter(
        origin: Double,
        span: Double,
        strokeThickness: Double,
    ): Double {
        val nominalCenter = origin + span / 2.0
        val top = (nominalCenter - strokeThickness / 2.0).roundToInt()
        val bottom = (nominalCenter + strokeThickness / 2.0).roundToInt()
        return (top + bottom) / 2.0
    }

    private companion object {
        private const val KAPPA = 0.552284749831
        private const val LIGHT_RATIO = 16.0
        private const val THIN_RATIO = 8.0
        private const val HEAVY_RATIO = 4.0
        private const val DOUBLE_OFFSET_RATIO = 5.0

        private val pathLocal = ThreadLocal.withInitial { Path2D.Double() }

        private val strokeCache = ConcurrentHashMap<Int, BasicStroke>()

        private fun getStroke(
            thickness: Float,
            cap: Int,
            join: Int,
        ): BasicStroke {
            val quantized = (thickness * 10f).roundToInt() / 10f
            val widthKey = (quantized * 10f).roundToInt()
            val key = (widthKey shl 8) or (cap shl 4) or join

            return strokeCache.getOrPut(key) {
                BasicStroke(quantized, cap, join)
            }
        }
    }
}
