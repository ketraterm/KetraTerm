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

import java.awt.Color
import java.awt.Dimension

/**
 * Visual constants for the standalone Swing host.
 *
 * All colours follow a sleek, modern dark palette.
 */
internal object LatticeChrome {
    const val APP_TITLE = "Lattice"

    // ── Surfaces ──────────────────────────────────────────────────────────────

    /** Root window background and deep surface. */
    val SURFACE: Color = Color(0x181818)

    /** Title bar and tab strip background. */
    val TOP_BAR_BACKGROUND: Color = Color(0x181818)

    /** Active terminal content background. */
    val TERMINAL_BACKGROUND: Color = Color(0x181818)

    // ── Tab states ────────────────────────────────────────────────────────────

    /** Background of the selected tab — a distinct muted blue pill. */
    val TAB_SELECTED_BG: Color = Color(0x3B4C68)

    /** Background shown when hovering over an unselected tab. */
    val TAB_HOVER_BG: Color = Color(0x2D2D2D)

    // ── Controls ──────────────────────────────────────────────────────────────

    val POPUP_BACKGROUND: Color = Color(0x2B2B2B)
    val CONTROL_BACKGROUND: Color = Color(0x3C3C3C)
    val CONTROL_HOVER: Color = Color(0x4A4A4A)
    val CONTROL_PRESSED: Color = Color(0x555555)

    // ── Text ──────────────────────────────────────────────────────────────────

    /** Primary text — used for selected tab labels and active UI elements. */
    val TEXT_PRIMARY: Color = Color(0xFFFFFF)

    /** Secondary text — used for unselected tab labels. */
    val TEXT_SECONDARY: Color = Color(0xAAAAAA)

    // ── Accent ────────────────────────────────────────────────────────────────

    /** Brand accent. */
    val ACCENT: Color = Color(0x4DA3FF)

    // ── Borders ───────────────────────────────────────────────────────────────

    val BORDER: Color = Color(0x3A3A3A)

    // ── Scrollbar ─────────────────────────────────────────────────────────────

    val SCROLLBAR_TRACK: Color = Color(0x181818)
    val SCROLLBAR_THUMB: Color = Color(0x555555)
    val SCROLLBAR_THUMB_HOVER: Color = Color(0x707070)
    val SCROLLBAR_THUMB_PRESSED: Color = Color(0x909090)
    val SCROLLBAR_SIZE: Dimension = Dimension(10, 1)
}
