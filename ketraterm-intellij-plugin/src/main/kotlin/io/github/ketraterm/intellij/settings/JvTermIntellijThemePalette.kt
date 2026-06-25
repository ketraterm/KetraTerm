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
package io.github.ketraterm.intellij.settings

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.ui.swing.settings.TerminalTheme

/**
 * Builds terminal palettes from IntelliJ editor colors for IDE-hosted terminals.
 *
 * The returned palette is immutable and stores packed ARGB integers, so the
 * Swing renderer can keep using its allocation-conscious color path.
 */
internal object JvTermIntellijThemePalette {
    private const val MIN_READABLE_CONTRAST = 3.0
    private const val OPAQUE_MASK = 0xFF000000.toInt()
    private const val WHITE = OPAQUE_MASK or 0x00FFFFFF
    private const val BLACK = OPAQUE_MASK
    private const val DARK_SELECTION_SEED = OPAQUE_MASK or (0x4D shl 16) or (0xA3 shl 8) or 0xFF
    private const val LIGHT_SELECTION_SEED = OPAQUE_MASK or (0x0B shl 16) or (0x57 shl 8) or 0xD0

    /**
     * Creates a palette that follows the active IntelliJ editor color scheme.
     *
     * @return resolved terminal color palette.
     */
    fun currentIntellijPalette(): TerminalColorPalette =
        fromScheme(EditorColorsManager.getInstance().globalScheme)

    /**
     * Creates a palette from [scheme].
     *
     * @param scheme IntelliJ editor color scheme to adapt.
     * @return resolved terminal color palette.
     */
    fun fromScheme(scheme: EditorColorsScheme): TerminalColorPalette {
        val source =
            ColorSource(
                foreground = packOpaque(scheme.defaultForeground.rgb),
                background = packOpaque(scheme.defaultBackground.rgb),
                selectionForeground = packOpaque(scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR)?.rgb),
                selectionBackground = packOpaque(scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)?.rgb),
                cursor = packOpaque(scheme.getColor(EditorColors.CARET_COLOR)?.rgb),
            )
        return fromSource(source)
    }

    /**
     * Creates a palette from primitive IntelliJ color slots.
     *
     * @param source editor color values, nullable where the active scheme leaves
     * a slot inherited or unspecified.
     * @return resolved terminal color palette.
     */
    fun fromSource(source: ColorSource): TerminalColorPalette {
        val fallbackForeground = packOpaque(UIUtil.getLabelForeground().rgb) ?: WHITE
        val fallbackBackground = packOpaque(UIUtil.getPanelBackground().rgb) ?: BLACK
        val foreground = source.foreground ?: fallbackForeground
        val background = source.background ?: fallbackBackground
        val dark = isDark(background)
        val base = if (dark) TerminalTheme.ONE_DARK.createPalette() else TerminalTheme.CAMPBELL.createPalette()
        val indexed = base.toIndexedColorsArray()

        indexed[0] = background
        indexed[7] = foreground
        indexed[8] = blend(foreground, background, 0.45)
        indexed[15] = ensureReadable(foreground, background, dark)

        val selectionBackground = source.selectionBackground ?: defaultSelectionBackground(background, foreground, dark)
        val selectionForeground = source.selectionForeground ?: ensureReadable(foreground, selectionBackground, isDark(selectionBackground))
        val cursorBackground = source.cursor ?: foreground
        val cursorForeground = ensureReadable(background, cursorBackground, isDark(cursorBackground))

        return TerminalColorPalette(
            defaultForeground = foreground,
            defaultBackground = background,
            selectionForeground = selectionForeground,
            selectionBackground = selectionBackground,
            cursorForeground = cursorForeground,
            cursorBackground = cursorBackground,
            indexedColors = indexed,
            boldAsBright = true,
        )
    }

    private fun defaultSelectionBackground(
        background: Int,
        foreground: Int,
        dark: Boolean,
    ): Int = blend(if (dark) DARK_SELECTION_SEED else LIGHT_SELECTION_SEED, background, 0.72).let {
        if (contrastRatio(it, foreground) >= MIN_READABLE_CONTRAST) it else blend(foreground, background, 0.28)
    }

    private fun ensureReadable(
        foreground: Int,
        background: Int,
        darkBackground: Boolean,
    ): Int {
        if (contrastRatio(foreground, background) >= MIN_READABLE_CONTRAST) return foreground
        return if (darkBackground) WHITE else BLACK
    }

    private fun blend(
        foreground: Int,
        background: Int,
        foregroundWeight: Double,
    ): Int {
        val clampedWeight = foregroundWeight.coerceIn(0.0, 1.0)
        val backgroundWeight = 1.0 - clampedWeight
        return OPAQUE_MASK or
            (channel(red(foreground) * clampedWeight + red(background) * backgroundWeight) shl 16) or
            (channel(green(foreground) * clampedWeight + green(background) * backgroundWeight) shl 8) or
            channel(blue(foreground) * clampedWeight + blue(background) * backgroundWeight)
    }

    private fun channel(value: Double): Int = value.toInt().coerceIn(0, 255)

    private fun isDark(color: Int): Boolean = relativeLuminance(color) < 0.5

    private fun contrastRatio(
        first: Int,
        second: Int,
    ): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Int): Double =
        0.2126 * linear(red(color)) +
            0.7152 * linear(green(color)) +
            0.0722 * linear(blue(color))

    private fun linear(channel: Int): Double {
        val normalized = channel / 255.0
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
    }

    private fun packOpaque(rgb: Int?): Int? =
        if (rgb == null) {
            null
        } else {
            OPAQUE_MASK or (rgb and 0x00FFFFFF)
        }

    private fun red(color: Int): Int = (color ushr 16) and 0xFF

    private fun green(color: Int): Int = (color ushr 8) and 0xFF

    private fun blue(color: Int): Int = color and 0xFF

    /**
     * Editor color inputs used by the IntelliJ palette adapter.
     *
     * @property foreground default editor foreground packed ARGB color.
     * @property background default editor background packed ARGB color.
     * @property selectionForeground selected text foreground packed ARGB color.
     * @property selectionBackground selected text background packed ARGB color.
     * @property cursor editor caret packed ARGB color.
     */
    data class ColorSource(
        val foreground: Int?,
        val background: Int?,
        val selectionForeground: Int?,
        val selectionBackground: Int?,
        val cursor: Int?,
    )
}
