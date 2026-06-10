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
package com.gagik.terminal.standalone.ui

import com.gagik.terminal.render.api.TerminalColorPalette
import java.awt.Color
import java.awt.Dimension
import javax.swing.UIManager
import kotlin.math.pow

/**
 * Palette-derived visual constants for the standalone Swing host.
 *
 * The reusable terminal component owns terminal rendering. This host object
 * owns only standalone chrome colors such as tab states, menus, and scrollbars.
 */
internal object LatticeChrome {
    const val APP_TITLE = "Lattice"

    /** Root window background and deep surface. */
    var surface: Color = Color(0x18, 0x18, 0x18)

    /** Title bar and tab strip background. */
    var topBarBackground: Color = surface

    /** Active terminal content background. */
    var terminalBackground: Color = Color(0x18, 0x18, 0x18)

    /** Background of the selected tab. */
    var tabSelectedBackground: Color = terminalBackground

    /** Background shown when hovering over an unselected tab. */
    var tabHoverBackground: Color = Color(0x2B, 0x2D, 0x30)

    /** Popup menu and menu item background. */
    var popupBackground: Color = Color(0x2B, 0x2B, 0x2B)

    /** Resting background for compact chrome controls. */
    var controlBackground: Color = Color(0x3C, 0x3C, 0x3C)

    /** Hover background for compact chrome controls. */
    var controlHover: Color = Color(0x4A, 0x4A, 0x4A)

    /** Pressed background for compact chrome controls. */
    var controlPressed: Color = Color(0x55, 0x55, 0x55)

    /** Primary text used for selected tab labels and active UI elements. */
    var textPrimary: Color = Color.WHITE

    /** Hover text used for inactive tabs and controls under the pointer. */
    var textHover: Color = Color(0xDF, 0xE1, 0xE5)

    /** Secondary text used for unselected tab labels. */
    var textSecondary: Color = Color(0xAA, 0xAA, 0xAA)

    /** Text used for enabled compact action icons. */
    var controlText: Color = Color(0xCF, 0xD2, 0xD6)

    /** Text used for disabled compact action icons. */
    var controlTextDisabled: Color = Color(0x5E, 0x63, 0x6B)

    /** Brand accent. */
    var accent: Color = Color(0x4D, 0xA3, 0xFF)

    /** Outline and separator color. */
    var border: Color = Color(0x2B, 0x2D, 0x30)

    /** Subtle divider color for adjacent inactive controls. */
    var divider: Color = Color(0xFF, 0xFF, 0xFF, 60)

    /** Scrollbar track color. */
    var scrollbarTrack: Color = Color(0x18, 0x18, 0x18)

    /** Scrollbar thumb color. */
    var scrollbarThumb: Color = Color(0x55, 0x55, 0x55)

    /** Scrollbar thumb hover color. */
    var scrollbarThumbHover: Color = Color(0x70, 0x70, 0x70)

    /** Scrollbar thumb pressed color. */
    var scrollbarThumbPressed: Color = Color(0x90, 0x90, 0x90)

    /** Preferred scrollbar width. */
    val scrollbarSize: Dimension = Dimension(10, 1)

    /**
     * Derives standalone chrome colors from [palette] and updates Swing defaults.
     */
    fun applyPalette(palette: TerminalColorPalette) {
        val bg = Color(palette.defaultBackground, true)
        val fg = Color(palette.defaultForeground, true)
        val isDark = isDarkColor(bg)
        val surfaceTarget = if (isDark) fg else Color.WHITE

        surface = blendColors(bg, surfaceTarget, if (isDark) 0.09f else 0.10f)
        topBarBackground = surface
        terminalBackground = bg
        tabSelectedBackground = bg
        tabHoverBackground = blendColors(surface, fg, if (isDark) 0.11f else 0.08f)
        border = blendColors(surface, fg, if (isDark) 0.22f else 0.26f)
        divider = withAlpha(border, if (isDark) 150 else 190)

        textPrimary = ensureContrast(fg, tabSelectedBackground, MINIMUM_TEXT_CONTRAST)
        textHover = ensureContrast(blendColors(surface, textPrimary, 0.86f), tabHoverBackground, MINIMUM_TEXT_CONTRAST)
        textSecondary = ensureContrast(blendColors(surface, textPrimary, 0.62f), surface, MINIMUM_TEXT_CONTRAST)
        controlText = ensureContrast(blendColors(surface, textPrimary, 0.78f), surface, MINIMUM_TEXT_CONTRAST)
        controlTextDisabled = blendColors(surface, textPrimary, 0.32f)
        accent = Color(palette.selectionBackground, true)

        popupBackground = blendColors(surface, fg, 0.10f)
        controlBackground = blendColors(surface, fg, 0.14f)
        controlHover = blendColors(surface, fg, 0.22f)
        controlPressed = blendColors(surface, fg, 0.30f)

        scrollbarTrack = bg
        scrollbarThumb = blendColors(bg, fg, 0.25f)
        scrollbarThumbHover = blendColors(bg, fg, 0.35f)
        scrollbarThumbPressed = blendColors(bg, fg, 0.45f)

        UIManager.put("TitlePane.background", topBarBackground)
        UIManager.put("TitlePane.foreground", textPrimary)
        UIManager.put("TitlePane.inactiveBackground", topBarBackground)
        UIManager.put("TitlePane.inactiveForeground", textSecondary)
        UIManager.put("Panel.background", surface)
        UIManager.put("Separator.foreground", border)
        UIManager.put("Button.background", controlBackground)
        UIManager.put("Button.hoverBackground", controlHover)
        UIManager.put("Button.pressedBackground", controlPressed)
        UIManager.put("Button.foreground", textPrimary)
        UIManager.put("PopupMenu.background", popupBackground)
        UIManager.put("MenuItem.selectionBackground", controlHover)
        UIManager.put("MenuItem.selectionForeground", textPrimary)
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
