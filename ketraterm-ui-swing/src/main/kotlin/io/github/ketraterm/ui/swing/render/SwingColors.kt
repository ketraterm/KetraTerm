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
                Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
        }

    private var lastFg = 0
    private var lastBg = 0
    private var lastAdjusted = 0

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
        val alpha = ((fgColor ushr 24) and 0xFF) / 255.0
        val r = (((fgColor ushr 16) and 0xFF) * alpha + ((bgColor ushr 16) and 0xFF) * (1.0 - alpha)).toInt()
        val g = (((fgColor ushr 8) and 0xFF) * alpha + ((bgColor ushr 8) and 0xFF) * (1.0 - alpha)).toInt()
        val b = ((fgColor and 0xFF) * alpha + (bgColor and 0xFF) * (1.0 - alpha)).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    fun ensureContrast(
        fg: Int,
        bg: Int,
    ): Int {
        if (fg == lastFg && bg == lastBg) {
            return lastAdjusted
        }
        val adjusted = calculateContrastAdjusted(fg, bg)
        lastFg = fg
        lastBg = bg
        lastAdjusted = adjusted
        return adjusted
    }

    private fun calculateContrastAdjusted(
        fg: Int,
        bg: Int,
    ): Int {
        val fgLum = relativeLuminance(fg)
        val bgLum = relativeLuminance(bg)
        val lighter = maxOf(fgLum, bgLum)
        val darker = minOf(fgLum, bgLum)
        val ratio = (lighter + 0.05) / (darker + 0.05)

        if (ratio >= 3.0) {
            return fg
        }

        // Adjust fg towards white or black
        val target = if (bgLum < 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

        // Stepwise blend
        for (step in 1..5) {
            val weight = step * 0.2
            val candidate = blend(target, fg, weight)
            val candLum = relativeLuminance(candidate)
            val candLighter = maxOf(candLum, bgLum)
            val candDarker = minOf(candLum, bgLum)
            val candRatio = (candLighter + 0.05) / (candDarker + 0.05)
            if (candRatio >= 3.0) {
                return candidate
            }
        }

        return target
    }

    /**
     * Resolves the foreground color Swing should paint for [attrWord].
     */
    fun foreground(
        palette: TerminalColorPalette,
        attrWord: Long,
        codePoint: Int = 0,
    ): Int {
        if (TerminalRenderAttrs.isInvisible(attrWord)) {
            return background(palette, attrWord)
        }

        val color = palette.foreground(attrWord)
        val fg = if (TerminalRenderAttrs.isFaint(attrWord)) dim(color) else color
        if (isGraphicCodePoint(codePoint)) {
            return fg
        }
        val bg = background(palette, attrWord)
        return ensureContrast(fg, bg)
    }

    private fun isGraphicCodePoint(codePoint: Int): Boolean {
        // Box Drawing: U+2500..U+257F
        // Block Elements: U+2580..U+259F
        // Geometric Shapes: U+25A0..U+25FF
        // Braille Patterns: U+2800..U+28FF
        return codePoint in 0x2500..0x25FF || codePoint in 0x2800..0x28FF
    }

    /**
     * Resolves the background color Swing should paint for [attrWord].
     */
    fun background(
        palette: TerminalColorPalette,
        attrWord: Long,
    ): Int = palette.background(attrWord)

    /**
     * Applies Swing's current faint rendering policy to a packed ARGB color.
     */
    fun dim(color: Int): Int {
        val alpha = color and 0xFF000000.toInt()
        val red = ((color ushr 16) and 0xFF) / 2
        val green = ((color ushr 8) and 0xFF) / 2
        val blue = (color and 0xFF) / 2
        return alpha or (red shl 16) or (green shl 8) or blue
    }
}
