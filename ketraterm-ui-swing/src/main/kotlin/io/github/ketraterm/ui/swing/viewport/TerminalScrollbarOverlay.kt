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
package io.github.ketraterm.ui.swing.viewport

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.TerminalViewportState
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import kotlin.math.roundToInt

/**
 * EDT-owned overlay scrollbar painted inside the terminal's reserved right inset.
 */
internal class TerminalScrollbarOverlay {
    private var dragThumbOffsetY: Int = 0
    var hovered: Boolean = false
        private set
    var dragging: Boolean = false
        private set

    fun paint(
        g: Graphics2D,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        palette: TerminalColorPalette,
        componentWidth: Int,
        componentHeight: Int,
        state: TerminalViewportState,
    ) {
        if (!isThumbVisible(settings, activeBuffer, componentWidth, componentHeight, state)) return
        val thumb = thumbBounds(settings, activeBuffer, componentWidth, componentHeight, state) ?: return
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = thumbColor(palette)
            if (hovered || dragging) {
                graphics.color = hoverThumbColor(palette)
            }
            graphics.fillRoundRect(thumb.x, thumb.y, thumb.width, thumb.height, thumb.width, thumb.width)
        } finally {
            graphics.dispose()
        }
    }

    fun handlePressed(
        x: Int,
        y: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        state: TerminalViewportState,
        scrollTo: (Int, Boolean) -> Unit,
    ): Boolean {
        if (!containsGutter(settings, activeBuffer, componentWidth, componentHeight, x, y)) return false
        if (!isThumbVisible(settings, activeBuffer, componentWidth, componentHeight, state)) return true

        val thumb = thumbBounds(settings, activeBuffer, componentWidth, componentHeight, state) ?: return true
        dragging = true
        dragThumbOffsetY =
            if (thumb.contains(x, y)) {
                y - thumb.y
            } else {
                thumb.height / 2
            }
        scrollTo(offsetAtThumbTop(y - dragThumbOffsetY, settings, componentHeight, state), true)
        return true
    }

    fun handleDragged(
        y: Int,
        settings: SwingSettings,
        componentHeight: Int,
        state: TerminalViewportState,
        scrollTo: (Int, Boolean) -> Unit,
    ): Boolean {
        if (!dragging) return false
        scrollTo(offsetAtThumbTop(y - dragThumbOffsetY, settings, componentHeight, state), true)
        return true
    }

    fun handleReleased(
        y: Int,
        settings: SwingSettings,
        componentHeight: Int,
        state: TerminalViewportState,
        scrollTo: (Int, Boolean) -> Unit,
    ): Boolean {
        if (!dragging) return false
        dragging = false
        scrollTo(offsetAtThumbTop(y - dragThumbOffsetY, settings, componentHeight, state), false)
        return true
    }

    fun handleMoved(
        x: Int,
        y: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
    ): Boolean {
        val nextHovered = containsGutter(settings, activeBuffer, componentWidth, componentHeight, x, y)
        val changed = hovered != nextHovered
        hovered = nextHovered
        return changed
    }

    fun handleExited(): Boolean {
        val changed = hovered || dragging
        hovered = false
        dragging = false
        return changed
    }

    fun containsGutter(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val rightInset = SwingTerminalChrome.right(settings, activeBuffer)
        if (rightInset <= 0 || componentWidth <= 0) return false
        val top = SwingTerminalChrome.top(settings, activeBuffer)
        val bottom = componentHeight - SwingTerminalChrome.bottom(settings, activeBuffer)
        return x in (componentWidth - rightInset) until componentWidth && y in top until bottom
    }

    fun thumbBounds(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        state: TerminalViewportState,
    ): Rectangle? {
        if (!isThumbVisible(settings, activeBuffer, componentWidth, componentHeight, state)) return null
        val rightInset = SwingTerminalChrome.right(settings, activeBuffer)
        val trackTop = SwingTerminalChrome.top(settings, activeBuffer)
        val trackHeight = componentHeight - trackTop - SwingTerminalChrome.bottom(settings, activeBuffer)
        val thumbWidth = minOf(MAX_THUMB_WIDTH, maxOf(MIN_THUMB_WIDTH, rightInset - THUMB_HORIZONTAL_PADDING * 2))
        val thumbHeight =
            (
                (trackHeight.toLong() * state.viewportHeightPixels.toLong()) /
                    (state.visualScrollRangePixels.toLong() + state.viewportHeightPixels.toLong())
            ).coerceIn(MIN_THUMB_HEIGHT.toLong(), trackHeight.toLong())
                .toInt()
        val travel = trackHeight - thumbHeight
        val thumbTop =
            if (travel <= 0) {
                trackTop
            } else {
                val topOriginPixels = state.visualScrollRangePixels - state.visualScrollOffsetPixels.roundToInt()
                trackTop + ((topOriginPixels.toLong() * travel.toLong()) / state.visualScrollRangePixels.toLong()).toInt()
            }
        val x = componentWidth - rightInset + (rightInset - thumbWidth) / 2
        return Rectangle(x, thumbTop, thumbWidth, thumbHeight)
    }

    private fun offsetAtThumbTop(
        thumbTop: Int,
        settings: SwingSettings,
        componentHeight: Int,
        state: TerminalViewportState,
    ): Int {
        if (state.historySize <= 0 || state.visualScrollRangePixels <= 0) return 0
        val trackTop = SwingTerminalChrome.top(settings, TerminalRenderBufferKind.PRIMARY)
        val trackHeight = componentHeight - trackTop - SwingTerminalChrome.bottom(settings, TerminalRenderBufferKind.PRIMARY)
        if (trackHeight <= 0) return 0
        val thumbHeight = thumbBounds(settings, TerminalRenderBufferKind.PRIMARY, 1, componentHeight, state)?.height ?: return 0
        val travel = trackHeight - thumbHeight
        if (travel <= 0) return 0

        val clampedTop = thumbTop.coerceIn(trackTop, trackTop + travel)
        val topOriginPixels =
            (((clampedTop - trackTop).toLong() * state.visualScrollRangePixels.toLong()) / travel.toLong()).toInt()
        val topRow = topOriginPixels / state.cellHeightPixels
        return (state.historySize - topRow).coerceIn(0, state.historySize)
    }

    private fun isThumbVisible(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        state: TerminalViewportState,
    ): Boolean =
        activeBuffer != TerminalRenderBufferKind.ALTERNATE &&
            SwingTerminalChrome.right(settings, activeBuffer) > 0 &&
            componentWidth > 0 &&
            componentHeight > SwingTerminalChrome.verticalInset(settings, activeBuffer) &&
            state.historySize > 0 &&
            state.visualScrollRangePixels > 0 &&
            state.viewportHeightPixels > 0

    private fun thumbColor(palette: TerminalColorPalette): Color = blendedColor(palette, THUMB_ALPHA)

    private fun hoverThumbColor(palette: TerminalColorPalette): Color = blendedColor(palette, HOVER_THUMB_ALPHA)

    private fun blendedColor(
        palette: TerminalColorPalette,
        alpha: Int,
    ): Color {
        val foreground = Color(palette.defaultForeground, true)
        return Color(foreground.red, foreground.green, foreground.blue, alpha)
    }

    private companion object {
        private const val THUMB_HORIZONTAL_PADDING = 2
        private const val MIN_THUMB_WIDTH = 4
        private const val MAX_THUMB_WIDTH = 6
        private const val MIN_THUMB_HEIGHT = 24
        private const val THUMB_ALPHA = 96
        private const val HOVER_THUMB_ALPHA = 160
    }
}
