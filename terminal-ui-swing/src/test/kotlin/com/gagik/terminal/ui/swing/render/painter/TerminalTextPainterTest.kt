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
package com.gagik.terminal.ui.swing.render.painter

import com.gagik.terminal.render.api.*
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.render.*
import com.gagik.terminal.ui.swing.render.cache.AwtColorCache
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the text rendering pipeline, ensuring that fast-path ASCII runs,
 * complex-shaped text, and wide-cell grapheme clusters are painted accurately
 * according to the terminal's rigid column grid.
 */
class TerminalTextPainterTest {
    @Nested
    inner class AsciiTextRendering {
        @Test
        fun `paints contiguous ascii run in consecutive terminal cells`() {
            // Arrange
            val fixture = fixture()
            val cache = renderCache(TestRenderFrame.text("ii"))

            // Act
            fixture.paintRow(cache)

            // Assert
            assertTrue(
                fixture.image.containsColorInRange(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
                "The second glyph 'i' was not painted in the second terminal column",
            )
        }

        /**
         * "Mismatch path" refers to the fallback triggered when Java2D's natural
         * font advances do not align perfectly with the terminal's rigid cell width
         * (often caused by fractional metrics or kerning).
         * The painter must abandon `drawChars` and use positioned `GlyphVectors`.
         */
        @Test
        fun `ascii mismatch path maintains absolute origin for positioned glyphs`() {
            // Arrange
            val settings = createMismatchSettings()
            val (image, metrics, painter) = createMismatchFixture(settings)
            val g = image.createGraphics()
            val cache = renderCache(TestRenderFrame.text("ii"))

            // Act
            painter.paintRow(g, cache, settings.palette, metrics, row = 0, fontRenderContext = g.fontRenderContext)
            g.dispose()

            // Assert
            assertTrue(image.containsColorInRange(TEST_RED, 0, metrics.cellWidth), "First positioned glyph missing")
            assertTrue(image.containsColorInRange(TEST_RED, metrics.cellWidth, metrics.cellWidth * 2), "Second positioned glyph missing")
        }

        @Test
        fun `ascii mismatch path leaves graphics transform completely unchanged`() {
            // Arrange
            val settings = createMismatchSettings()
            val (image, metrics, painter) = createMismatchFixture(settings)
            val g = image.createGraphics()

            // Introduce a deliberate transform to verify the painter cleans up after itself
            val initialTransform = AffineTransform.getTranslateInstance(3.0, 5.0)
            g.transform = initialTransform
            val cache = renderCache(TestRenderFrame.text("ii"))

            // Act
            painter.paintRow(g, cache, settings.palette, metrics, row = 0, fontRenderContext = g.fontRenderContext)

            // Assert
            assertEquals(initialTransform, g.transform, "Painter modified the AffineTransform without restoring it")
            g.dispose()
        }

        @Test
        fun `ascii mismatch path does not dynamically rescale prefixes when runs grow`() {
            // Arrange
            val settings = createMismatchSettings()

            // Act
            val shortImage = paintSerifAscii(settings, "Wi")
            val longImage = paintSerifAscii(settings, "Wii")
            val metrics = testMetrics(shortImage, settings)

            // Assert
            // The pixels rendered for 'W' in the first cell must remain identical
            // regardless of the string length that follows it.
            assertEquals(
                shortImage.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                longImage.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                "Prefix glyph was warped/rescaled due to sub-pixel layout changes in a longer string",
            )
        }

        @Test
        fun `ascii mismatch path correctly spaces out compact kerning runs`() {
            // Arrange
            val settings = createMismatchSettings()

            // Act
            val singleCell = paintSerifAscii(settings, "i")
            val twoCells = paintSerifAscii(settings, "ii")
            val metrics = testMetrics(singleCell, settings)

            // Assert
            // 'i' is a narrow character. If the pipeline falls back to default Java2D string drawing
            // instead of our strict grid glyph-vector positioning, the two 'i's will squish together.
            assertEquals(
                singleCell.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                twoCells.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                "The first 'i' bled into the second cell, or the run was not spaced to the terminal grid",
            )
        }

        @Test
        fun `ascii runs are split correctly when text decorations change`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE)

            // Frame with same text and foreground, but different underline colors
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "AB",
                        attrs =
                            longArrayOf(
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                            ),
                        extraAttrs =
                            longArrayOf(
                                TerminalRenderExtraAttrs.pack(
                                    underlineColorKind = TerminalRenderColorKind.RGB,
                                    underlineColorValue = 0x00FF00, // Green underline
                                ),
                                TerminalRenderExtraAttrs.pack(
                                    underlineColorKind = TerminalRenderColorKind.RGB,
                                    underlineColorValue = 0x0000FF, // Blue underline
                                ),
                            ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(TEST_GREEN, fixture.image.getRGB(1, fixture.metrics.underlineY), "First cell underline should be green")
            assertEquals(
                TEST_BLUE,
                fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.underlineY),
                "Second cell underline should be blue",
            )
        }
    }

    @Nested
    inner class ComplexTextRendering {
        @Test
        fun `paints non-ascii unicode code point using layout cache`() {
            // Arrange
            val fixture = fixture()
            val cache = renderCache(TestRenderFrame.text("\u03A9")) // Greek Omega

            // Act
            fixture.paintRow(cache)

            // Assert
            assertTrue(
                fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Non-ascii codepoint failed to render",
            )
        }

        @Test
        fun `paints astral unicode scalar as one terminal cell`() {
            // Arrange
            val fixture = fixture(width = 80)
            val text = String(Character.toChars(ASTRAL_SMILE_CODE_POINT))
            val cache = renderCache(TestRenderFrame.text(text))

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(1, cache.columns)
            assertEquals(ASTRAL_SMILE_CODE_POINT, cache.codeWords[0])
            val isLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
            if (!isLinux) {
                assertTrue(
                    fixture.image.containsPaintedPixelInRange(0, fixture.metrics.cellWidth, 0, fixture.metrics.cellHeight),
                    "Astral-plane scalar failed to render",
                )
            }
        }

        @Test
        fun `paints grapheme cluster sourced from cluster sink`() {
            // Arrange
            val fixture = fixture()
            // Thai cluster (base consonant + combining mark)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    flags = TerminalRenderCellFlags.CLUSTER,
                                    attr = TerminalRenderAttrs.DEFAULT,
                                    cluster = "\u0E01\u0E34",
                                ),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertTrue(
                fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Grapheme cluster failed to render",
            )
        }

        @Test
        fun `paints astral grapheme cluster sourced from primitive cluster data`() {
            // Arrange
            val fixture = fixture(width = 80)
            val cluster = String(Character.toChars(ASTRAL_SMILE_CODE_POINT)) + "\uFE0F"
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    flags = TerminalRenderCellFlags.CLUSTER,
                                    attr = TerminalRenderAttrs.DEFAULT,
                                    cluster = cluster,
                                ),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(cluster, cache.clusterText(row = 0, column = 0))
            val isLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
            if (!isLinux) {
                assertTrue(
                    fixture.image.containsPaintedPixelInRange(0, fixture.metrics.cellWidth, 0, fixture.metrics.cellHeight),
                    "Astral grapheme cluster failed to render",
                )
            }
        }

        @Test
        fun `wide complex cell layout and decorations span two columns`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE, width = 80)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = 0x4E2D, // CJK 'Middle' character
                                    flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                                    attr = TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                                ),
                                TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            // Ensure the underline (or glyph itself) painted successfully into the trailing column's X bounds
            assertTrue(
                fixture.image.containsColorInRange(TEST_WHITE, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
                "Wide cell did not paint into its trailing column",
            )
        }

        @Test
        fun `single-width complex glyph is clipped to its core cell span`() {
            // Arrange
            val fixture = fixture(width = 40)
            val narrowMetrics =
                TerminalSwingMetrics(
                    cellWidth = 4,
                    cellHeight = fixture.metrics.cellHeight,
                    baseline = fixture.metrics.baseline,
                    underlineY = fixture.metrics.underlineY,
                    strikethroughY = fixture.metrics.strikethroughY,
                    overlineY = fixture.metrics.overlineY,
                    cursorStrokeWidth = fixture.metrics.cursorStrokeWidth,
                )
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = 0x221E,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = TerminalRenderAttrs.DEFAULT,
                                ),
                                TestCell(),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.painter.paintRow(
                fixture.g,
                cache,
                fixture.settings.palette,
                narrowMetrics,
                row = 0,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertTrue(
                !fixture.image.containsPaintedPixelInRange(narrowMetrics.cellWidth, narrowMetrics.cellWidth * 2),
                "Complex glyph escaped the single-cell span owned by core",
            )
        }

        @Test
        fun `symbol heavy narrow row does not paint beyond its core columns`() {
            // Arrange
            val text = "∀∂∈ℝ∧∪≡∞ ↑↗↨↻⇣ ┐┼╔╘░►☺♀ ﬁ�⑀₂ἠḂӥẄɐː⍎אԱა"
            val cellWidth = 4
            val fixture = fixture(width = text.codePointCount(0, text.length) * cellWidth + cellWidth)
            val narrowMetrics =
                TerminalSwingMetrics(
                    cellWidth = cellWidth,
                    cellHeight = fixture.metrics.cellHeight,
                    baseline = fixture.metrics.baseline,
                    underlineY = fixture.metrics.underlineY,
                    strikethroughY = fixture.metrics.strikethroughY,
                    overlineY = fixture.metrics.overlineY,
                    cursorStrokeWidth = fixture.metrics.cursorStrokeWidth,
                )
            val cache = renderCache(TestRenderFrame.text(text))

            // Act
            fixture.painter.paintRow(
                fixture.g,
                cache,
                fixture.settings.palette,
                narrowMetrics,
                row = 0,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            val textEnd = text.codePointCount(0, text.length) * cellWidth
            assertTrue(
                !fixture.image.containsPaintedPixelInRange(textEnd, textEnd + cellWidth),
                "Narrow symbol row painted past the core-owned terminal columns",
            )
        }

        @Test
        fun `fitted complex glyph path restores graphics transform`() {
            // Arrange
            val fixture = fixture(width = 40)
            val initialTransform = AffineTransform.getTranslateInstance(3.0, 5.0)
            fixture.g.transform = initialTransform
            val narrowMetrics =
                TerminalSwingMetrics(
                    cellWidth = 4,
                    cellHeight = fixture.metrics.cellHeight,
                    baseline = fixture.metrics.baseline,
                    underlineY = fixture.metrics.underlineY,
                    strikethroughY = fixture.metrics.strikethroughY,
                    overlineY = fixture.metrics.overlineY,
                    cursorStrokeWidth = fixture.metrics.cursorStrokeWidth,
                )
            val cache = renderCache(TestRenderFrame.text("\u221E"))

            // Act
            fixture.painter.paintRow(
                fixture.g,
                cache,
                fixture.settings.palette,
                narrowMetrics,
                row = 0,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertEquals(initialTransform, fixture.g.transform, "Complex glyph fitting leaked a Graphics2D transform")
        }
    }

    @Nested
    inner class CursorForegroundRendering {
        @Test
        fun `paints ascii cell inverted with supplied cursor foreground`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE) // Default text is white
            val cache = renderCache(TestRenderFrame.text("A"))

            // Act
            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN, // Override to Green for the cursor block
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertTrue(
                fixture.image.containsColor(TEST_GREEN, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Cursor foreground did not override cell text color",
            )
        }

        @Test
        fun `empty cell does not accidentally paint foreground debris`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE)
            val cache = renderCache(TestRenderFrame(arrayOf(arrayOf(TestCell())))) // Empty cell

            // Act
            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertTrue(
                !fixture.image.containsColor(TEST_GREEN, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Empty cell painted ghost text when targeted by block cursor",
            )
        }

        @Test
        fun `paintCellForeground restores rectangular clip bounds`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE)
            val cache = renderCache(TestRenderFrame.text("A"))
            val originalClip = Rectangle(3, 4, fixture.metrics.cellWidth, fixture.metrics.cellHeight)
            fixture.g.setClip(originalClip.x, originalClip.y, originalClip.width, originalClip.height)

            // Act
            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertEquals(originalClip, fixture.g.getClipBounds(Rectangle()))
        }
    }

    // --- Testing Utilities & Helpers ---

    private data class Fixture(
        val image: BufferedImage,
        val g: Graphics2D,
        val settings: TerminalSwingSettings,
        val metrics: TerminalSwingMetrics,
        val painter: TerminalTextPainter,
    ) {
        fun paintRow(
            cache: TerminalRenderCache,
            row: Int = 0,
        ) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
            painter.paintRow(g, cache, settings.palette, metrics, row = row, fontRenderContext = g.fontRenderContext)
        }
    }

    private fun fixture(
        foreground: Int = TEST_RED,
        background: Int = TEST_BLACK,
        width: Int = 80,
    ): Fixture {
        val image = BufferedImage(width, 40, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = foreground, background = background)
        val colorCache = AwtColorCache()
        val painter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))
        painter.updateSettings(settings)
        return Fixture(
            image = image,
            g = image.createGraphics(),
            settings = settings,
            metrics = testMetrics(image, settings),
            painter = painter,
        )
    }

    private fun createMismatchSettings(): TerminalSwingSettings =
        TerminalSwingSettings(
            font = Font(Font.SERIF, Font.PLAIN, 18),
            palette = defaultTestSettings(foreground = TEST_RED, background = TEST_BLACK).palette,
            textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
            fractionalMetrics = RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )

    private fun createMismatchFixture(settings: TerminalSwingSettings): Triple<BufferedImage, TerminalSwingMetrics, TerminalTextPainter> {
        val image = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val fontMetrics = g.getFontMetrics(settings.font)
        val metrics =
            TerminalSwingMetrics(
                cellWidth = maxOf(1, fontMetrics.charWidth('W')),
                cellHeight = fontMetrics.height,
                baseline = fontMetrics.ascent,
                underlineY = minOf(fontMetrics.height - 1, fontMetrics.ascent + 1),
                strikethroughY = maxOf(0, fontMetrics.ascent - fontMetrics.ascent / 3),
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val painter = TerminalTextPainter(AwtColorCache(), TerminalDecorationPainter(AwtColorCache()))
        painter.updateSettings(settings)

        // Push graphics hints to ensure GlyphVector mismatch path triggers
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)

        g.dispose()
        return Triple(image, metrics, painter)
    }

    private fun paintSerifAscii(
        settings: TerminalSwingSettings,
        text: String,
    ): BufferedImage {
        val image = BufferedImage(140, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val colorCache = AwtColorCache()
        val painter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))

        painter.updateSettings(settings)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)

        painter.paintRow(
            g,
            renderCache(TestRenderFrame.text(text)),
            settings.palette,
            testMetrics(image, settings),
            row = 0,
            fontRenderContext = g.fontRenderContext,
        )
        g.dispose()
        return image
    }

    private companion object {
        private const val ASTRAL_SMILE_CODE_POINT = 0x1F642
    }
}
