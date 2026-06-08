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
package com.gagik.terminal.ui.swing.settings

import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import java.awt.Canvas
import java.awt.Font
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
    fun settingsDefaultToHighQualityGridSafeTextHints() {
        val settings = TerminalSwingSettings()

        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, settings.textAntialiasing)
        assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF, settings.fractionalMetrics)
        assertTrue(settings.fallbackFonts.isNotEmpty())
        assertEquals(false, settings.useSystemFallbackFonts)
        assertEquals(false, settings.treatAmbiguousAsWide)
        assertEquals(0xFF4DA3FF.toInt(), settings.hyperlinkActivationForeground)
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
    fun settingsDefaultPaletteOwnsSwingThemeColors() {
        val palette = TerminalSwingSettings.defaultPalette()

        assertEquals(0xFFF2F2F2.toInt(), palette.defaultForeground)
        assertEquals(0xFF0C0C0C.toInt(), palette.defaultBackground)
        assertEquals(0xFF0C0C0C.toInt(), palette.indexedColor(0))
        assertEquals(0xFFC50F1F.toInt(), palette.indexedColor(1))
    }

    @Test
    fun componentReportsVisibleGridFromFrozenMetrics() {
        val component =
            TerminalSwingTerminal {
                TerminalSwingSettings(columns = 10, rows = 4)
            }
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
