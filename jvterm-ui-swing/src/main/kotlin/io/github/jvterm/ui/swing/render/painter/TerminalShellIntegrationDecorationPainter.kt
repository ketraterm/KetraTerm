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
package io.github.jvterm.ui.swing.render.painter

import io.github.jvterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.jvterm.ui.swing.render.TerminalShellIntegrationViewportDecorations
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Paints shell-integration prompt dots and failed-command rails in the left gutter.
 */
internal class TerminalShellIntegrationDecorationPainter(
    private val colorCache: AwtColorCache,
) {
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
        val failed = decorations.commandLifecycleAt(row) == TerminalShellIntegrationCommandLifecycle.FAILED
        val markerColor =
            if (failed) settings.shellIntegrationFailedPromptDotColor else settings.shellIntegrationPromptDotColor
        if (hovered) {
            val haloDiameter = minOf(gutterWidth, metrics.cellHeight)
            val haloX = centerX - haloDiameter / 2
            val haloY = y + (metrics.cellHeight - haloDiameter) / 2
            g.color = colorCache.color(withAlpha(markerColor, HOVER_HALO_ALPHA))
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.fillOval(haloX, haloY, haloDiameter, haloDiameter)
        }
        g.color =
            colorCache.color(markerColor)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        try {
            g.fillOval(dotX, dotY, diameter, diameter)
        } finally {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        }
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

        private fun withAlpha(
            argb: Int,
            alpha: Int,
        ): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}
