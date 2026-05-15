package com.gagik.terminal.ui.swing.render.primitives

import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_FALLING
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_RISING
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOUBLE
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOWN_SHIFT
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.HEAVY
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LEFT_SHIFT
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LIGHT
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.NONE
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.RIGHT_SHIFT
import com.gagik.terminal.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.UP_SHIFT
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D

/**
 * Paints Unicode box-drawing glyphs from packed glyph metadata.
 */
internal class TerminalBoxDrawingPainter {
    fun paint(g: Graphics2D, codePoint: Int, x: Int, y: Int, width: Int, height: Int) {
        val roundedFallback = TerminalBoxDrawingGlyphs.roundedFallbackEdges(codePoint)
        if (roundedFallback != NONE) {
            paintRoundedCorner(g, codePoint, x, y, width, height, roundedFallback)
            return
        }

        val horizontalDashStyle = TerminalBoxDrawingGlyphs.horizontalDashStyle(codePoint)
        if (horizontalDashStyle != NONE) {
            paintDashedHorizontal(g, x, y, width, height, horizontalDashStyle)
            return
        }

        val verticalDashStyle = TerminalBoxDrawingGlyphs.verticalDashStyle(codePoint)
        if (verticalDashStyle != NONE) {
            paintDashedVertical(g, x, y, width, height, verticalDashStyle)
            return
        }

        val diagonalMask = TerminalBoxDrawingGlyphs.diagonalMask(codePoint)
        if (diagonalMask != NONE) {
            paintDiagonal(g, x, y, width, height, diagonalMask)
            return
        }

        val packedEdges = TerminalBoxDrawingGlyphs.edges(codePoint)
        if (packedEdges != NONE) {
            paintPackedEdges(g, x, y, width, height, packedEdges)
        }
    }

    private fun paintPackedEdges(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, packed: Int) {
        val left = TerminalBoxDrawingGlyphs.edge(packed, LEFT_SHIFT)
        val right = TerminalBoxDrawingGlyphs.edge(packed, RIGHT_SHIFT)
        val up = TerminalBoxDrawingGlyphs.edge(packed, UP_SHIFT)
        val down = TerminalBoxDrawingGlyphs.edge(packed, DOWN_SHIFT)

        paintHorizontal(g, x, y, width, height, left, left = true)
        paintHorizontal(g, x, y, width, height, right, left = false)
        paintVertical(g, x, y, width, height, up, up = true)
        paintVertical(g, x, y, width, height, down, up = false)
        paintMixedDoubleBridges(g, x, y, width, height, left, right, up, down)
    }

    private fun paintHorizontal(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, style: Int, left: Boolean) {
        if (style == NONE) return
        val centerX = x + width / 2
        val lineThickness = thickness(style, width, height)
        val startX = if (left) x else centerX - lineThickness / 2
        val endX = if (left) centerX + (lineThickness + 1) / 2 else x + width
        if (style == DOUBLE) {
            val thin = thin(width, height)
            val offset = doubleOffset(width, height)
            fillHorizontal(g, startX, endX, y + height / 2 - offset, thin)
            fillHorizontal(g, startX, endX, y + height / 2 + offset, thin)
        } else {
            fillHorizontal(g, startX, endX, y + height / 2, lineThickness)
        }
    }

    private fun paintVertical(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, style: Int, up: Boolean) {
        if (style == NONE) return
        val centerY = y + height / 2
        val lineThickness = thickness(style, width, height)
        val startY = if (up) y else centerY - lineThickness / 2
        val endY = if (up) centerY + (lineThickness + 1) / 2 else y + height
        if (style == DOUBLE) {
            val thin = thin(width, height)
            val offset = doubleOffset(width, height)
            fillVertical(g, x + width / 2 - offset, startY, endY, thin)
            fillVertical(g, x + width / 2 + offset, startY, endY, thin)
        } else {
            fillVertical(g, x + width / 2, startY, endY, lineThickness)
        }
    }

    private fun paintMixedDoubleBridges(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        up: Int,
        down: Int,
    ) {
        val horizontalHasDouble = left == DOUBLE || right == DOUBLE
        val verticalHasDouble = up == DOUBLE || down == DOUBLE
        val horizontalAny = left != NONE || right != NONE
        val verticalAny = up != NONE || down != NONE
        val offset = doubleOffset(width, height)

        if (horizontalHasDouble && verticalAny && !verticalHasDouble) {
            val style = stronger(up, down)
            fillVertical(
                g = g,
                centerX = x + width / 2,
                startY = y + height / 2 - offset,
                endY = y + height / 2 + offset + thin(width, height),
                thickness = thickness(style, width, height),
            )
        }

        if (verticalHasDouble && horizontalAny && !horizontalHasDouble) {
            val style = stronger(left, right)
            fillHorizontal(
                g = g,
                startX = x + width / 2 - offset,
                endX = x + width / 2 + offset + thin(width, height),
                centerY = y + height / 2,
                thickness = thickness(style, width, height),
            )
        }
    }

