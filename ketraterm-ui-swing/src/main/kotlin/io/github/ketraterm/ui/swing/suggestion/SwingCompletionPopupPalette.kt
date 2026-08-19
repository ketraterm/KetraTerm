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
package io.github.ketraterm.ui.swing.suggestion

import java.awt.Color
import java.awt.Font
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Frozen typography and color state consumed by the custom-painted popup. */
internal data class SwingCompletionPopupAppearance(
    val primaryFont: Font,
    val matchedFont: Font,
    val detailFont: Font,
    val sourceFont: Font,
    val palette: SwingCompletionPopupPalette,
)

/** Immutable, contrast-safe colors used by the standalone completion surface. */
internal data class SwingCompletionPopupPalette(
    val background: Color,
    val foreground: Color,
    val mutedForeground: Color,
    val border: Color,
    val selectionBackground: Color,
    val selectedForeground: Color,
    val matchForeground: Color,
    val selectedMatchForeground: Color,
    val sourceBackground: Color,
    val sourceForeground: Color,
    val commandAccent: Color,
    val pathAccent: Color,
    val optionAccent: Color,
    val historyAccent: Color,
    val otherAccent: Color,
    val scrollTrack: Color,
    val scrollThumb: Color,
    val shadow: Color,
) {
    fun accent(role: SwingShellSuggestionAccentRole): Color =
        when (role) {
            SwingShellSuggestionAccentRole.COMMAND -> commandAccent
            SwingShellSuggestionAccentRole.PATH -> pathAccent
            SwingShellSuggestionAccentRole.OPTION -> optionAccent
            SwingShellSuggestionAccentRole.HISTORY -> historyAccent
            SwingShellSuggestionAccentRole.OTHER -> otherAccent
        }
}

/** Resolves one popup appearance when parent font or palette state changes. */
internal object SwingCompletionPopupAppearanceResolver {
    fun resolve(
        font: Font?,
        background: Color?,
        foreground: Color?,
    ): SwingCompletionPopupAppearance {
        val baseFont = font ?: DEFAULT_FONT
        val primaryFont = baseFont.deriveFont(Font.PLAIN)
        val matchedFont = primaryFont.deriveFont(Font.BOLD)
        val detailFont = primaryFont.deriveFont(Font.PLAIN, max(MIN_DETAIL_FONT_SIZE, primaryFont.size2D - 1f))
        val sourceFont = primaryFont.deriveFont(Font.BOLD, max(MIN_SOURCE_FONT_SIZE, primaryFont.size2D - 2f))
        return SwingCompletionPopupAppearance(
            primaryFont = primaryFont,
            matchedFont = matchedFont,
            detailFont = detailFont,
            sourceFont = sourceFont,
            palette = resolvePalette(background ?: DEFAULT_BACKGROUND, foreground ?: DEFAULT_FOREGROUND),
        )
    }

