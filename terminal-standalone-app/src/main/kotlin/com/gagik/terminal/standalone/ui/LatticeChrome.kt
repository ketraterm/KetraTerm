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
    val SURFACE: Color = Color(0x15181D)
    val BORDER: Color = Color(0x2A3038)
    val SCROLLBAR_TRACK: Color = Color(0x1B1F25)
    val SCROLLBAR_THUMB: Color = Color(0x6E7681)
    val SCROLLBAR_SIZE: Dimension = Dimension(12, 1)
}
