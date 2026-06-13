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
package io.github.jvterm.ui.swing.render

import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.api.TerminalRenderAttrs
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalSwingColorsTest {
    @Test
    fun foregroundAppliesSwingFaintPolicy() {
        val palette = TerminalColorPalette(defaultForeground = 0xFF224466.toInt())
        val attrs = TerminalRenderAttrs.pack(faint = true)

        assertEquals(0xFF112233.toInt(), TerminalSwingColors.foreground(palette, attrs))
    }

    @Test
    fun invisibleForegroundMatchesBackgroundEvenWhenFaint() {
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF224466.toInt(),
                defaultBackground = 0xFF102030.toInt(),
            )
        val attrs = TerminalRenderAttrs.pack(faint = true, invisible = true)

        assertEquals(TerminalSwingColors.background(palette, attrs), TerminalSwingColors.foreground(palette, attrs))
    }
}
