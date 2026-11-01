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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.ui.swing.render.TerminalShellIntegrationViewportDecorations
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPromptDecoration
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.math.roundToInt

/**
 * Paints shell-integration gutter markers and prompt divider rules.
 */
internal class TerminalShellIntegrationDecorationPainter(
    private val colorCache: AwtColorCache,
) {
    private val promptOutline = Ellipse2D.Double()
    private val railShape = Path2D.Double()
    private val decorationBounds = Rectangle2D.Double()

    /**
     * Paints shell-integration decorations for one visible row.
     */
    fun paint(
        g: Graphics2D,
        settings: SwingSettings,
        metrics: SwingMetrics,
        decorations: TerminalShellIntegrationViewportDecorations?,
        gutterWidth: Int,
        row: Int,
        hovered: Boolean,
        palette: TerminalColorPalette,
    ) {
        if (decorations == null) return
        if (gutterWidth <= 0 || settings.promptDecoration == SwingPromptDecoration.NONE) return

        val y = row * metrics.cellHeight
        val markerSpan =
            minOf(
                maxOf(settings.shellIntegrationPromptDotDiameter, settings.shellIntegrationFailedCommandRailWidth),
                gutterWidth,
                metrics.cellHeight,
            )
        val centerX = -(gutterWidth - gutterWidth / 2) - minOf(MARKER_LEFT_SHIFT, (gutterWidth - markerSpan) / 2)
        if (settings.shellIntegrationFailedCommandRailsVisible && hasFailedRailAt(settings, decorations, row)) {
            val width = minOf(settings.shellIntegrationFailedCommandRailWidth, gutterWidth, metrics.cellHeight)
            val recordId = decorations.commandRecordIdAt(row)
            paintFailedCommandRail(
                g,
                settings,
                centerX - width / 2.0,
                y,
                width,
                metrics.cellHeight,
                continuesFromPreviousRow =
                    hasFailedRailAt(settings, decorations, row - 1) && decorations.commandRecordIdAt(row - 1) == recordId,
                continuesIntoNextRow =
                    hasFailedRailAt(
                        settings,
                        decorations,
                        row + 1,
                    ) &&
                        decorations.commandRecordIdAt(row + 1) == recordId,
            )
        }

        if (settings.promptDecoration != SwingPromptDecoration.GUTTER ||
            !settings.shellIntegrationPromptDotsVisible ||
            !decorations.hasPromptStartAt(row)
        ) {
            return
        }

        val diameter = minOf(settings.shellIntegrationPromptDotDiameter, gutterWidth, metrics.cellHeight)
        val dotX = centerX - diameter / 2
        val dotY = y + (metrics.cellHeight - diameter) / 2
        val interactive = decorations.isPromptMarkerInteractiveAt(row)
        val showHover = hovered && interactive
        val failed = decorations.commandLifecycleAt(row) == TerminalShellIntegrationCommandLifecycle.FAILED
        val normalMarkerColor =
            if (failed) {
                settings.shellIntegrationFailedPromptDotColor
            } else {
                if (settings.shellIntegrationPromptDotColor == 0x8CFFFFFF.toInt()) {
                    (palette.defaultForeground and 0x00FFFFFF) or 0x8C000000.toInt()
                } else {
                    settings.shellIntegrationPromptDotColor
                }
            }
        val hoverMarkerColor =
            if (failed) settings.shellIntegrationFailedPromptDotHoverColor else settings.shellIntegrationPromptDotHoverColor
        val markerColor = if (showHover) hoverMarkerColor ?: normalMarkerColor else normalMarkerColor
        val previousAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        try {
            if (showHover) {
                val haloOverride =
                    if (failed) settings.shellIntegrationFailedPromptDotHoverHaloColor else settings.shellIntegrationPromptDotHoverHaloColor
                val haloColor = haloOverride ?: withAlpha(markerColor, HOVER_HALO_ALPHA)
                if (haloColor ushr 24 != 0) {
                    val haloDiameter = minOf(gutterWidth, metrics.cellHeight)
                    val haloX = centerX - haloDiameter / 2
                    val haloY = y + (metrics.cellHeight - haloDiameter) / 2
                    g.color = colorCache.color(haloColor)
                    g.fillOval(haloX, haloY, haloDiameter, haloDiameter)
                }
            }
            if (markerColor ushr 24 != 0) {
                g.color = colorCache.color(markerColor)
                if (interactive) {
                    g.fillOval(dotX, dotY, diameter, diameter)
                } else {
                    paintPromptOutline(g, dotX, dotY, diameter)
                }
            }
        } finally {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasing ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT)
        }
    }

    private fun paintPromptOutline(
        g: Graphics2D,
        x: Int,
        y: Int,
        diameter: Int,
    ) {
        if (diameter <= 2) {
            g.fillOval(x, y, diameter, diameter)
            return
        }
        val previousStroke = g.stroke
        val previousStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL)
        val inset = PROMPT_OUTLINE_STROKE.lineWidth / 2.0
        promptOutline.setFrame(x + inset, y + inset, diameter - inset * 2, diameter - inset * 2)
        g.stroke = PROMPT_OUTLINE_STROKE
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        try {
            g.draw(promptOutline)
        } finally {
            g.stroke = previousStroke
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, previousStrokeControl ?: RenderingHints.VALUE_STROKE_DEFAULT)
        }
    }

    /** Paints a rule centered in the visual band preceding the translated text row. */
    fun paintDivider(
        g: Graphics2D,
        settings: SwingSettings,
        metrics: SwingMetrics,
        row: Int,
        contentWidth: Int,
        hovered: Boolean,
        palette: TerminalColorPalette,
        interactive: Boolean,
        left: Int = 0,
    ) {
        if (contentWidth <= 0) return
        val showHover = hovered && interactive
        val override = if (showHover) settings.shellIntegrationPromptDividerHoverColor else settings.shellIntegrationPromptDividerColor
        val color = override ?: withAlpha(palette.defaultForeground, if (showHover) 0x70 else 0x30)
        if (color ushr 24 == 0) return
        g.color = colorCache.color(color)
        val thickness = minOf(2, metrics.cellHeight)
        val y = row * metrics.cellHeight - (metrics.cellHeight + thickness + 1) / 2
        g.fill(alignDecorationBounds(g, left.toDouble(), y, contentWidth, thickness, vertical = false))
    }

    private fun hasFailedRailAt(
        settings: SwingSettings,
        decorations: TerminalShellIntegrationViewportDecorations,
        row: Int,
    ): Boolean =
        if (settings.promptDecoration == SwingPromptDecoration.DIVIDER) {
            decorations.commandLifecycleAt(row) == TerminalShellIntegrationCommandLifecycle.FAILED
        } else {
            decorations.hasFailedCommandRailAt(row)
        }

    private fun paintFailedCommandRail(
        g: Graphics2D,
        settings: SwingSettings,
        x: Double,
        y: Int,
        width: Int,
        height: Int,
        continuesFromPreviousRow: Boolean,
        continuesIntoNextRow: Boolean,
    ) {
        val bounds = alignDecorationBounds(g, x, y, width, height, vertical = true)
        val left = bounds.x
        val right = bounds.maxX
        val top = bounds.y
        val bottom = bounds.maxY
        val center = bounds.centerX
        val radiusX = bounds.width / 2
        val radiusY = minOf(radiusX, bounds.height / 2)
        val topRadius = if (continuesFromPreviousRow) 0.0 else radiusY
        val bottomRadius = if (continuesIntoNextRow) 0.0 else radiusY
        railShape.reset()
        railShape.moveTo(left, top + topRadius)
        if (topRadius == 0.0) {
            railShape.lineTo(right, top)
        } else {
            railShape.curveTo(left, top + topRadius * (1 - ARC_CONTROL), center - radiusX * ARC_CONTROL, top, center, top)
            railShape.curveTo(center + radiusX * ARC_CONTROL, top, right, top + topRadius * (1 - ARC_CONTROL), right, top + topRadius)
        }
        railShape.lineTo(right, bottom - bottomRadius)
        if (bottomRadius == 0.0) {
            railShape.lineTo(left, bottom)
        } else {
            railShape.curveTo(right, bottom - bottomRadius * (1 - ARC_CONTROL), center + radiusX * ARC_CONTROL, bottom, center, bottom)
            railShape.curveTo(
                center - radiusX * ARC_CONTROL,
                bottom,
                left,
                bottom - bottomRadius * (1 - ARC_CONTROL),
                left,
                bottom - bottomRadius,
            )
        }
        railShape.closePath()
        g.color = colorCache.color(settings.shellIntegrationFailedCommandRailColor)
        val previousAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        try {
            g.fill(railShape)
        } finally {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasing ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT)
        }
    }

    // Round thickness independently of position, and share rounded boundaries between adjacent rows.
    private fun alignDecorationBounds(
        g: Graphics2D,
        x: Double,
        y: Int,
        width: Int,
        height: Int,
        vertical: Boolean,
    ): Rectangle2D.Double {
        val transform = g.transform
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY
        if (transform.shearX == 0.0 && transform.shearY == 0.0 && scaleX > 0.0 && scaleY > 0.0) {
            val left = (x * scaleX + transform.translateX).roundToInt()
            val top = (y * scaleY + transform.translateY).roundToInt()
            val nominalWidth = (width * scaleX).roundToInt().coerceAtLeast(1)
            val nominalHeight = (height * scaleY).roundToInt().coerceAtLeast(1)
            val deviceWidth =
                if (vertical) {
                    nominalWidth
                } else {
                    ((x + width) * scaleX + transform.translateX).roundToInt() -
                        left
                }
            val deviceHeight =
                if (vertical) {
                    ((y + height) * scaleY + transform.translateY).roundToInt() - top
                } else {
                    nominalHeight
                }
            decorationBounds.setRect(
                (left - transform.translateX) / scaleX,
                (top - transform.translateY) / scaleY,
                deviceWidth / scaleX,
                deviceHeight / scaleY,
            )
        } else {
            decorationBounds.setRect(x, y.toDouble(), width.toDouble(), height.toDouble())
        }
        return decorationBounds
    }

    private companion object {
        private const val HOVER_HALO_ALPHA = 0x52
        private const val MARKER_LEFT_SHIFT = 2
        private const val ARC_CONTROL = 0.5522847498307933
        private val PROMPT_OUTLINE_STROKE = BasicStroke(1.5f)

        private fun withAlpha(
            argb: Int,
            alpha: Int,
        ): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}
