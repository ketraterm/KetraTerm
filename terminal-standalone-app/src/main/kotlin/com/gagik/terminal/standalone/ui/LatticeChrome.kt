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

/**
 * Visual constants for the standalone Swing host.
 *
 * All colours follow a sleek, modern dark palette.
 */
internal object LatticeChrome {
    const val APP_TITLE = "Lattice"

    // ── Surfaces ──────────────────────────────────────────────────────────────

    /** Root window background and deep surface. */
    var SURFACE: Color = Color(0x181818)

    /** Title bar and tab strip background. */
    var TOP_BAR_BACKGROUND: Color = Color(0x20, 0x20, 0x24)

    /** Active terminal content background. */
    var TERMINAL_BACKGROUND: Color = Color(0x181818)

    // ── Tab states ────────────────────────────────────────────────────────────

    /** Background of the selected tab — a solid dark charcoal grey. */
    var TAB_SELECTED_BG: Color = Color(0x18, 0x18, 0x18)

    /** Background shown when hovering over an unselected tab. */
    var TAB_HOVER_BG: Color = Color(0x2B, 0x2D, 0x30)

    // ── Controls ──────────────────────────────────────────────────────────────

    var POPUP_BACKGROUND: Color = Color(0x2B2B2B)
    var CONTROL_BACKGROUND: Color = Color(0x3C3C3C)
    var CONTROL_HOVER: Color = Color(0x4A4A4A)
    var CONTROL_PRESSED: Color = Color(0x555555)

    // ── Text ──────────────────────────────────────────────────────────────────

    /** Primary text — used for selected tab labels and active UI elements. */
    var TEXT_PRIMARY: Color = Color(0xFFFFFF)

    /** Secondary text — used for unselected tab labels. */
    var TEXT_SECONDARY: Color = Color(0xAAAAAA)

    // ── Accent ────────────────────────────────────────────────────────────────

    /** Brand accent. */
    var ACCENT: Color = Color(0x4DA3FF)

    // ── Borders ───────────────────────────────────────────────────────────────

    var BORDER: Color = Color(0x2B, 0x2D, 0x30)

    // ── Scrollbar ─────────────────────────────────────────────────────────────

    var SCROLLBAR_TRACK: Color = Color(0x181818)
    var SCROLLBAR_THUMB: Color = Color(0x555555)
    var SCROLLBAR_THUMB_HOVER: Color = Color(0x707070)
    var SCROLLBAR_THUMB_PRESSED: Color = Color(0x909090)
    val SCROLLBAR_SIZE: Dimension = Dimension(10, 1)

    /**
     * Programmatically derives all visual theme elements and updates UIManager defaults.
     */
    fun applyPalette(palette: TerminalColorPalette) {
        val bg = Color(palette.defaultBackground, true)
        val fg = Color(palette.defaultForeground, true)
        val isDark = isDarkColor(bg)

        SURFACE = if (isDark) adjustBrightness(bg, 0.75f) else adjustBrightness(bg, 0.92f)
        TOP_BAR_BACKGROUND = SURFACE
        TERMINAL_BACKGROUND = bg
        TAB_SELECTED_BG = bg

        val hoverRatio = 0.08f
        val borderRatio = 0.15f
        val textSecondaryRatio = 0.55f

        TAB_HOVER_BG = blendColors(bg, fg, hoverRatio)
        BORDER = blendColors(bg, fg, borderRatio)

        TEXT_PRIMARY = fg
        TEXT_SECONDARY = blendColors(bg, fg, textSecondaryRatio)

        ACCENT = Color(palette.selectionBackground, true)

        POPUP_BACKGROUND = blendColors(bg, fg, 0.12f)
        CONTROL_BACKGROUND = blendColors(bg, fg, 0.18f)
        CONTROL_HOVER = blendColors(bg, fg, 0.26f)
        CONTROL_PRESSED = blendColors(bg, fg, 0.32f)

        SCROLLBAR_TRACK = bg
        SCROLLBAR_THUMB = blendColors(bg, fg, 0.25f)
        SCROLLBAR_THUMB_HOVER = blendColors(bg, fg, 0.35f)
        SCROLLBAR_THUMB_PRESSED = blendColors(bg, fg, 0.45f)

        // Update FlatLaf UIManager parameters
        UIManager.put("TitlePane.background", TOP_BAR_BACKGROUND)
        UIManager.put("TitlePane.foreground", TEXT_PRIMARY)
        UIManager.put("TitlePane.inactiveBackground", TOP_BAR_BACKGROUND)
        UIManager.put("TitlePane.inactiveForeground", TEXT_SECONDARY)
        UIManager.put("Panel.background", SURFACE)
        UIManager.put("Separator.foreground", BORDER)
        UIManager.put("Button.background", CONTROL_BACKGROUND)
        UIManager.put("Button.hoverBackground", CONTROL_HOVER)
        UIManager.put("Button.pressedBackground", CONTROL_PRESSED)
        UIManager.put("Button.foreground", TEXT_PRIMARY)
        UIManager.put("PopupMenu.background", POPUP_BACKGROUND)
        UIManager.put("MenuItem.selectionBackground", CONTROL_HOVER)
        UIManager.put("MenuItem.selectionForeground", TEXT_PRIMARY)
    }

    private fun isDarkColor(color: Color): Boolean {
        val luminance = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
        return luminance < 128
    }

    private fun adjustBrightness(color: Color, factor: Float): Color {
        val r = (color.red * factor).toInt().coerceIn(0, 255)
        val g = (color.green * factor).toInt().coerceIn(0, 255)
        val b = (color.blue * factor).toInt().coerceIn(0, 255)
        return Color(r, g, b, color.alpha)
    }

    private fun blendColors(c1: Color, c2: Color, ratio: Float): Color {
        val r = (c1.red * (1 - ratio) + c2.red * ratio).toInt().coerceIn(0, 255)
        val g = (c1.green * (1 - ratio) + c2.green * ratio).toInt().coerceIn(0, 255)
        val b = (c1.blue * (1 - ratio) + c2.blue * ratio).toInt().coerceIn(0, 255)
        val a = (c1.alpha * (1 - ratio) + c2.alpha * ratio).toInt().coerceIn(0, 255)
        return Color(r, g, b, a)
    }
}
