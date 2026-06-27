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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwingColorsTest {
    @Test
    fun foregroundAppliesSwingFaintPolicy() {
        // Use a white background so the faint color 0xFF112233 has high contrast and is not adjusted
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF224466.toInt(),
                defaultBackground = 0xFFFFFFFF.toInt(),
            )
        val attrs = TerminalRenderAttrs.pack(faint = true)

        assertEquals(0xFF112233.toInt(), SwingColors.foreground(palette, attrs))
    }

    @Test
    fun invisibleForegroundMatchesBackgroundEvenWhenFaint() {
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF224466.toInt(),
                defaultBackground = 0xFF102030.toInt(),
            )
        val attrs = TerminalRenderAttrs.pack(faint = true, invisible = true)

        assertEquals(SwingColors.background(palette, attrs), SwingColors.foreground(palette, attrs))
    }

    @Test
    fun foregroundEnforcesMinimumContrastOnDarkBackground() {
        val attrs = TerminalRenderAttrs.pack()

        // Red on black has contrast ~ 5.6 (no adjustment)
        val paletteHighContrast =
            TerminalColorPalette(
                defaultForeground = 0xFFFF0000.toInt(), // Red
                defaultBackground = 0xFF000000.toInt(), // Black
            )
        assertEquals(0xFFFF0000.toInt(), SwingColors.foreground(paletteHighContrast, attrs))

        // Very dark red on black has low contrast, must be adjusted to be lighter
        val paletteLowContrast =
            TerminalColorPalette(
                defaultForeground = 0xFF220000.toInt(), // Very dark red
                defaultBackground = 0xFF000000.toInt(), // Black
            )
        val adjusted = SwingColors.foreground(paletteLowContrast, attrs)
        assertNotEquals(0xFF220000.toInt(), adjusted)
        assertTrue(SwingColors.contrastRatio(adjusted, 0xFF000000.toInt()) >= 3.0)
    }

    @Test
    fun foregroundEnforcesMinimumContrastOnLightBackground() {
        val attrs = TerminalRenderAttrs.pack()

        // Yellow on white has contrast ~ 1.07, must be adjusted to be darker
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFFFFFF00.toInt(), // Yellow
                defaultBackground = 0xFFFFFFFF.toInt(), // White
            )
        val adjusted = SwingColors.foreground(palette, attrs)
        assertNotEquals(0xFFFFFF00.toInt(), adjusted)
        assertTrue(SwingColors.contrastRatio(adjusted, 0xFFFFFFFF.toInt()) >= 3.0)
    }
}
