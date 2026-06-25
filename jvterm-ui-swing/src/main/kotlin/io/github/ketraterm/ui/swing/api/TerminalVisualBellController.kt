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
package io.github.ketraterm.ui.swing.api

import java.awt.Color
import java.awt.Graphics2D
import javax.swing.Timer

/**
 * UI-local visual bell animation state.
 *
 * The controller is owned by the Swing EDT. It keeps the active animation as
 * primitive timestamps and precomputed alpha colors so painting the bell
 * overlay does not allocate on animation frames.
 *
 * @param repaint callback invoked when the overlay should be repainted.
 */
internal class TerminalVisualBellController(
    private val repaint: () -> Unit,
) {
    private val timer =
        Timer(FRAME_DELAY_MILLIS) {
            if (!isActiveAt(nowMillis())) {
                stop()
            }
            repaint()
        }

    private val colors = arrayOfNulls<Color>(ALPHA_COUNT)
    private var enabled = true
    private var durationMillis = DEFAULT_DURATION_MILLIS
    private var edgeThicknessPixels = DEFAULT_EDGE_THICKNESS_PIXELS
    private var activeUntilMillis = 0L
    private var lastColorArgb = 0

    init {
        timer.isRepeats = true
        rebuildColors(DEFAULT_COLOR_ARGB)
    }

    /**
     * Reconfigures the animation from the latest immutable settings snapshot.
     *
     * @param enabled whether BEL events should trigger the visual overlay.
     * @param colorArgb packed ARGB overlay color.
     * @param durationMillis animation duration in milliseconds.
     * @param edgeThicknessPixels edge pulse thickness in pixels.
     */
    fun configure(
        enabled: Boolean,
        colorArgb: Int,
        durationMillis: Int,
        edgeThicknessPixels: Int,
    ) {
        this.enabled = enabled
        this.durationMillis = durationMillis
        this.edgeThicknessPixels = edgeThicknessPixels
        if (lastColorArgb != colorArgb) {
            rebuildColors(colorArgb)
        }
        if (!enabled || durationMillis <= 0 || edgeThicknessPixels <= 0) {
            stop()
            repaint()
        }
    }

    /**
     * Starts or restarts the visual bell pulse.
     */
    fun trigger() {
        triggerAt(nowMillis())
    }

    internal fun triggerAt(nowMillis: Long) {
        if (!enabled || durationMillis <= 0 || edgeThicknessPixels <= 0) return
        activeUntilMillis = nowMillis + durationMillis.toLong()
        if (!timer.isRunning) {
            timer.start()
        }
        repaint()
    }

    /**
     * Stops the active pulse and its timer.
     */
    fun stop() {
        activeUntilMillis = 0L
        timer.stop()
    }

    /**
     * Paints the current edge pulse, if active.
     *
     * @param g target graphics.
     * @param width component width.
     * @param height component height.
     */
    fun paint(
        g: Graphics2D,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val alpha = alphaAt(nowMillis())
        if (alpha <= 0) return
        val color = colors[alpha] ?: return
        val edge = edgeThicknessPixels.coerceAtMost(minOf(width, height))

        g.color = color
        g.fillRect(0, 0, width, edge)
        g.fillRect(0, height - edge, width, edge)
        if (height > edge * 2) {
            g.fillRect(0, edge, edge, height - edge * 2)
            g.fillRect(width - edge, edge, edge, height - edge * 2)
        } else {
            g.fillRect(0, 0, edge, height)
            g.fillRect(width - edge, 0, edge, height)
        }
    }

    internal fun alphaAt(nowMillis: Long): Int {
        if (!isActiveAt(nowMillis)) return 0
        val remaining = activeUntilMillis - nowMillis
        val elapsed = durationMillis.toLong() - remaining
        val remainingWeight = durationMillis.toLong() - elapsed
        val eased = (remainingWeight * remainingWeight) / durationMillis.toLong()
        return (eased * maxAlpha() / durationMillis.toLong()).toInt().coerceIn(0, maxAlpha())
    }

    private fun isActiveAt(nowMillis: Long): Boolean = activeUntilMillis > nowMillis

    private fun rebuildColors(colorArgb: Int) {
        lastColorArgb = colorArgb
        val rgb = colorArgb and RGB_MASK
        val configuredAlpha = colorArgb ushr ALPHA_SHIFT
        for (alpha in 0 until ALPHA_COUNT) {
            colors[alpha] = Color(rgb or (minOf(alpha, configuredAlpha) shl ALPHA_SHIFT), true)
        }
    }

    private fun maxAlpha(): Int = lastColorArgb ushr ALPHA_SHIFT

    private companion object {
        private const val FRAME_DELAY_MILLIS = 16
        private const val ALPHA_COUNT = 256
        private const val ALPHA_SHIFT = 24
        private const val RGB_MASK = 0x00FF_FFFF

        const val DEFAULT_COLOR_ARGB: Int = 0x66_4DA3FF
        const val DEFAULT_DURATION_MILLIS: Int = 240
        const val DEFAULT_EDGE_THICKNESS_PIXELS: Int = 18

        private fun nowMillis(): Long = System.nanoTime() / 1_000_000L
    }
}
