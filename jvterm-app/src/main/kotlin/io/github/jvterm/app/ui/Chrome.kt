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
package io.github.jvterm.app.ui

import java.awt.Color
import java.awt.Dimension
import javax.swing.UIManager
import kotlin.math.pow

private data class ChromeColors(
    val surface: Color,
    val topBarBackground: Color,
    val terminalBackground: Color,
    val tabSelectedBackground: Color,
    val tabHoverBackground: Color,
    val popupBackground: Color,
    val controlBackground: Color,
    val controlHover: Color,
    val controlPressed: Color,
    val textPrimary: Color,
    val textHover: Color,
    val textSecondary: Color,
    val controlText: Color,
    val controlTextDisabled: Color,
    val accent: Color,
    val border: Color,
    val divider: Color,
    val scrollbarTrack: Color,
    val scrollbarThumb: Color,
    val scrollbarThumbHover: Color,
    val scrollbarThumbPressed: Color,
) {
    companion object {
        fun fallback(): ChromeColors =
            ChromeColors(
                surface = Color(0x18, 0x18, 0x18),
                topBarBackground = Color(0x18, 0x18, 0x18),
                terminalBackground = Color(0x18, 0x18, 0x18),
                tabSelectedBackground = Color(0x18, 0x18, 0x18),
                tabHoverBackground = Color(0x2B, 0x2D, 0x30),
                popupBackground = Color(0x2B, 0x2B, 0x2B),
                controlBackground = Color(0x3C, 0x3C, 0x3C),
                controlHover = Color(0x4A, 0x4A, 0x4A),
                controlPressed = Color(0x55, 0x55, 0x55),
                textPrimary = Color.WHITE,
                textHover = Color(0xDF, 0xE1, 0xE5),
                textSecondary = Color(0xAA, 0xAA, 0xAA),
                controlText = Color(0xCF, 0xD2, 0xD6),
                controlTextDisabled = Color(0x5E, 0x63, 0x6B),
                accent = Color(0x4D, 0xA3, 0xFF),
                border = Color(0x2B, 0x2D, 0x30),
                divider = Color(0xFF, 0xFF, 0xFF, 60),
                scrollbarTrack = Color(0x18, 0x18, 0x18),
                scrollbarThumb = Color(0x55, 0x55, 0x55),
                scrollbarThumbHover = Color(0x70, 0x70, 0x70),
                scrollbarThumbPressed = Color(0x90, 0x90, 0x90),
            )
    }
}

/**
 * Palette-derived visual tokens for the standalone Swing host.
 *
 * The reusable terminal component owns terminal rendering. This host object
 * owns only standalone chrome colors such as tab states, menus, and scrollbars.
 */
internal object Chrome {
    const val APP_TITLE = "JvTerm"

    /** Root window background and deep surface. */
    val surface: Color
        get() = colors.surface

    /** Title bar and tab strip background. */
    val topBarBackground: Color
        get() = colors.topBarBackground

    /** Active terminal content background. */
    val terminalBackground: Color
        get() = colors.terminalBackground

    /** Background of the selected tab. */
    val tabSelectedBackground: Color
        get() = colors.tabSelectedBackground

    /** Background shown when hovering over an unselected tab. */
    val tabHoverBackground: Color
        get() = colors.tabHoverBackground

    /** Popup menu and menu item background. */
    val popupBackground: Color
        get() = colors.popupBackground

    /** Resting background for compact chrome controls. */
    val controlBackground: Color
        get() = colors.controlBackground

    /** Hover background for compact chrome controls. */
    val controlHover: Color
        get() = colors.controlHover

    /** Pressed background for compact chrome controls. */
    val controlPressed: Color
        get() = colors.controlPressed

    /** Primary text used for selected tab labels and active UI elements. */
    val textPrimary: Color
        get() = colors.textPrimary

    /** Hover text used for inactive tabs and controls under the pointer. */
    val textHover: Color
        get() = colors.textHover

    /** Secondary text used for unselected tab labels. */
    val textSecondary: Color
        get() = colors.textSecondary

    /** Text used for enabled compact action icons. */
    val controlText: Color
        get() = colors.controlText

    /** Text used for disabled compact action icons. */
    val controlTextDisabled: Color
        get() = colors.controlTextDisabled

    /** Brand accent. */
    val accent: Color
        get() = colors.accent

    /** Outline and separator color. */
    val border: Color
        get() = colors.border

    /** Subtle divider color for adjacent inactive controls. */
    val divider: Color
        get() = colors.divider

    /** Scrollbar track color. */
    val scrollbarTrack: Color
        get() = colors.scrollbarTrack

    /** Scrollbar thumb color. */
    val scrollbarThumb: Color
        get() = colors.scrollbarThumb

    /** Scrollbar thumb hover color. */
    val scrollbarThumbHover: Color
        get() = colors.scrollbarThumbHover

    /** Scrollbar thumb pressed color. */
    val scrollbarThumbPressed: Color
        get() = colors.scrollbarThumbPressed

    /** Preferred scrollbar width. */
    val scrollbarSize: Dimension = Dimension(10, 1)

    private var colors = ChromeColors.fallback()

