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
 */
internal object LatticeChrome {
    const val APP_TITLE = "Lattice"
    val SURFACE: Color = Color(0x0F1011)
    val TOP_BAR_BACKGROUND: Color = Color(0x202020)
    val TAB_BAR_BACKGROUND: Color = Color(0x202020)
    val TAB_SELECTED: Color = Color(0x0C0C0C)
    val TAB_HOVER: Color = Color(0x323232)
    val TERMINAL_BACKGROUND: Color = Color(0x0C0C0C)
    val POPUP_BACKGROUND: Color = Color(0x2B2B2B)
    val CONTROL_BACKGROUND: Color = Color(0x343434)
    val CONTROL_HOVER: Color = Color(0x424242)
    val CONTROL_PRESSED: Color = Color(0x4D4D4D)
    val TITLE_FOREGROUND: Color = Color(0xF2F2F2)
    val TEXT_MUTED: Color = Color(0xA7A7A7)
    val ACCENT: Color = Color(0x4DA3FF)
    val BORDER: Color = Color(0x303030)
    val SCROLLBAR_TRACK: Color = Color(0x0C0C0C)
    val SCROLLBAR_THUMB: Color = Color(0x5F5F5F)
    val SCROLLBAR_THUMB_HOVER: Color = Color(0x777777)
    val SCROLLBAR_THUMB_PRESSED: Color = Color(0x949494)
    val SCROLLBAR_SIZE: Dimension = Dimension(12, 1)
}
