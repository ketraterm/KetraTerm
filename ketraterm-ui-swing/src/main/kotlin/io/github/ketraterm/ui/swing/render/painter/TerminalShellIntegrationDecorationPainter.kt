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
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D

/**
 * Paints shell-integration gutter markers and prompt divider rules.
 */
internal class TerminalShellIntegrationDecorationPainter(
    private val colorCache: AwtColorCache,
) {
    private val promptOutline = Ellipse2D.Double()

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
        if (gutterWidth <= 0) return

        val y = row * metrics.cellHeight
        if (settings.shellIntegrationFailedCommandRailsVisible && decorations.hasFailedCommandRailAt(row)) {
            val width = minOf(settings.shellIntegrationFailedCommandRailWidth, gutterWidth, metrics.cellHeight)
            val centerX = -(gutterWidth / 2).coerceAtLeast(1)
            paintFailedCommandRail(g, settings, decorations, row, centerX - width / 2, y, width, metrics.cellHeight)
        }

        if (!settings.shellIntegrationPromptDotsVisible || !decorations.hasPromptStartAt(row)) return

        val centerX = -(gutterWidth / 2).coerceAtLeast(1)
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
    ) {
        if (contentWidth <= 0) return
        val showHover = hovered && interactive
        val override = if (showHover) settings.shellIntegrationPromptDividerHoverColor else settings.shellIntegrationPromptDividerColor
        val color = override ?: withAlpha(palette.defaultForeground, if (showHover) 0x70 else 0x30)
        if (color ushr 24 == 0) return
        g.color = colorCache.color(color)
        val thickness = minOf(2, metrics.cellHeight)
        val y = row * metrics.cellHeight - (metrics.cellHeight + thickness + 1) / 2
        g.fillRect(0, y, contentWidth, thickness)
    }

    private fun paintFailedCommandRail(
        g: Graphics2D,
        settings: SwingSettings,
        decorations: TerminalShellIntegrationViewportDecorations,
        row: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val continuesFromPreviousRow = decorations.hasFailedCommandRailAt(row - 1)
        val continuesIntoNextRow = decorations.hasFailedCommandRailAt(row + 1)
        val capInset = width / 2
        val topInset = if (continuesFromPreviousRow) 0 else capInset
        val bottomInset = if (continuesIntoNextRow) 0 else capInset

        g.color = colorCache.color(settings.shellIntegrationFailedCommandRailColor)
        g.fillRect(x, y + topInset, width, height - topInset - bottomInset)
        if (!continuesFromPreviousRow || !continuesIntoNextRow) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            try {
                if (!continuesFromPreviousRow) g.fillOval(x, y, width, width)
                if (!continuesIntoNextRow) g.fillOval(x, y + height - width, width, width)
            } finally {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            }
        }
    }

    private companion object {
        private const val HOVER_HALO_ALPHA = 0x52
        private val PROMPT_OUTLINE_STROKE = BasicStroke(1.5f)

        private fun withAlpha(
            argb: Int,
            alpha: Int,
        ): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}