    private fun paintDashedHorizontal(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, style: Int) {
        val lineThickness = thickness(style, width, height)
        val centerY = y + height / 2
        val dash = maxOf(1, width / 3)
        fillHorizontal(g, x, minOf(x + width, x + dash), centerY, lineThickness)
        fillHorizontal(g, maxOf(x, x + width - dash), x + width, centerY, lineThickness)
    }

    private fun paintDashedVertical(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, style: Int) {
        val lineThickness = thickness(style, width, height)
        val centerX = x + width / 2
        val dash = maxOf(1, height / 3)
        fillVertical(g, centerX, y, minOf(y + height, y + dash), lineThickness)
        fillVertical(g, centerX, maxOf(y, y + height - dash), y + height, lineThickness)
    }

    private fun paintRoundedCorner(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        fallbackEdges: Int,
    ) {
        val thickness = thickness(LIGHT, width, height)
        if (width <= thickness || height <= thickness) {
            paintPackedEdges(g, x, y, width, height, fallbackEdges)
            return
        }

        val cx = x + width / 2.0
        val cy = y + height / 2.0
        val rx = width / 2.0
        val ry = height / 2.0

        val path = Path2D.Double()

        // Map endpoints explicitly to the boundary edges connecting to the adjacent cells.
        when (codePoint) {
            0x256D -> { // ╭ Top-Left: Connects Bottom-Center and Right-Center
                path.moveTo(cx, y + height.toDouble())
                path.curveTo(
                    cx, (y + height) - ry * KAPPA,
                    (x + width) - rx * KAPPA, cy,
                    x + width.toDouble(), cy
                )
            }
            0x256E -> { // ╮ Top-Right: Connects Bottom-Center and Left-Center
                path.moveTo(cx, y + height.toDouble())
                path.curveTo(
                    cx, (y + height) - ry * KAPPA,
                    x + rx * KAPPA, cy,
                    x.toDouble(), cy
                )
            }
            0x256F -> { // ╯ Bottom-Right: Connects Top-Center and Left-Center
                path.moveTo(cx, y.toDouble())
                path.curveTo(
                    cx, y + ry * KAPPA,
                    x + rx * KAPPA, cy,
                    x.toDouble(), cy
                )
            }
            0x2570 -> { // ╰ Bottom-Left: Connects Top-Center and Right-Center
                path.moveTo(cx, y.toDouble())
                path.curveTo(
                    cx, y + ry * KAPPA,
                    (x + width) - rx * KAPPA, cy,
                    x + width.toDouble(), cy
                )
            }
            else -> return
        }

        val oldStroke = g.stroke
        val oldHint = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // CAP_BUTT guarantees the stroke terminates flush with the cell boundary,
        // creating a seamless weld with the adjacent fillRect without bleeding over.
        g.stroke = BasicStroke(
            thickness.toFloat(),
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER
        )

        g.draw(path)

        g.stroke = oldStroke
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint)
    }

    private fun paintDiagonal(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, mask: Int) {
        if (mask and DIAGONAL_RISING != 0) {
            g.drawLine(x, y + height - 1, x + width - 1, y)
        }
        if (mask and DIAGONAL_FALLING != 0) {
            g.drawLine(x, y, x + width - 1, y + height - 1)
        }
    }

    private fun fillHorizontal(g: Graphics2D, startX: Int, endX: Int, centerY: Int, thickness: Int) {
        g.fillRect(startX, centerY - thickness / 2, maxOf(1, endX - startX), thickness)
    }

    private fun fillVertical(g: Graphics2D, centerX: Int, startY: Int, endY: Int, thickness: Int) {
        g.fillRect(centerX - thickness / 2, startY, thickness, maxOf(1, endY - startY))
    }

    private fun thin(width: Int, height: Int): Int {
        return maxOf(1, minOf(width, height) / 8)
    }

    private fun thickness(style: Int, width: Int, height: Int): Int {
        return when (style) {
            HEAVY -> maxOf(2, minOf(width, height) / 4)
            DOUBLE -> thin(width, height)
            else -> LIGHT_THICKNESS
        }
    }

    private fun doubleOffset(width: Int, height: Int): Int {
        return maxOf(1, minOf(width, height) / 5)
    }

    private fun stronger(first: Int, second: Int): Int {
        return maxOf(first, second).coerceAtLeast(LIGHT)
    }

    private companion object {
        private const val LIGHT_THICKNESS = 1
        // Magic constant for a perfect circular arc approximation using a cubic Bézier curve
        private const val KAPPA = 0.552284749831
    }
}