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
import io.github.ketraterm.ui.swing.render.cache.TextContrastCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwingColorsTest {
    @Test
    fun `disabled contrast preserves exact truecolor and opaque endpoints have known ratios`() {
        assertEquals(21.0, SwingColors.contrastRatio(0xff000000.toInt(), 0xffffffff.toInt()))
        assertEquals(1.0, SwingColors.contrastRatio(0xff123456.toInt(), 0xff123456.toInt()))
        for (color in intArrayOf(0xff000000.toInt(), 0xff010101.toInt(), 0xff888888.toInt(), 0xffffffff.toInt())) {
            assertEquals(color, SwingColors.ensureContrast(color, color, 1.0))
        }
    }

    @Test
    fun `contrast meets every achievable target across light dark and intermediate backgrounds`() {
        for (level in 0..255) {
            val background = 0xff000000.toInt() or (level * 0x010101)
            val possible =
                maxOf(SwingColors.contrastRatio(0xff000000.toInt(), background), SwingColors.contrastRatio(0xffffffff.toInt(), background))
            for (ratio in doubleArrayOf(1.1, 3.0, 4.5, 7.0, 21.0)) {
                for (foreground in intArrayOf(background, 0xff112233.toInt(), 0xffaa5500.toInt())) {
                    val adjusted = SwingColors.ensureContrast(foreground, background, ratio)
                    val actual = SwingColors.contrastRatio(adjusted, background)
                    assertTrue(actual + 1e-12 >= minOf(possible, ratio), "bg=$level target=$ratio achieved=$actual possible=$possible")
                    if (SwingColors.contrastRatio(foreground, background) >= ratio) assertEquals(foreground, adjusted)
                }
            }
        }
    }

    @Test
    fun `faint intensity blends toward displayed background and can be disabled`() {
        val palette = TerminalColorPalette(defaultForeground = 0xff808080.toInt(), defaultBackground = 0xffffffff.toInt())
        val attr = TerminalRenderAttrs.pack(faint = true)
        assertEquals(0xffffffff.toInt(), SwingColors.foreground(palette, attr, faintOpacity = 0.0, minimumContrastRatio = 1.0))
        assertEquals(0xffbfbfbf.toInt(), SwingColors.foreground(palette, attr, faintOpacity = 0.5, minimumContrastRatio = 1.0))
        assertEquals(0xff808080.toInt(), SwingColors.foreground(palette, attr, faintOpacity = 1.0, minimumContrastRatio = 1.0))
        assertEquals(0xff404040.toInt(), SwingColors.foreground(palette, attr, background = 0xff000000.toInt(), minimumContrastRatio = 1.0))
    }

    @Test
    fun `contrast cache isolates renderers and invalidates on ratio changes`() {
        val first = TextContrastCache()
        val second = TextContrastCache()
        val gray = 0xff777777.toInt()
        first.configure(4.5)
        val adjusted = first.resolve(gray, gray)
        repeat(10) { assertEquals(adjusted, first.resolve(gray, gray)) }
        assertEquals(gray, second.resolve(gray, gray))
        first.configure(1.0)
        assertEquals(gray, first.resolve(gray, gray))
        first.configure(7.0)
        assertEquals(SwingColors.ensureContrast(gray, gray, 7.0), first.resolve(gray, gray))
        assertEquals(SwingColors.ensureContrast(gray, 0xffffffff.toInt(), 7.0), first.resolve(gray, 0xffffffff.toInt()))
    }

    @Test
    fun foregroundAppliesSwingFaintPolicy() {
        // Faint text blends toward its background, including on light themes.
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF224466.toInt(),
                defaultBackground = 0xFFFFFFFF.toInt(),
            )
        val attrs = TerminalRenderAttrs.pack(faint = true)

        assertEquals(0xFF90A1B2.toInt(), SwingColors.foreground(palette, attrs, minimumContrastRatio = 1.0))
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
        val adjusted = SwingColors.foreground(paletteLowContrast, attrs, minimumContrastRatio = 3.0)
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
        val adjusted = SwingColors.foreground(palette, attrs, minimumContrastRatio = 3.0)
        assertNotEquals(0xFFFFFF00.toInt(), adjusted)
        assertTrue(SwingColors.contrastRatio(adjusted, 0xFFFFFFFF.toInt()) >= 3.0)
    }

    @Test
    fun powerlinePreservesSegmentColorsWithContrastAdjustmentEnabled() {
        val foreground = 0xFF220000.toInt()
        val palette = TerminalColorPalette(defaultForeground = foreground, defaultBackground = 0xFF000000.toInt())
        for (codePoint in 0xE0B0..0xE0BF) {
            assertEquals(
                foreground,
                SwingColors.foreground(
                    palette,
                    TerminalRenderAttrs.pack(),
                    codePoint,
                    minimumContrastRatio = 3.0,
                ),
            )
            assertEquals(
                SwingColors.blend(foreground, palette.defaultBackground, 0.5),
                SwingColors.foreground(
                    palette,
                    TerminalRenderAttrs.pack(faint = true),
                    codePoint,
                    minimumContrastRatio = 3.0,
                    faintOpacity = 0.5,
                ),
            )
        }
    }

    @Test
    fun mosaicsPreserveColorsWithContrastAdjustmentEnabled() {
        val foreground = 0xFF220000.toInt()
        val palette = TerminalColorPalette(defaultForeground = foreground, defaultBackground = 0xFF000000.toInt())
        for (codePoint in (0x1FB00..0x1FB3B) + (0x1CD00..0x1CDE5)) {
            assertEquals(foreground, SwingColors.foreground(palette, TerminalRenderAttrs.pack(), codePoint, minimumContrastRatio = 3.0))
            assertEquals(
                SwingColors.blend(foreground, palette.defaultBackground, 0.5),
                SwingColors.foreground(palette, TerminalRenderAttrs.pack(faint = true), codePoint, minimumContrastRatio = 3.0),
            )
        }
    }

    @Test
    fun foregroundBypassesContrastEnforcementForGraphicGlyphs() {
        val attrs = TerminalRenderAttrs.pack()

        // Very dark red on black has low contrast, normally adjusted
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF220000.toInt(), // Very dark red
                defaultBackground = 0xFF000000.toInt(), // Black
            )

        // Standard lookup / default codePoint should be adjusted
        val adjustedNormal = SwingColors.foreground(palette, attrs, codePoint = 0, minimumContrastRatio = 3.0)
        assertNotEquals(0xFF220000.toInt(), adjustedNormal)

        // Box drawing character (e.g. U+2500) must bypass adjustment and keep original color
        val boxDrawingColor = SwingColors.foreground(palette, attrs, codePoint = 0x2500)
        assertEquals(0xFF220000.toInt(), boxDrawingColor)

        // Block element character (e.g. U+2588) must bypass adjustment and keep original color
        val blockElementColor = SwingColors.foreground(palette, attrs, codePoint = 0x2588)
        assertEquals(0xFF220000.toInt(), blockElementColor)

        // Geometric shape character (e.g. U+25A0) must bypass adjustment and keep original color
        val geometricShapeColor = SwingColors.foreground(palette, attrs, codePoint = 0x25A0)
        assertEquals(0xFF220000.toInt(), geometricShapeColor)

        // Braille pattern character (e.g. U+2812) must bypass adjustment and keep original color
        val braillePatternColor = SwingColors.foreground(palette, attrs, codePoint = 0x2812)
        assertEquals(0xFF220000.toInt(), braillePatternColor)
    }
}
