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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.ui.swing.render.primitives.TerminalPowerlinePainter
import kotlin.math.pow

/**
 * Swing-specific visual interpretation of render colors.
 */
internal object SwingColors {
    private val LINEAR_LUMINANCE =
        DoubleArray(256) { i ->
            val normalized = i / 255.0
            if (normalized <= 0.03928) {
                normalized / 12.92
            } else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }

    fun relativeLuminance(color: Int): Double {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return 0.2126 * LINEAR_LUMINANCE[r] +
            0.7152 * LINEAR_LUMINANCE[g] +
            0.0722 * LINEAR_LUMINANCE[b]
    }

    fun contrastRatio(
        first: Int,
        second: Int,
    ): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun blend(
        foreground: Int,
        background: Int,
        foregroundWeight: Double,
    ): Int {
        val clampedWeight = foregroundWeight.coerceIn(0.0, 1.0)
        val backgroundWeight = 1.0 - clampedWeight
        return 0xFF000000.toInt() or
            ((((foreground ushr 16) and 0xFF) * clampedWeight + ((background ushr 16) and 0xFF) * backgroundWeight).toInt() shl 16) or
            ((((foreground ushr 8) and 0xFF) * clampedWeight + ((background ushr 8) and 0xFF) * backgroundWeight).toInt() shl 8) or
            (((foreground and 0xFF) * clampedWeight + (background and 0xFF) * backgroundWeight).toInt())
    }

    fun blendOver(
        fgColor: Int,
        bgColor: Int,
    ): Int {
        if (fgColor ushr 24 == 0) return bgColor
        if (fgColor ushr 24 == 255) return fgColor
        if (bgColor ushr 24 == 0) return fgColor
        val alpha = ((fgColor ushr 24) and 0xFF) / 255.0
        if (bgColor ushr 24 != 255) {
            val backgroundAlpha = (bgColor ushr 24) / 255.0 * (1.0 - alpha)
            val combined = alpha + backgroundAlpha
            val red = (((fgColor ushr 16) and 255) * alpha + ((bgColor ushr 16) and 255) * backgroundAlpha) / combined
            val green = (((fgColor ushr 8) and 255) * alpha + ((bgColor ushr 8) and 255) * backgroundAlpha) / combined
            val blue = ((fgColor and 255) * alpha + (bgColor and 255) * backgroundAlpha) / combined
            return ((combined * 255).toInt() shl 24) or (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()
        }
        val r = (((fgColor ushr 16) and 0xFF) * alpha + ((bgColor ushr 16) and 0xFF) * (1.0 - alpha)).toInt()
        val g = (((fgColor ushr 8) and 0xFF) * alpha + ((bgColor ushr 8) and 0xFF) * (1.0 - alpha)).toInt()
        val b = ((fgColor and 0xFF) * alpha + (bgColor and 0xFF) * (1.0 - alpha)).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    fun ensureContrast(
        fg: Int,
        bg: Int,
        minimumRatio: Double,
    ): Int {
        if (minimumRatio <= 1.0) return fg
        val visibleForeground = if (fg ushr 24 == 255) fg else blendOver(fg, bg)
        val fgLum = relativeLuminance(visibleForeground)
        val bgLum = relativeLuminance(bg)
        val lighter = maxOf(fgLum, bgLum)
        val darker = minOf(fgLum, bgLum)
        val ratio = (lighter + 0.05) / (darker + 0.05)

        if (ratio >= minimumRatio) {
            return fg
        }
        val blackRatio = (bgLum + 0.05) / 0.05
        val whiteRatio = 1.05 / (bgLum + 0.05)
        val lighten =
            when {
                fgLum > bgLum && whiteRatio >= minimumRatio -> true
                fgLum < bgLum && blackRatio >= minimumRatio -> false
                else -> whiteRatio >= blackRatio
            }
        val target = if (lighten) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        if (maxOf(blackRatio, whiteRatio) < minimumRatio) return target
        var low = 0
        var high = 255
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            val candidate = blend(target, visibleForeground, middle / 255.0)
            if (contrastRatio(candidate, bg) >= minimumRatio) high = middle else low = middle
        }
        return blend(target, visibleForeground, high / 255.0)
    }

    /**
     * Resolves the foreground color Swing should paint for [attrWord].
     */
    fun foreground(
        palette: TerminalColorPalette,
        attrWord: Long,
        codePoint: Int = 0,
        minimumContrastRatio: Double = 1.0,
        faintOpacity: Double = 0.5,
        background: Int = palette.background(attrWord),
    ): Int {
        if (TerminalRenderAttrs.isInvisible(attrWord)) {
            return background
        }

        val color = palette.foreground(attrWord)
        val fg = if (TerminalRenderAttrs.isFaint(attrWord)) blend(color, background, faintOpacity) else color
        if (isGraphicCodePoint(codePoint)) {
            return fg
        }
        return ensureContrast(fg, background, minimumContrastRatio)
    }

    fun isGraphicCodePoint(codePoint: Int): Boolean {
        // Box Drawing: U+2500..U+257F
        // Block Elements: U+2580..U+259F
        // Geometric Shapes: U+25A0..U+25FF
        // Braille Patterns: U+2800..U+28FF
        return codePoint in 0x2500..0x25FF || codePoint in 0x2800..0x28FF || TerminalPowerlinePainter.canPaint(codePoint)
    }

    /**
     * Resolves the background color Swing should paint for [attrWord].
     */
    fun background(
        palette: TerminalColorPalette,
        attrWord: Long,
    ): Int = palette.background(attrWord)
}