    /**
     * Derives standalone chrome colors from [palette] and updates Swing defaults.
     */
    fun applyPalette(palette: io.github.jvterm.render.api.TerminalColorPalette) {
        val bg = Color(palette.defaultBackground, true)
        val fg = Color(palette.defaultForeground, true)
        val isDark = isDarkColor(bg)
        val surfaceTarget = if (isDark) fg else Color.WHITE

        val surface = blendColors(bg, surfaceTarget, if (isDark) 0.09f else 0.10f)
        val tabHoverBackground = blendColors(surface, fg, if (isDark) 0.11f else 0.08f)
        val border = blendColors(surface, fg, if (isDark) 0.22f else 0.26f)
        val textPrimary = ensureContrast(fg, bg, MINIMUM_TEXT_CONTRAST)

        colors =
            ChromeColors(
                surface = surface,
                topBarBackground = surface,
                terminalBackground = bg,
                tabSelectedBackground = bg,
                tabHoverBackground = tabHoverBackground,
                popupBackground = blendColors(surface, fg, 0.10f),
                controlBackground = blendColors(surface, fg, 0.14f),
                controlHover = blendColors(surface, fg, 0.22f),
                controlPressed = blendColors(surface, fg, 0.30f),
                textPrimary = textPrimary,
                textHover = ensureContrast(blendColors(surface, textPrimary, 0.86f), tabHoverBackground, MINIMUM_TEXT_CONTRAST),
                textSecondary = ensureContrast(blendColors(surface, textPrimary, 0.62f), surface, MINIMUM_TEXT_CONTRAST),
                controlText = ensureContrast(blendColors(surface, textPrimary, 0.78f), surface, MINIMUM_TEXT_CONTRAST),
                controlTextDisabled = blendColors(surface, textPrimary, 0.32f),
                accent = Color(palette.selectionBackground, true),
                border = border,
                divider = withAlpha(border, if (isDark) 150 else 190),
                scrollbarTrack = bg,
                scrollbarThumb = blendColors(bg, fg, 0.25f),
                scrollbarThumbHover = blendColors(bg, fg, 0.35f),
                scrollbarThumbPressed = blendColors(bg, fg, 0.45f),
            )

        applySwingDefaults()
    }

    /**
     * Applies the current chrome tokens to FlatLaf/Swing defaults.
     */
    fun applySwingDefaults() {
        val current = colors
        UIManager.put("TitlePane.background", current.topBarBackground)
        UIManager.put("TitlePane.foreground", current.textPrimary)
        UIManager.put("TitlePane.inactiveBackground", current.topBarBackground)
        UIManager.put("TitlePane.inactiveForeground", current.textSecondary)
        UIManager.put("TitlePane.borderColor", Color(0, 0, 0, 0))
        UIManager.put("MenuBar.borderColor", Color(0, 0, 0, 0))
        UIManager.put("MenuBar.margin", java.awt.Insets(0, 0, 0, 0))
        UIManager.put("Panel.background", current.surface)
        UIManager.put("Separator.foreground", current.border)
        UIManager.put("Button.background", current.controlBackground)
        UIManager.put("Button.hoverBackground", current.controlHover)
        UIManager.put("Button.pressedBackground", current.controlPressed)
        UIManager.put("Button.foreground", current.textPrimary)
        UIManager.put("PopupMenu.background", current.popupBackground)
        UIManager.put("MenuItem.selectionBackground", current.controlHover)
        UIManager.put("MenuItem.selectionForeground", current.textPrimary)
    }

    /**
     * Returns the WCAG contrast ratio between [foreground] and [background].
     */
    fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun isDarkColor(color: Color): Boolean {
        val luminance = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
        return luminance < 128
    }

    private fun blendColors(
        c1: Color,
        c2: Color,
        ratio: Float,
    ): Color {
        val r = (c1.red * (1 - ratio) + c2.red * ratio).toInt().coerceIn(0, 255)
        val g = (c1.green * (1 - ratio) + c2.green * ratio).toInt().coerceIn(0, 255)
        val b = (c1.blue * (1 - ratio) + c2.blue * ratio).toInt().coerceIn(0, 255)
        val a = (c1.alpha * (1 - ratio) + c2.alpha * ratio).toInt().coerceIn(0, 255)
        return Color(r, g, b, a)
    }

    private fun withAlpha(
        color: Color,
        alpha: Int,
    ): Color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun ensureContrast(
        foreground: Color,
        background: Color,
        minimumRatio: Double,
    ): Color {
        if (contrastRatio(foreground, background) >= minimumRatio) return foreground
        val target = if (isDarkColor(background)) Color.WHITE else Color.BLACK
        var ratio = 0.20f
        while (ratio <= 1.0f) {
            val candidate = blendColors(foreground, target, ratio)
            if (contrastRatio(candidate, background) >= minimumRatio) return candidate
            ratio += 0.10f
        }
        return target
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearized(color.red) +
            0.7152 * linearized(color.green) +
            0.0722 * linearized(color.blue)

    private fun linearized(component: Int): Double {
        val channel = component / 255.0
        return if (channel <= 0.03928) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }

    private const val MINIMUM_TEXT_CONTRAST = 4.5
}