    internal fun contrastRatio(
        first: Color,
        second: Color,
    ): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = max(firstLuminance, secondLuminance)
        val darker = min(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun resolvePalette(
        background: Color,
        foreground: Color,
    ): SwingCompletionPopupPalette {
        val surface = background.opaque()
        val preferredForeground = foreground.opaque()
        val black = Color.BLACK
        val white = Color.WHITE
        val maximumContrast =
            if (contrastRatio(black, surface) >= contrastRatio(white, surface)) black else white
        val text = ensureContrast(preferredForeground, surface, TEXT_CONTRAST, maximumContrast)
        val dark = relativeLuminance(surface) < DARK_LUMINANCE_THRESHOLD
        val preferredMatch = if (dark) DARK_MATCH else LIGHT_MATCH
        val match = ensureContrast(preferredMatch, surface, TEXT_CONTRAST, text)
        val selection = mix(surface, match, if (dark) DARK_SELECTION_MIX else LIGHT_SELECTION_MIX)
        val selectedText = ensureContrast(text, selection, TEXT_CONTRAST, maximumContrast)
        val selectedMatch = ensureContrast(match, selection, TEXT_CONTRAST, selectedText)
        val muted = ensureContrast(mix(surface, text, MUTED_TEXT_MIX), surface, MUTED_TEXT_CONTRAST, text)
        val sourceBackground = mix(surface, text, SOURCE_BACKGROUND_MIX)
        val sourceForeground = ensureContrast(muted, sourceBackground, MUTED_TEXT_CONTRAST, text)

        fun accent(
            darkColor: Color,
            lightColor: Color,
        ): Color = ensureContrast(if (dark) darkColor else lightColor, surface, NON_TEXT_CONTRAST, text)

        return SwingCompletionPopupPalette(
            background = surface,
            foreground = text,
            mutedForeground = muted,
            border = mix(surface, text, BORDER_MIX),
            selectionBackground = selection,
            selectedForeground = selectedText,
            matchForeground = match,
            selectedMatchForeground = selectedMatch,
            sourceBackground = sourceBackground,
            sourceForeground = sourceForeground,
            commandAccent = accent(DARK_COMMAND, LIGHT_COMMAND),
            pathAccent = accent(DARK_PATH, LIGHT_PATH),
            optionAccent = accent(DARK_OPTION, LIGHT_OPTION),
            historyAccent = accent(DARK_HISTORY, LIGHT_HISTORY),
            otherAccent = muted,
            scrollTrack = mix(surface, text, SCROLL_TRACK_MIX),
            scrollThumb = mix(surface, text, SCROLL_THUMB_MIX),
            shadow = Color(0, 0, 0, if (dark) DARK_SHADOW_ALPHA else LIGHT_SHADOW_ALPHA),
        )
    }

    private fun ensureContrast(
        candidate: Color,
        background: Color,
        minimumRatio: Double,
        fallback: Color,
    ): Color {
        val opaqueCandidate = candidate.opaque()
        if (contrastRatio(opaqueCandidate, background) >= minimumRatio) return opaqueCandidate
        val maximumContrast =
            if (contrastRatio(Color.BLACK, background) >= contrastRatio(Color.WHITE, background)) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        val effectiveFallback =
            fallback.opaque().takeIf { contrastRatio(it, background) >= minimumRatio } ?: maximumContrast
        var step = 1
        while (step <= CONTRAST_ADJUSTMENT_STEPS) {
            val adjusted = mix(opaqueCandidate, effectiveFallback, step.toDouble() / CONTRAST_ADJUSTMENT_STEPS)
            if (contrastRatio(adjusted, background) >= minimumRatio) return adjusted
            step++
        }
        return effectiveFallback
    }

    private fun mix(
        base: Color,
        overlay: Color,
        overlayRatio: Double,
    ): Color {
        val ratio = overlayRatio.coerceIn(0.0, 1.0)
        val baseRatio = 1.0 - ratio
        return Color(
            (base.red * baseRatio + overlay.red * ratio).toInt().coerceIn(0, 255),
            (base.green * baseRatio + overlay.green * ratio).toInt().coerceIn(0, 255),
            (base.blue * baseRatio + overlay.blue * ratio).toInt().coerceIn(0, 255),
        )
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearComponent(color.red) +
            0.7152 * linearComponent(color.green) +
            0.0722 * linearComponent(color.blue)

    private fun linearComponent(component: Int): Double {
        val normalized = component / 255.0
        return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }

    private fun Color.opaque(): Color = if (alpha == 255) this else Color(red, green, blue)

    private const val MIN_DETAIL_FONT_SIZE = 10f
    private const val MIN_SOURCE_FONT_SIZE = 9f
    private const val TEXT_CONTRAST = 4.5
    private const val MUTED_TEXT_CONTRAST = 4.5
    private const val NON_TEXT_CONTRAST = 3.0
    private const val DARK_LUMINANCE_THRESHOLD = 0.36
    private const val DARK_SELECTION_MIX = 0.24
    private const val LIGHT_SELECTION_MIX = 0.14
    private const val MUTED_TEXT_MIX = 0.66
    private const val SOURCE_BACKGROUND_MIX = 0.12
    private const val BORDER_MIX = 0.20
    private const val SCROLL_TRACK_MIX = 0.10
    private const val SCROLL_THUMB_MIX = 0.36
    private const val CONTRAST_ADJUSTMENT_STEPS = 12
    private const val DARK_SHADOW_ALPHA = 88
    private const val LIGHT_SHADOW_ALPHA = 48

    private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    private val DEFAULT_BACKGROUND = Color(0x10, 0x14, 0x18)
    private val DEFAULT_FOREGROUND = Color(0xE5, 0xE7, 0xEB)
    private val DARK_MATCH = Color(0x6E, 0xA6, 0xFF)
    private val LIGHT_MATCH = Color(0x0B, 0x57, 0xD0)
    private val DARK_COMMAND = Color(0x63, 0xD6, 0xAE)
    private val LIGHT_COMMAND = Color(0x06, 0x78, 0x57)
    private val DARK_PATH = Color(0x69, 0xA7, 0xFF)
    private val LIGHT_PATH = Color(0x1D, 0x4E, 0xD8)
    private val DARK_OPTION = Color(0xFF, 0xC8, 0x57)
    private val LIGHT_OPTION = Color(0xA1, 0x4B, 0x00)
    private val DARK_HISTORY = Color(0xD8, 0x92, 0xFF)
    private val LIGHT_HISTORY = Color(0x7E, 0x22, 0xCE)
}
