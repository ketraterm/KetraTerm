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

import io.github.jvterm.render.api.*
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.api.CellSelection
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class TerminalGridPainterTest {
    @Test
    fun `ascii runs paint contiguous measured cells`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = RED,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "ii", cursorVisible = false, palette = settings.palette))

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertTrue(
            image.containsColorInRange(RED, xStart = metrics.cellWidth, xEnd = metrics.cellWidth * 2),
            "second glyph was not painted in the second terminal cell",
        )
    }

    @Test
    fun `ascii run fallback preserves grid cells when measured width differs`() {
        val image = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.SERIF, Font.PLAIN, 18),
                palette =
                    TerminalColorPalette(
                        defaultForeground = RED,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                fractionalMetrics = RenderingHints.VALUE_FRACTIONALMETRICS_ON,
                padding = Insets(0, 0, 0, 0),
            )
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
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "ii", cursorVisible = false, palette = settings.palette))

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertTrue(
            image.containsColorInRange(RED, xStart = 0, xEnd = metrics.cellWidth),
            "first glyph was not painted in the first terminal cell",
        )
        assertTrue(
            image.containsColorInRange(RED, xStart = metrics.cellWidth, xEnd = metrics.cellWidth * 2),
            "fallback glyph vector did not paint the second glyph in the second terminal cell",
        )
    }

    @Test
    fun `block cursor redraws covered glyph with cursor foreground`() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                        cursorForeground = RED,
                        cursorBackground = BLUE,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 1, rows = 1)
        cache.updateFrom(TextFrame(text = "A", cursorVisible = true, palette = settings.palette))

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(BLUE, image.getRGB(1, 1))
        assertTrue(
            image.containsColor(RED, metrics.cellWidth, metrics.cellHeight),
            "cursor foreground glyph was not painted over the block cursor",
        )
    }

    @Test
    fun `complex code point paints with foreground color`() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = RED,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 1, rows = 1)
        cache.updateFrom(TextFrame(text = "\u03A9", cursorVisible = false, palette = settings.palette))

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertTrue(
            image.containsColor(RED, metrics.cellWidth, metrics.cellHeight),
            "complex code point was not painted with the foreground color",
        )
    }

    @Test
    fun `block cursor redraws covered complex code point with cursor foreground`() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                        cursorForeground = GREEN,
                        cursorBackground = BLUE,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 1, rows = 1)
        cache.updateFrom(TextFrame(text = "\u03A9", cursorVisible = true, palette = settings.palette))

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(BLUE, image.getRGB(1, 1))
        assertTrue(
            image.containsColor(GREEN, metrics.cellWidth, metrics.cellHeight),
            "complex code point was not redrawn over the block cursor",
        )
    }

    @Test
    fun `decorations use extra attributes for underline color and overline`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(
            TextFrame(
                text = "AB",
                cursorVisible = false,
                attrs =
                    longArrayOf(
                        TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                        TerminalRenderAttrs.DEFAULT,
                    ),
                extraAttrs =
                    longArrayOf(
                        TerminalRenderExtraAttrs.pack(
                            underlineColorKind = TerminalRenderColorKind.RGB,
                            underlineColorValue = 0x00FF00,
                        ),
                        TerminalRenderExtraAttrs.pack(overline = true),
                    ),
                palette = settings.palette,
            ),
        )

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(GREEN, image.getRGB(1, metrics.underlineY))
        assertEquals(WHITE, image.getRGB(metrics.cellWidth + 1, metrics.overlineY))
    }

    @Test
    fun `extra decoration attributes split ascii runs`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(
            TextFrame(
                text = "AB",
                cursorVisible = false,
                attrs =
                    longArrayOf(
                        TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                        TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                    ),
                extraAttrs =
                    longArrayOf(
                        TerminalRenderExtraAttrs.pack(
                            underlineColorKind = TerminalRenderColorKind.RGB,
                            underlineColorValue = 0x00FF00,
                        ),
                        TerminalRenderExtraAttrs.pack(
                            underlineColorKind = TerminalRenderColorKind.RGB,
                            underlineColorValue = 0x0000FF,
                        ),
                    ),
                palette = settings.palette,
            ),
        )

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(GREEN, image.getRGB(1, metrics.underlineY))
        assertEquals(BLUE, image.getRGB(metrics.cellWidth + 1, metrics.underlineY))
    }

    @Test
    fun `selection background uses configurable Swing color`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                selectionBackground = RED,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = Insets(0, 0, 0, 0),
            )
        val metrics = TerminalSwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "AB", cursorVisible = false, palette = settings.palette))

        TerminalGridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            selection = CellSelection(0, 0, 1, 0),
        )
        g.dispose()

        assertEquals(RED, image.getRGB(1, 1))
        assertEquals(BLACK, image.getRGB(metrics.cellWidth + 1, 1))
    }

    private fun BufferedImage.containsColorInRange(
        argb: Int,
        xStart: Int,
        xEnd: Int,
    ): Boolean {
        var y = 0
        while (y < height) {
            var x = xStart
            while (x < xEnd) {
                if (getRGB(x, y) == argb) return true
                x++
            }
            y++
        }
        return false
    }

    private fun BufferedImage.containsColor(
        argb: Int,
        width: Int,
        height: Int,
    ): Boolean {
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (getRGB(x, y) == argb) return true
                x++
            }
            y++
        }
        return false
    }

    private class TextFrame(
        private val text: String,
        cursorVisible: Boolean,
        private val attrs: LongArray = LongArray(text.length) { TerminalRenderAttrs.DEFAULT },
        private val extraAttrs: LongArray = LongArray(text.length) { TerminalRenderExtraAttrs.DEFAULT },
        override val palette: TerminalColorPalette = TerminalColorPalette(),
    ) : TerminalRenderFrameReader,
        TerminalRenderFrame {
        override val columns: Int = text.length
        override val rows: Int = 1
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = cursorVisible,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(this)
        }

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = text[column].code
                attrWords[attrOffset + column] = attrs[column]
                flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                extraAttrWords?.set(extraAttrOffset + column, extraAttrs[column])
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLUE = 0xFF0000FF.toInt()
    }
}
