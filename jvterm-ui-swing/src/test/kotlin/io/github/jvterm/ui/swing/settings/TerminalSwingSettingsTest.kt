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
package io.github.jvterm.ui.swing.settings

import io.github.jvterm.ui.swing.api.TerminalSwingTerminal
import java.awt.Canvas
import java.awt.Font
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalSwingSettingsTest {
    @Test
    fun settingsRejectInvalidGridSizes() {
        assertFailsWith<IllegalArgumentException> {
            TerminalSwingSettings(columns = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalSwingSettings(rows = 0)
        }
    }

    @Test
    fun settingsAllowZeroCursorBlinkToDisableBlinking() {
        val settings = TerminalSwingSettings(cursorBlinkMillis = 0)

        assertEquals(0, settings.cursorBlinkMillis)
    }

    @Test
    fun metricsArePositiveForMonospacedFont() {
        val component = Canvas()
        val fontMetrics = component.getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val metrics = TerminalSwingMetrics.from(fontMetrics)

        assertTrue(metrics.cellWidth > 0)
        assertTrue(metrics.cellHeight > 0)
        assertTrue(metrics.baseline in 0..metrics.cellHeight)
        assertTrue(metrics.cursorStrokeWidth > 0)
    }

    @Test
    fun metricsScaleCellHeightAndBaselineWithLineHeightMultiplier() {
        val component = Canvas()
        val fontMetrics = component.getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val unscaled = TerminalSwingMetrics.from(fontMetrics, 1.0f)
        val scaled = TerminalSwingMetrics.from(fontMetrics, 1.5f)

        val expectedHeight = (fontMetrics.height * 1.5f).toInt()
        assertEquals(expectedHeight, scaled.cellHeight)

        val expectedBaselineShift = (scaled.cellHeight - unscaled.cellHeight) / 2
        assertEquals(unscaled.baseline + expectedBaselineShift, scaled.baseline)
        assertEquals(unscaled.cellWidth, scaled.cellWidth)

        // Verify that a very small line height (0.5f) does not crash and baseline is coerced
        val tinyMetrics = TerminalSwingMetrics.from(fontMetrics, 0.5f)
        assertTrue(tinyMetrics.cellHeight > 0)
        assertTrue(tinyMetrics.baseline in 0..tinyMetrics.cellHeight)
    }

    @Test
    fun metricsDoNotCrashForVariousFontsSizesAndLineHeights() {
        val component = Canvas()
        val families = arrayOf(Font.MONOSPACED, Font.SERIF, Font.SANS_SERIF, "Cascadia Mono", "Courier New", "Arial")
        val sizes = intArrayOf(8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 48, 72)
        val lineHeights = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f)

        for (family in families) {
            for (size in sizes) {
                val font = Font(family, Font.PLAIN, size)
                val fontMetrics = component.getFontMetrics(font)
                for (lh in lineHeights) {
                    try {
                        val metrics = TerminalSwingMetrics.from(fontMetrics, lh)
                        assertTrue(metrics.cellWidth > 0, "Width <= 0 for $family $size lh=$lh")
                        assertTrue(metrics.cellHeight > 0, "Height <= 0 for $family $size lh=$lh")
                        assertTrue(metrics.baseline in 0..metrics.cellHeight, "Baseline $metrics out of bounds for $family $size lh=$lh")
                    } catch (e: Exception) {
                        kotlin.test.fail("Failed for $family $size lh=$lh: ${e.message}")
                    }
                }
            }
        }
    }

    @Test
    fun printMetricsForDebugging() {
        val component = Canvas()
        for (family in listOf("Cascadia Mono", Font.MONOSPACED)) {
            val font = Font(family, Font.PLAIN, 16)
            val fm = component.getFontMetrics(font)
            println("=== FONT: $family 16 ===")
            println("  height=${fm.height} ascent=${fm.ascent} descent=${fm.descent} leading=${fm.leading}")
            for (lh in listOf(0.5f, 0.6f, 1.0f)) {
                val m = TerminalSwingMetrics.from(fm, lh)
                println(
                    "  lh=$lh -> cellHeight=${m.cellHeight} baseline=${m.baseline} underlineY=${m.underlineY} strikethroughY=${m.strikethroughY}",
                )
            }
        }
    }

    @Test
    fun settingsDefaultToHighQualityGridSafeTextHints() {
        val settings = TerminalSwingSettings()

        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, settings.textAntialiasing)
        assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF, settings.fractionalMetrics)
        assertTrue(settings.fallbackFonts.isNotEmpty())
        assertEquals(false, settings.useSystemFallbackFonts)
        assertEquals(false, settings.treatAmbiguousAsWide)
        assertEquals(0xFF4DA3FF.toInt(), settings.hyperlinkActivationForeground)
        assertEquals(Insets(12, 12, 12, 12), settings.padding)
    }

    @Test
    fun settingsAcceptCustomPadding() {
        val settings = TerminalSwingSettings(padding = Insets(4, 8, 4, 8))
        assertEquals(Insets(4, 8, 4, 8), settings.padding)
    }

    @Test
    fun fallbackPolicyPrefersInstalledColorEmojiBeforeSymbolFonts() {
        val families =
            TerminalSwingSettings.fallbackFontFamiliesForInstalledFonts(
                arrayOf(
                    "Segoe UI Symbol",
                    "Dialog",
                    "Segoe UI Emoji",
                    "SansSerif",
                ),
            )

        assertTrue(families.indexOf("Segoe UI Emoji") < families.indexOf(Font.DIALOG))
        assertTrue(families.indexOf("Segoe UI Emoji") < families.indexOf("Segoe UI Symbol"))
    }

    @Test
    fun fallbackPolicyIncludesInstalledIndicKhmerAndSinhalaFonts() {
        val families =
            TerminalSwingSettings.fallbackFontFamiliesForInstalledFonts(
                arrayOf(
                    "Nirmala UI",
                    "Noto Sans Devanagari",
                    "Noto Sans Bengali",
                    "Noto Sans Tamil",
                    "Noto Sans Khmer",
                    "Noto Sans Sinhala",
                    "Khmer UI",
                    "Iskoola Pota",
                ),
            )

        assertTrue("Nirmala UI" in families)
        assertTrue("Noto Sans Devanagari" in families)
        assertTrue("Noto Sans Bengali" in families)
        assertTrue("Noto Sans Tamil" in families)
        assertTrue("Noto Sans Khmer" in families)
        assertTrue("Noto Sans Sinhala" in families)
        assertTrue("Khmer UI" in families)
        assertTrue("Iskoola Pota" in families)
    }

    @Test
    fun settingsDefaultPaletteOwnsSwingThemeColors() {
        val palette = TerminalSwingSettings.defaultPalette()

        assertEquals(0xFFF2F2F2.toInt(), palette.defaultForeground)
        assertEquals(0xFF0C0C0C.toInt(), palette.defaultBackground)
        assertEquals(0xFF0C0C0C.toInt(), palette.indexedColor(0))
        assertEquals(0xFFC50F1F.toInt(), palette.indexedColor(1))
    }

    @Test
    fun terminalThemesRemainPaletteFactoriesForHosts() {
        val palette = TerminalTheme.ONE_DARK.createPalette()

        assertEquals(0xFFABB2BF.toInt(), palette.defaultForeground)
        assertEquals(0xFF1E2127.toInt(), palette.defaultBackground)
    }

    @Test
    fun componentReportsVisibleGridFromFrozenMetrics() {
        val component =
            TerminalSwingTerminal(settingsProvider = {
                TerminalSwingSettings(columns = 10, rows = 4)
            })
        val preferred = component.preferredSize
        var visibleColumns = 0
        var visibleRows = 0

        SwingUtilities.invokeAndWait {
            component.size = preferred
            val visible = component.visibleGridSize()
            visibleColumns = visible.width
            visibleRows = visible.height
        }

        assertEquals(10, visibleColumns)
        assertEquals(4, visibleRows)
    }
}
