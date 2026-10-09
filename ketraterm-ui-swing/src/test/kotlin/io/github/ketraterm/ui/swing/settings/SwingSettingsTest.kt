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
package io.github.ketraterm.ui.swing.settings

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.render.cache.FontCache
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Canvas
import java.awt.Font
import java.awt.RenderingHints
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingSettingsTest {
    private val chrome = SwingTerminalChrome()

    @Test
    fun `prompt modes copy independently and remove reserved gutter space`() {
        val initial = SwingSettings()
        assertEquals(SwingPromptDecoration.GUTTER, initial.promptDecoration)
        for (mode in listOf(SwingPromptDecoration.NONE, SwingPromptDecoration.DIVIDER)) {
            val settings = initial.copy { it.promptDecoration = mode }
            assertEquals(mode, settings.promptDecoration)
            assertEquals(settings, settings.copy {})
            assertEquals(settings.hashCode(), settings.copy {}.hashCode())
            assertNotEquals(initial, settings)
            assertEquals(0, chrome.promptDecorationGutterWidth(settings, TerminalRenderBufferKind.PRIMARY))
            assertEquals(
                chrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY),
                chrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE),
            )
        }
    }

    @Test
    fun `unavailable gutter removes automatic alternate margin and preserves explicit padding`() {
        val unavailableChrome = SwingTerminalChrome().apply { promptDecorationsAvailable = false }
        val automatic = SwingSettings.create { it.padding = SwingPadding(2, 4, 3, 6) }
        assertEquals(4, unavailableChrome.left(automatic, TerminalRenderBufferKind.PRIMARY))
        assertEquals(0, unavailableChrome.promptDecorationGutterWidth(automatic, TerminalRenderBufferKind.PRIMARY))
        assertEquals(10, unavailableChrome.horizontalInset(automatic, TerminalRenderBufferKind.ALTERNATE))
        val explicit = automatic.copy { it.alternateScreenPadding = SwingPadding(1, 8, 2, 12) }
        assertEquals(8, unavailableChrome.left(explicit, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(12, unavailableChrome.right(explicit, TerminalRenderBufferKind.ALTERNATE))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `alternate grid centering uses actual cells without changing resize insets`(metadataAvailable: Boolean) {
        val chrome = SwingTerminalChrome().apply { promptDecorationsAvailable = metadataAvailable }
        val settings = SwingSettings.create { it.padding = SwingPadding(2, 4, 3, 6) }
        val metrics = SwingMetrics(8, 16, 12, 13, 8, 0, 1)
        val horizontalInset = chrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE)
        assertTrue(chrome.updateLayout(settings, metrics, 107, 73, 10, 3))
        assertEquals(13, chrome.left(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(14, chrome.right(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(12, chrome.top(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(13, chrome.bottom(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(horizontalInset, chrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(5, chrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertFalse(chrome.updateLayout(settings, metrics, 107, 73, 10, 3))
        assertEquals(4 + if (metadataAvailable) 16 else 0, chrome.left(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(2, chrome.top(settings, TerminalRenderBufferKind.PRIMARY))
    }

    @Test
    fun `explicit alternate padding and oversized grids retain bounded clipping margins`() {
        val chrome = SwingTerminalChrome().apply { promptDecorationsAvailable = false }
        val metrics = SwingMetrics(8, 16, 12, 13, 8, 0, 1)
        val automatic = SwingSettings.create { it.padding = SwingPadding(2, 4, 3, 6) }
        chrome.updateLayout(automatic, metrics, 1, 1, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(5, chrome.left(automatic, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(5, chrome.right(automatic, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(2, chrome.top(automatic, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(3, chrome.bottom(automatic, TerminalRenderBufferKind.ALTERNATE))
        val explicit = automatic.copy { it.alternateScreenPadding = SwingPadding(1, 8, 2, 12) }
        chrome.updateLayout(explicit, metrics, 300, 200, 10, 3)
        assertEquals(8, chrome.left(explicit, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(12, chrome.right(explicit, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(1, chrome.top(explicit, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(2, chrome.bottom(explicit, TerminalRenderBufferKind.ALTERNATE))
    }

    @Test
    fun columnSpacingDefaultsToZero() {
        assertEquals(0, SwingSettings().columnSpacing)
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -5, -1, 0, 5, Int.MAX_VALUE])
    fun signedColumnSpacingIsRetainedBySettingsSnapshots(spacing: Int) {
        val original = SwingSettings()
        val builder = original.toBuilder()
        builder.columnSpacing = spacing
        val snapshot = builder.build()
        builder.columnSpacing = 10

        assertEquals(spacing, snapshot.columnSpacing)
        assertEquals(spacing, snapshot.toBuilder().build().columnSpacing)
        assertEquals(snapshot, original.copy { it.columnSpacing = spacing })
        assertEquals(0, original.columnSpacing)
        assertEquals(spacing, snapshot.copy {}.columnSpacing)
        assertEquals(snapshot.hashCode(), snapshot.copy {}.hashCode())
    }

    @Test
    fun spacingUsesOneGeometryAndRejectsOverflowBeforeApplyingSettings() {
        SwingUtilities.invokeAndWait {
            var settings =
                SwingSettings.create {
                    it.columns = 10
                    it.rows = 2
                }
            val component = SwingTerminal(settingsProvider = { settings })
            try {
                val original = component.preferredGridSize(10, 2)
                component.size = original
                val originalFont = component.font
                settings = settings.copy { it.columnSpacing = 3 }
                assertEquals(3, settings.toBuilder().build().columnSpacing)
                component.reloadSettings()
                assertEquals(originalFont, component.font)
                assertEquals(original.width + 30, component.preferredGridSize(10, 2).width)
                assertEquals(original.height, component.preferredGridSize(10, 2).height)
                assertTrue(component.visibleGridSize().width < 10)
                settings = settings.copy { it.columnSpacing = -1 }
                component.reloadSettings()
                assertEquals(originalFont, component.font)
                assertEquals(original.width - 10, component.preferredGridSize(10, 2).width)
                assertEquals(original.height, component.preferredGridSize(10, 2).height)
                assertFailsWith<IllegalArgumentException> { component.preferredGridSize(Int.MAX_VALUE, 1) }
                val condensed = component.preferredGridSize(10, 2)
                settings = settings.copy { it.columnSpacing = Int.MAX_VALUE }
                assertFailsWith<IllegalArgumentException> { component.reloadSettings() }
                assertEquals(condensed, component.preferredGridSize(10, 2))
            } finally {
                component.dispose()
            }
        }
    }

    @Test
    fun spacingDoesNotChangeVerticalMetricsOrCursorStroke() {
        val metrics = Canvas().getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val original = SwingMetrics.from(metrics)
        for (spacing in listOf(-1, 10)) {
            val adjusted = SwingMetrics.from(metrics, columnSpacing = spacing)
            assertEquals(
                original.copy(cellWidth = original.cellWidth + spacing),
                adjusted,
            )
            assertEquals(original.fontCellWidth, adjusted.fontCellWidth)
            assertEquals(
                if (spacing < 0) original.textCellWidth else original.cellWidth + spacing,
                adjusted.textCellWidth,
            )
        }
    }

    @Test
    fun metricsAcceptTheEntirePositiveCellWidthRange() {
        val fontMetrics = Canvas().getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val original = SwingMetrics.from(fontMetrics)

        val minimum = SwingMetrics.from(fontMetrics, columnSpacing = 1 - original.cellWidth)
        val maximum = SwingMetrics.from(fontMetrics, columnSpacing = Int.MAX_VALUE - original.cellWidth)
        assertEquals(original.copy(cellWidth = 1, cursorStrokeWidth = 1), minimum)
        assertEquals(original.fontCellWidth, minimum.fontCellWidth)
        assertEquals(original.textCellWidth, minimum.textCellWidth)
        assertEquals(original.copy(cellWidth = Int.MAX_VALUE), maximum)
        assertEquals(original.fontCellWidth, maximum.fontCellWidth)
        assertEquals(Int.MAX_VALUE, maximum.textCellWidth)
    }

    @Test
    fun condensedLargeFontRetainsGlyphWidthAndFitsTheCursorInsideOnePixelCells() {
        val fontMetrics = Canvas().getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 72))
        val original = SwingMetrics.from(fontMetrics)
        assertTrue(original.cursorStrokeWidth > 1)

        val condensed = SwingMetrics.from(fontMetrics, columnSpacing = 1 - original.cellWidth)

        assertEquals(original.copy(cellWidth = 1, cursorStrokeWidth = 1), condensed)
        assertEquals(original.fontCellWidth, condensed.fontCellWidth)
        assertEquals(original.textCellWidth, condensed.textCellWidth)
        assertEquals(1, condensed.cursorStrokeWidth)
        assertEquals(condensed.cellWidth, condensed.cursorStrokeWidth)
    }

    @Test
    fun metricsRejectZeroNegativeAndOverflowingCellWidths() {
        val fontMetrics = Canvas().getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val original = SwingMetrics.from(fontMetrics)

        for (spacing in listOf(-original.cellWidth, -original.cellWidth - 1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("spacing=$spacing") {
                SwingMetrics.from(fontMetrics, columnSpacing = spacing)
            }
        }
    }

    @Test
    fun interactionSettingsPreserveExistingDefaults() {
        val settings = SwingSettings()
        assertEquals(true, settings.mouseReportingEnabled)
        assertEquals(false, settings.copyOnSelection)
        assertEquals(false, settings.middleClickPaste)
    }

    @Test
    fun builderSnapshotsAreDetachedAndFailedUpdatesLeaveTheOriginalValid() {
        val fonts = mutableListOf(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val builder = SwingSettings.builder()
        builder.fallbackFonts = fonts
        builder.padding = SwingPadding(1, 2, 3, 4)
        builder.shellIntegrationDecorationGutterWidth = 8
        val original = builder.build()
        fonts.clear()
        builder.columns = 120
        assertEquals(80, original.columns)
        assertEquals(1, original.fallbackFonts.size)
        assertEquals(SwingPadding(1, 7, 3, 7), original.alternateScreenPadding)
        assertFailsWith<IllegalArgumentException> { original.copy { it.lineHeight = Float.NaN } }
        assertEquals(1f, original.lineHeight)
        val updated = original.copy { it.lineHeight = 1.25f }
        assertEquals(1.25f, updated.lineHeight)
        assertEquals(original, updated.copy { it.lineHeight = 1f })
        assertEquals(original.hashCode(), original.toBuilder().build().hashCode())
    }

    @ParameterizedTest
    @ValueSource(floats = [Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f, 0f])
    fun invalidLineHeightsAreRejectedBeforeRendering(lineHeight: Float) {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.lineHeight = lineHeight
            }
        }
    }

    @ParameterizedTest
    @ValueSource(floats = [0.5f, 1f, 2f])
    fun finitePositiveLineHeightsAreAccepted(lineHeight: Float) {
        assertEquals(
            lineHeight,
            SwingSettings
                .create { draft ->
                    draft.lineHeight = lineHeight
                }.lineHeight,
        )
    }

    @Test
    fun renderingHintDefaultsAndSupportedAlternativesAreAccepted() {
        val settings = SwingSettings()
        val antialiasingValues =
            listOf(
                RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                RenderingHints.VALUE_TEXT_ANTIALIAS_GASP,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR,
            )
        val fractionalValues =
            listOf(
                RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT,
                RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON,
            )
        for (antialiasing in antialiasingValues) {
            for (fractional in fractionalValues) {
                val copy =
                    settings.copy { draft ->
                        draft.textAntialiasing = antialiasing
                        draft.fractionalMetrics = fractional
                    }
                assertSame(antialiasing, copy.textAntialiasing)
                assertSame(fractional, copy.fractionalMetrics)
            }
        }
    }

    @Test
    fun incompatibleRenderingHintsAreRejectedBeforeRendering() {
        val settings = SwingSettings()
        for (value in listOf("on", 1, Any(), RenderingHints.VALUE_RENDER_SPEED)) {
            assertFailsWith<IllegalArgumentException> {
                settings.copy { draft ->
                    draft.textAntialiasing = value
                }
            }
            assertFailsWith<IllegalArgumentException> {
                settings.copy { draft ->
                    draft.fractionalMetrics = value
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            settings.copy { draft ->
                draft.textAntialiasing = RenderingHints.VALUE_FRACTIONALMETRICS_ON
            }
        }
        assertFailsWith<IllegalArgumentException> {
            settings.copy { draft ->
                draft.fractionalMetrics = RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun unchangedFontSettingsRetainUnsupportedGlyphResolution(fallbackCount: Int) {
        val font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val settings =
            SwingSettings.create { draft ->
                draft.font = font
                draft.fallbackFonts = List(fallbackCount) { font }.toImmutableList()
                draft.useSystemFallbackFonts = false
            }
        val cache = FontCache()
        assertTrue(cache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts))
        val missingGlyphFont = cache.fontForCodePoint(0x10FFFF, Font.PLAIN)

        assertFalse(cache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts))
        assertSame(missingGlyphFont, cache.fontForCodePoint(0x10FFFF, Font.PLAIN))
        assertEquals(26, chrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(4, chrome.verticalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(26, chrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(4, chrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
    }

    @Test
    fun settingsFontSnapshotIsIndependentOfHostList() {
        val font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val fonts = mutableListOf(font)
        val settings =
            SwingSettings.create { draft ->
                draft.fallbackFonts = fonts.toImmutableList()
            }
        val hash = settings.hashCode()
        fonts.clear()

        assertEquals(listOf(font), settings.fallbackFonts)
        assertEquals(settings, settings.copy {})
        assertEquals(hash, settings.copy {}.hashCode())
    }

    @Test
    fun settingsCopySharesImmutableValues() {
        val settings =
            SwingSettings.create { draft ->
                draft.fallbackFonts = persistentListOf(Font(Font.MONOSPACED, Font.PLAIN, 14))
                draft.padding = SwingPadding(1, 2, 3, 4)
                draft.alternateScreenPadding = SwingPadding(5, 6, 7, 8)
            }
        val copy =
            settings.copy { draft ->
                draft.columns = 123
            }
        assertSame(settings.padding, copy.padding)
        assertSame(settings.alternateScreenPadding, copy.alternateScreenPadding)
        assertSame(settings.fallbackFonts, copy.fallbackFonts)
        assertEquals(80, settings.columns)
        assertEquals(123, copy.columns)
    }

    @Test
    fun replacingPaddingAndFontsLeavesOriginalSettingsUnchanged() {
        val originalFont = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val replacementFont = Font(Font.DIALOG, Font.BOLD, 18)
        val settings =
            SwingSettings.create { draft ->
                draft.fallbackFonts = persistentListOf(originalFont)
            }
        val fonts = mutableListOf(replacementFont)
        val copy =
            settings.copy { draft ->
                draft.fallbackFonts = fonts.toImmutableList()
                draft.padding = settings.padding.copy(left = 12)
                draft.alternateScreenPadding = settings.alternateScreenPadding.copy(bottom = 9)
            }
        fonts.clear()

        assertEquals(listOf(originalFont), settings.fallbackFonts)
        assertEquals(listOf(replacementFont), copy.fallbackFonts)
        assertEquals(SwingPadding(0, 4, 4, 6), settings.padding)
        assertEquals(SwingPadding(0, 12, 4, 6), copy.padding)
        assertEquals(SwingPadding(0, 13, 4, 13), settings.alternateScreenPadding)
        assertEquals(SwingPadding(0, 13, 9, 13), copy.alternateScreenPadding)
        assertEquals(settings.font, copy.font)
        assertTrue(settings != copy)
    }

    @Test
    fun settingsRejectInvalidGridSizes() {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.columns = 0
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.rows = 0
            }
        }
    }

    @Test
    fun settingsAllowZeroCursorBlinkToDisableBlinking() {
        val settings =
            SwingSettings.create { draft ->
                draft.cursorBlinkMillis = 0
            }

        assertEquals(0, settings.cursorBlinkMillis)
    }

    @Test
    fun metricsArePositiveForMonospacedFont() {
        val component = Canvas()
        val fontMetrics = component.getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val metrics = SwingMetrics.from(fontMetrics)

        assertTrue(metrics.cellWidth > 0)
        assertTrue(metrics.cellHeight > 0)
        assertTrue(metrics.baseline in 0..metrics.cellHeight)
        assertTrue(metrics.cursorStrokeWidth > 0)
    }

    @Test
    fun metricsScaleCellHeightAndBaselineWithLineHeightMultiplier() {
        val component = Canvas()
        val fontMetrics = component.getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val unscaled = SwingMetrics.from(fontMetrics, 1.0f)
        val scaled = SwingMetrics.from(fontMetrics, 1.5f)

        val expectedHeight = (fontMetrics.height * 1.5f).toInt()
        assertEquals(expectedHeight, scaled.cellHeight)

        val expectedBaselineShift = (scaled.cellHeight - unscaled.cellHeight) / 2
        assertEquals(unscaled.baseline + expectedBaselineShift, scaled.baseline)
        assertEquals(unscaled.cellWidth, scaled.cellWidth)

        // Verify that a very small line height (0.5f) does not crash and baseline is coerced
        val tinyMetrics = SwingMetrics.from(fontMetrics, 0.5f)
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
                        val metrics = SwingMetrics.from(fontMetrics, lh)
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
                val m = SwingMetrics.from(fm, lh)
                println(
                    "  lh=$lh -> cellHeight=${m.cellHeight} baseline=${m.baseline} underlineY=${m.underlineY} strikethroughY=${m.strikethroughY}",
                )
            }
        }
    }

    @Test
    fun settingsDefaultToHighQualityGridSafeTextHints() {
        val settings = SwingSettings()

        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, settings.textAntialiasing)
        assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF, settings.fractionalMetrics)
        assertTrue(settings.fallbackFonts.isNotEmpty())
        assertEquals(true, settings.useSystemFallbackFonts)
        assertEquals(false, settings.treatAmbiguousAsWide)
        assertEquals(0xFF4DA3FF.toInt(), settings.hyperlinkActivationForeground)
        assertEquals(true, settings.visualBellEnabled)
        assertEquals(0x664DA3FF, settings.visualBellColor)
        assertEquals(240, settings.visualBellDurationMillis)
        assertEquals(18, settings.visualBellEdgeThicknessPixels)
        assertEquals(true, settings.shellIntegrationPromptDotsVisible)
        assertEquals(0x8CFFFFFF.toInt(), settings.shellIntegrationPromptDotColor)
        assertEquals(0xFFE74856.toInt(), settings.shellIntegrationFailedPromptDotColor)
        assertEquals(8, settings.shellIntegrationPromptDotDiameter)
        assertEquals(16, settings.shellIntegrationDecorationGutterWidth)
        assertEquals(true, settings.shellIntegrationFailedCommandRailsVisible)
        assertEquals(0xFFE74856.toInt(), settings.shellIntegrationFailedCommandRailColor)
        assertEquals(3, settings.shellIntegrationFailedCommandRailWidth)
        assertEquals(SwingPadding(0, 4, 4, 6), settings.padding)
        assertEquals(SwingPadding(0, 13, 4, 13), settings.alternateScreenPadding)
        assertEquals(0, settings.padding.top)
        assertEquals(4, settings.padding.left)
        assertEquals(4, settings.padding.bottom)
    }

    @Test
    fun settingsRejectInvalidShellIntegrationDecorationDimensions() {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.visualBellDurationMillis = -1
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.visualBellEdgeThicknessPixels = -1
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.shellIntegrationPromptDotDiameter = 0
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.shellIntegrationDecorationGutterWidth = -1
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.shellIntegrationFailedCommandRailWidth = 0
            }
        }
    }

    @Test
    fun settingsAcceptCustomPadding() {
        val settings =
            SwingSettings.create { draft ->
                draft.padding = SwingPadding(4, 8, 4, 8)
                draft.alternateScreenPadding = SwingPadding(1, 2, 3, 4)
            }
        assertEquals(SwingPadding(4, 8, 4, 8), settings.padding)
        assertEquals(SwingPadding(1, 2, 3, 4), settings.alternateScreenPadding)
    }

    @Test
    fun alternateScreenChromeUsesExplicitAlternatePadding() {
        val settings = SwingSettings()

        assertEquals(20, chrome.left(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(6, chrome.right(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(13, chrome.left(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(13, chrome.right(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(26, chrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(26, chrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(4, chrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(16, chrome.promptDecorationGutterWidth(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(0, chrome.promptDecorationGutterWidth(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(true, settings.shellSuggestionsEnabled)
    }

    @Test
    fun alternateScreenChromeDoesNotInheritPrimaryScrollbarGutter() {
        val settings =
            SwingSettings.create { draft ->
                draft.padding = SwingPadding(0, 40, 8, 14)
                draft.alternateScreenPadding = SwingPadding(0, 3, 4, 5)
            }

        assertEquals(56, chrome.left(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(14, chrome.right(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(3, chrome.left(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(5, chrome.right(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(70, chrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(8, chrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(4, chrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
    }

    @Test
    fun settingsRejectNegativePaddingEdges() {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.padding = SwingPadding(0, -1, 0, 0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings.create { draft ->
                draft.alternateScreenPadding = SwingPadding(0, 0, 0, -1)
            }
        }
    }

    @Test
    fun resolveFontFamilyReturnsInstalledFontWithSystemCasing() {
        val monospaced = "monospaced"
        val resolved = SwingSettings.resolveFontFamily(monospaced)
        assertTrue(resolved.equals("Monospaced", ignoreCase = true))
    }

    @Test
    fun resolveFontFamilyFallsBackToPreferredMonospaceForMissingFont() {
        val missingFont = "NonExistentFontFamilyName12345"
        val resolved = SwingSettings.resolveFontFamily(missingFont)
        val preferred = listOf("Cascadia Mono", "Cascadia Code", "Consolas", Font.MONOSPACED)
        assertTrue(
            resolved in preferred ||
                java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .availableFontFamilyNames
                    .contains(resolved),
            "Resolved font '$resolved' must be either one of the preferred defaults or an installed system font",
        )
    }

    @Test
    fun getMonospaceFontFamiliesReturnsOnlyInstalledMonospaceFonts() {
        val monospaceFamilies = SwingSettings.getMonospaceFontFamilies()
        assertTrue(monospaceFamilies.isNotEmpty(), "Monospace font families list must not be empty")

        val installedSet =
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toSet()

        for (family in monospaceFamilies) {
            assertTrue(
                installedSet.contains(family),
                "Resolved monospace font family '$family' must be installed on the system",
            )
        }
    }

    @Test
    fun fallbackPolicyPrefersInstalledColorEmojiBeforeSymbolFonts() {
        val families =
            SwingSettings.fallbackFontFamiliesForInstalledFonts(
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
            SwingSettings.fallbackFontFamiliesForInstalledFonts(
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
        val palette = SwingSettings.defaultPalette()

        assertEquals(0xFFF2F2F2.toInt(), palette.defaultForeground)
        assertEquals(0xFF0C0C0C.toInt(), palette.defaultBackground)
        assertEquals(0xFF0C0C0C.toInt(), palette.indexedColor(0))
        assertEquals(0xFFC50F1F.toInt(), palette.indexedColor(1))
    }

    @Test
    fun terminalThemeIdsRoundTripAndAcceptLegacyNames() {
        for (theme in TerminalTheme.entries) {
            assertEquals(theme, TerminalTheme.fromId(theme.id))
            assertEquals(theme, TerminalTheme.fromId(theme.name))
            assertEquals(theme, TerminalTheme.fromId(theme.name.lowercase()))
        }
        assertEquals("one-dark", TerminalTheme.ONE_DARK.id)
        assertEquals("tokyo-night", TerminalTheme.TOKYO_NIGHT.id)
        assertEquals(null, TerminalTheme.fromId("intellij"))
        assertEquals(null, TerminalTheme.fromId("unknown"))
    }

    @Test
    fun terminalThemesRemainPaletteFactoriesForHosts() {
        for (theme in TerminalTheme.entries) {
            assertTrue(theme.createPalette().isDark)
        }
        val palette = TerminalTheme.ONE_DARK.createPalette()

        assertEquals(0xFFABB2BF.toInt(), palette.defaultForeground)
        assertEquals(0xFF1E2127.toInt(), palette.defaultBackground)
    }

    @Test
    fun componentReportsVisibleGridFromFrozenMetrics() {
        val component =
            SwingTerminal(settingsProvider = {
                SwingSettings.create { draft ->
                    draft.columns = 10
                    draft.rows = 4
                }
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

    @Test
    fun preferredGridSizeIncludesRequestedScreenChrome() {
        SwingUtilities.invokeAndWait {
            val component =
                SwingTerminal(settingsProvider = {
                    SwingSettings.create { draft ->
                        draft.columns = 10
                        draft.rows = 4
                        draft.padding = SwingPadding(3, 5, 7, 11)
                        draft.alternateScreenPadding = SwingPadding(2, 4, 6, 8)
                    }
                })
            val cellWidth = (component.preferredGridSize(2, 1).width - component.preferredGridSize(1, 1).width)
            val cellHeight = (component.preferredGridSize(1, 2).height - component.preferredGridSize(1, 1).height)

            assertEquals(10 * cellWidth + 5 + 16 + 11, component.preferredGridSize(10, 4).width)
            assertEquals(4 * cellHeight + 3 + 7, component.preferredGridSize(10, 4).height)
            assertEquals(10 * cellWidth + 4 + 8, component.preferredGridSize(10, 4, TerminalRenderBufferKind.ALTERNATE).width)
            assertEquals(4 * cellHeight + 2 + 6, component.preferredGridSize(10, 4, TerminalRenderBufferKind.ALTERNATE).height)
            component.dispose()
        }
    }
}
