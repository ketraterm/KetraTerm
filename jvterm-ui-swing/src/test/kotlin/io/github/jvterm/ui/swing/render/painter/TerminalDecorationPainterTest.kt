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
package io.github.jvterm.ui.swing.render.painter

import io.github.jvterm.render.api.TerminalRenderAttrs
import io.github.jvterm.render.api.TerminalRenderColorKind
import io.github.jvterm.render.api.TerminalRenderExtraAttrs
import io.github.jvterm.render.api.TerminalRenderUnderline
import io.github.jvterm.ui.swing.render.*
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalDecorationPainterTest {
    @Nested
    inner class Underline {
        @Test
        fun `single underline uses foreground by default`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }

        @Test
        fun `underline color can come from extra attributes`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                TerminalRenderExtraAttrs.pack(
                    underlineColorKind = TerminalRenderColorKind.RGB,
                    underlineColorValue = 0x00FF00,
                ),
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_GREEN, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }

        @Test
        fun `double underline paints two rows`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.DOUBLE),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(1, minOf(fixture.metrics.cellHeight - 1, fixture.metrics.underlineY + 2)))
        }
    }

    @Nested
    inner class OtherDecorations {
        @Test
        fun `strikethrough paints at strikethrough metric`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(strikethrough = true),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.strikethroughY))
        }

        @Test
        fun `overline paints at overline metric`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.DEFAULT,
                TerminalRenderExtraAttrs.pack(overline = true),
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.overlineY))
        }

        @Test
        fun `wide spans paint through every covered cell`() {
            val fixture = fixture(width = 80)

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 2,
                row = 0,
                metrics = fixture.metrics,
            )

            assertTrue(
                fixture.image.containsColorInRange(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
            )
        }
    }

    @Nested
    inner class HyperlinkDecorations {
        @Test
        fun `hyperlink underline is dotted when not hovered`() {
            val fixture = fixture()

            fixture.painter.paintHyperlink(
                fixture.g,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
                hovered = false,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertTrue(fixture.image.getRGB(1, fixture.metrics.underlineY) != TEST_RED)
        }

        @Test
        fun `hyperlink underline is solid when hovered`() {
            val fixture = fixture()

            fixture.painter.paintHyperlink(
                fixture.g,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
                hovered = true,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }
    }

    private data class Fixture(
        val image: BufferedImage,
        val g: Graphics2D,
        val settings: TerminalSwingSettings,
        val metrics: TerminalSwingMetrics,
        val painter: TerminalDecorationPainter,
    )

    private fun fixture(width: Int = 40): Fixture {
        val image = BufferedImage(width, 30, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings()
        return Fixture(
            image = image,
            g = image.createGraphics(),
            settings = settings,
            metrics = testMetrics(image, settings),
            painter = TerminalDecorationPainter(AwtColorCache()),
        )
    }
}
