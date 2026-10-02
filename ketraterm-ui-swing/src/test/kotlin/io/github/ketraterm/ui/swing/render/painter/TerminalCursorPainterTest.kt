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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderCursor
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalCursorPainterTest {
    @Nested
    inner class Visibility {
        @Test
        fun `does not paint invisible cursor`() {
            val fixture = fixture(cursor(visible = false))

            fixture.paint()

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `does not paint blinking cursor when blink is hidden`() {
            val fixture = fixture(cursor(blinking = true))

            fixture.paint(cursorBlinkVisible = false)

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `does not paint application hidden cursor when unfocused`() {
            val fixture = fixture(cursor(visible = false))

            fixture.paint(terminalFocused = false)

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `ignores cursor outside cache bounds`() {
            val fixture = fixture(cursor(column = 2))

            fixture.paint()

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }
    }

    @Nested
    inner class Unfocused {
        @ParameterizedTest
        @EnumSource(TerminalRenderCursorShape::class)
        fun `inactive shapes are steady with hollow blocks and unchanged bars or underlines`(shape: TerminalRenderCursorShape) {
            val fixture = fixture(cursor(shape = shape, blinking = true))
            try {
                for (blinkVisible in listOf(false, true)) {
                    fixture.paint(terminalFocused = false, cursorBlinkVisible = blinkVisible)
                    val width = fixture.metrics.cellWidth
                    val height = fixture.metrics.cellHeight
                    for (y in 0 until fixture.image.height) {
                        for (x in 0 until fixture.image.width) {
                            val cursorPixel =
                                x < width &&
                                    y < height &&
                                    when (shape) {
                                        TerminalRenderCursorShape.BLOCK -> x == 0 || x == width - 1 || y == 0 || y == height - 1
                                        TerminalRenderCursorShape.BAR -> x < fixture.metrics.cursorStrokeWidth
                                        TerminalRenderCursorShape.UNDERLINE -> y >= height - fixture.metrics.cursorStrokeWidth
                                    }
                            assertEquals(if (cursorPixel) TEST_BLUE else TEST_BLACK, fixture.image.getRGB(x, y), "Pixel ($x, $y)")
                        }
                    }
                    assertEquals(shape, fixture.cache.cursorShape)
                    assertTrue(fixture.cache.cursorBlinking)
                }
            } finally {
                fixture.g.dispose()
            }
        }

        @ParameterizedTest
        @ValueSource(ints = [0, 1])
        fun `wide leader and continuation share the same hollow outline`(column: Int) {
            val fixture = wideFixture(cursor(column = column))
            try {
                fixture.paint(terminalFocused = false)
                assertOutline(fixture, fixture.metrics, fixture.metrics.cellWidth * 2)
            } finally {
                fixture.g.dispose()
            }
        }

        @Test
        fun `outline preserves underlying content and stays within its cell`() {
            val fixture = fixture(cursor())
            try {
                fixture.g.color = java.awt.Color(TEST_GREEN, true)
                fixture.g.fillRect(0, 0, fixture.image.width, fixture.image.height)
                fixture.paint(terminalFocused = false)
                assertOutline(fixture, fixture.metrics, fixture.metrics.cellWidth, interior = TEST_GREEN)
            } finally {
                fixture.g.dispose()
            }
        }

        @ParameterizedTest
        @ValueSource(ints = [1, 2, 3, 8])
        fun `outline stays thin independently of bar thickness and cell size`(size: Int) {
            val fixture = fixture(cursor())
            val metrics = fixture.metrics.copy(cellWidth = size, cellHeight = size, baseline = size, cursorStrokeWidth = 4)
            try {
                fixture.paint(terminalFocused = false, metrics = metrics)
                assertOutline(fixture, metrics, size)
            } finally {
                fixture.g.dispose()
            }
        }

        @Test
        fun `unfocused cursor outside cache bounds is not painted`() {
            val fixture = fixture(cursor(row = -1))
            try {
                fixture.paint(terminalFocused = false)
                assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.image.width, fixture.image.height))
            } finally {
                fixture.g.dispose()
            }
        }

        @ParameterizedTest
        @ValueSource(doubles = [1.0, 1.25, 1.5, 1.75, 2.0])
        fun `outline has uniform borders within its cell at fractional display scales`(scale: Double) {
            val original = fixture(cursor())
            original.g.dispose()
            val image = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            val fixture = original.copy(image = image, g = graphics)
            val metrics = fixture.metrics.copy(cellWidth = 10, cellHeight = 20, baseline = 14, cursorStrokeWidth = 4)
            val previousStroke = java.awt.BasicStroke(3f)
            try {
                graphics.scale(scale, scale)
                graphics.translate(4.0, 4.0)
                graphics.stroke = previousStroke
                fixture.paint(terminalFocused = false, metrics = metrics)

                assertSame(previousStroke, graphics.stroke)
                val middleX = (9 * scale).toInt()
                val middleY = (14 * scale).toInt()
                val horizontal = (0 until image.width).filter { image.getRGB(it, middleY) == TEST_BLUE }
                val vertical = (0 until image.height).filter { image.getRGB(middleX, it) == TEST_BLUE }
                assertTrue(horizontal.isNotEmpty())
                assertTrue(vertical.isNotEmpty())
                val left = horizontal.takeWhile { it < middleX }.size
                val right = horizontal.count { it > middleX }
                val top = vertical.takeWhile { it < middleY }.size
                val bottom = vertical.count { it > middleY }
                assertEquals(left, right, "Left/right border thickness at scale $scale")
                assertEquals(top, bottom, "Top/bottom border thickness at scale $scale")
                assertEquals(left, top, "Horizontal/vertical border thickness at scale $scale")
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        if (image.getRGB(x, y) == TEST_BLUE) {
                            assertTrue(x >= floor(4 * scale) && x < ceil(14 * scale), "Outline crossed horizontal bounds at ($x, $y)")
                            assertTrue(y >= floor(4 * scale) && y < ceil(24 * scale), "Outline crossed vertical bounds at ($x, $y)")
                        }
                    }
                }
            } finally {
                graphics.dispose()
            }
        }

        private fun assertOutline(
            fixture: Fixture,
            metrics: SwingMetrics,
            width: Int,
            interior: Int = TEST_BLACK,
            stroke: Int = 1,
        ) {
            for (y in 0 until fixture.image.height) {
                for (x in 0 until fixture.image.width) {
                    val border =
                        x < width &&
                            y < metrics.cellHeight &&
                            (x < stroke || x >= width - stroke || y < stroke || y >= metrics.cellHeight - stroke)
                    assertEquals(if (border) TEST_BLUE else interior, fixture.image.getRGB(x, y), "Pixel ($x, $y)")
                }
            }
        }
    }

    @Nested
    inner class Shapes {
        @ParameterizedTest
        @EnumSource(value = TerminalRenderCursorShape::class, names = ["BAR", "UNDERLINE"])
        fun `line cursors preserve their configured thickness and cell placement at native scale`(shape: TerminalRenderCursorShape) {
            val fixture = fixture(cursor(shape = shape, blinking = false))
            try {
                for (strokeWidth in 1..3) {
                    fixture.g.color = java.awt.Color(TEST_BLACK, true)
                    fixture.g.fillRect(0, 0, fixture.image.width, fixture.image.height)
                    val metrics = fixture.metrics.copy(cellWidth = 10, cellHeight = 20, baseline = 14, cursorStrokeWidth = strokeWidth)
                    fixture.paint(metrics = metrics)
                    for (y in 0 until fixture.image.height) {
                        for (x in 0 until fixture.image.width) {
                            val cursorPixel =
                                x < metrics.cellWidth &&
                                    y < metrics.cellHeight &&
                                    if (shape == TerminalRenderCursorShape.BAR) x < strokeWidth else y >= metrics.cellHeight - strokeWidth
                            assertEquals(
                                if (cursorPixel) TEST_BLUE else TEST_BLACK,
                                fixture.image.getRGB(x, y),
                                "$shape, stroke $strokeWidth at ($x, $y)",
                            )
                        }
                    }
                }
            } finally {
                fixture.g.dispose()
            }
        }

        @ParameterizedTest
        @EnumSource(value = TerminalRenderCursorShape::class, names = ["BAR", "UNDERLINE"])
        fun `cursor thickness is consistent across pane offsets and focus states`(shape: TerminalRenderCursorShape) {
            val original = fixture(cursor(shape = shape, blinking = false))
            original.g.dispose()
            for (scale in listOf(1.0, 1.25, 1.5, 1.75, 2.0)) {
                for (strokeWidth in 1..3) {
                    val expectedThickness = (strokeWidth * scale).roundToInt()
                    for (focused in listOf(false, true)) {
                        for (offset in 0..7) {
                            val image = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
                            val graphics = image.createGraphics()
                            val fixture = original.copy(image = image, g = graphics)
                            val metrics =
                                fixture.metrics.copy(
                                    cellWidth = 10,
                                    cellHeight = 20,
                                    baseline = 14,
                                    cursorStrokeWidth = strokeWidth,
                                )
                            val origin = 4 + offset
                            try {
                                graphics.scale(scale, scale)
                                graphics.translate(origin.toDouble(), origin.toDouble())
                                val previousStroke = graphics.stroke
                                fixture.paint(terminalFocused = focused, metrics = metrics)
                                assertSame(previousStroke, graphics.stroke)
                                val thickness =
                                    when (shape) {
                                        TerminalRenderCursorShape.BAR -> {
                                            val middleY = ((origin + 10) * scale).toInt()
                                            (0 until image.width).count { image.getRGB(it, middleY) == TEST_BLUE }
                                        }
                                        TerminalRenderCursorShape.UNDERLINE -> {
                                            val middleX = ((origin + 5) * scale).toInt()
                                            (0 until image.height).count { image.getRGB(middleX, it) == TEST_BLUE }
                                        }
                                        TerminalRenderCursorShape.BLOCK -> error("Only line cursors are tested")
                                    }
                                assertEquals(
                                    expectedThickness,
                                    thickness,
                                    "$shape at scale $scale, stroke $strokeWidth, offset $offset, focus $focused",
                                )
                                for (y in 0 until image.height) {
                                    for (x in 0 until image.width) {
                                        if (image.getRGB(x, y) == TEST_BLUE) {
                                            assertTrue(
                                                x >= floor(origin * scale) && x < ceil((origin + 10) * scale),
                                                "$shape crossed horizontal cell bounds at ($x, $y), scale $scale, stroke $strokeWidth, offset $offset",
                                            )
                                            assertTrue(
                                                y >= floor(origin * scale) && y < ceil((origin + 20) * scale),
                                                "$shape crossed vertical cell bounds at ($x, $y), scale $scale, stroke $strokeWidth, offset $offset",
                                            )
                                        }
                                    }
                                }
                            } finally {
                                graphics.dispose()
                            }
                        }
                    }
                }
            }
        }

        @ParameterizedTest
        @ValueSource(ints = [0x41, 0xE9, 0x2588])
        fun `concealed block cursor paints only its background in either blink phase`(codePoint: Int) {
            for (textBlinkVisible in listOf(false, true)) {
                val fixture = fixture(cursor(shape = TerminalRenderCursorShape.BLOCK))
                fixture.cache.codeWords[0] = codePoint
                fixture.cache.attrWords[0] = TerminalRenderAttrs.pack(invisible = true, blink = true, inverse = true)

                try {
                    fixture.paint(textBlinkVisible = textBlinkVisible)

                    for (y in 0 until fixture.metrics.cellHeight) {
                        for (x in 0 until fixture.metrics.cellWidth) {
                            assertEquals(TEST_BLUE, fixture.image.getRGB(x, y), "Concealed cursor glyph at ($x, $y)")
                        }
                    }
                    assertEquals(TEST_BLACK, fixture.image.getRGB(fixture.metrics.cellWidth, 0))
                } finally {
                    fixture.g.dispose()
                }
            }
        }

        @Test
        fun `block cursor fills full cell and redraws foreground`() {
            val fixture = fixture(cursor(shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, 1))
            assertTrue(fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `block cursor over wide leader fills both cells`() {
            val fixture = wideFixture(cursor(column = 0, shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, 1))
            assertEquals(TEST_BLUE, fixture.image.getRGB(fixture.metrics.cellWidth + 1, 1))
        }

        @Test
        fun `block cursor over wide trailing spacer fills owner pair`() {
            val fixture = wideFixture(cursor(column = 1, shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, 1))
            assertEquals(TEST_BLUE, fixture.image.getRGB(fixture.metrics.cellWidth + 1, 1))
        }

        @Test
        fun `block cursor redraws wide emoji foreground across both cells`() {
            val fixture = wideEmojiFixture(cursor(column = 0, shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertTrue(
                fixture.image.containsColorInRange(
                    TEST_RED,
                    xStart = fixture.metrics.cellWidth,
                    xEnd = fixture.metrics.cellWidth * 2,
                ),
            )
        }

        @Test
        fun `underline cursor fills bottom stroke only`() {
            val fixture = fixture(cursor(shape = TerminalRenderCursorShape.UNDERLINE))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, fixture.metrics.cellHeight - 1))
            assertEquals(TEST_BLACK, fixture.image.getRGB(1, 1))
        }

        @Test
        fun `underline cursor over wide leader spans both cells`() {
            val fixture = wideFixture(cursor(column = 0, shape = TerminalRenderCursorShape.UNDERLINE))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, fixture.metrics.cellHeight - 1))
            assertEquals(TEST_BLUE, fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.cellHeight - 1))
            assertEquals(TEST_BLACK, fixture.image.getRGB(fixture.metrics.cellWidth + 1, 1))
        }

        @Test
        fun `bar cursor fills leading stroke`() {
            val fixture = fixture(cursor(shape = TerminalRenderCursorShape.BAR))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(0, 1))
            assertEquals(TEST_BLACK, fixture.image.getRGB(fixture.metrics.cellWidth - 1, 1))
        }
    }

    private data class Fixture(
        val image: BufferedImage,
        val g: Graphics2D,
        val settings: SwingSettings,
        val metrics: SwingMetrics,
        val cache: TerminalRenderCache,
        val painter: TerminalCursorPainter,
        val textPainter: TerminalTextPainter,
    ) {
        fun paint(
            cursorBlinkVisible: Boolean = true,
            textBlinkVisible: Boolean = true,
            terminalFocused: Boolean = true,
            metrics: SwingMetrics = this.metrics,
        ) {
            textPainter.updateSettings(settings)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            painter.paint(
                g,
                cache,
                settings.palette,
                metrics,
                cursorBlinkVisible,
                textBlinkVisible,
                g.fontRenderContext,
                terminalFocused = terminalFocused,
            )
        }
    }

    private fun fixture(cursor: TerminalRenderCursor): Fixture {
        val image = BufferedImage(50, 30, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = TEST_WHITE, background = TEST_BLACK)
        val metrics = testMetrics(image, settings)
        val colorCache = AwtColorCache()
        val textPainter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))
        val cache = renderCache(copyWithCursor(cursor))
        return Fixture(
            image = image,
            g =
                image.createGraphics().also {
                    it.color = colorCache.color(TEST_BLACK)
                    it.fillRect(0, 0, image.width, image.height)
                },
            settings = settings,
            metrics = metrics,
            cache = cache,
            painter = TerminalCursorPainter(colorCache, textPainter),
            textPainter = textPainter,
        )
    }

    private fun wideFixture(cursor: TerminalRenderCursor): Fixture {
        val image = BufferedImage(50, 30, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = TEST_WHITE, background = TEST_BLACK)
        val metrics = testMetrics(image, settings)
        val colorCache = AwtColorCache()
        val textPainter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))
        val frame =
            TestRenderFrame(
                cells =
                    arrayOf(
                        arrayOf(
                            TestCell(
                                codeWord = 0x4F60,
                                flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                            ),
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                        ),
                    ),
                cursorValue = cursor,
            )
        val cache = renderCache(frame)
        return Fixture(
            image = image,
            g =
                image.createGraphics().also {
                    it.color = colorCache.color(TEST_BLACK)
                    it.fillRect(0, 0, image.width, image.height)
                },
            settings = settings,
            metrics = metrics,
            cache = cache,
            painter = TerminalCursorPainter(colorCache, textPainter),
            textPainter = textPainter,
        )
    }

    private fun wideEmojiFixture(cursor: TerminalRenderCursor): Fixture {
        val metrics =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 14,
                underlineY = 15,
                strikethroughY = 8,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val image = BufferedImage(metrics.cellWidth * 3, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = TEST_WHITE, background = TEST_BLACK)
        val colorCache = AwtColorCache()
        val textPainter =
            TerminalTextPainter(
                colorCache = colorCache,
                decorationPainter = TerminalDecorationPainter(colorCache),
                platformEmojiPainter = TerminalPlatformEmojiPainter(FakeEmojiRasterizer),
            )
        val frame =
            TestRenderFrame(
                cells =
                    arrayOf(
                        arrayOf(
                            TestCell(
                                codeWord = 0x1F600,
                                flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                            ),
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                        ),
                    ),
                cursorValue = cursor,
            )
        val cache = renderCache(frame)
        return Fixture(
            image = image,
            g =
                image.createGraphics().also {
                    it.color = colorCache.color(TEST_BLACK)
                    it.fillRect(0, 0, image.width, image.height)
                },
            settings = settings,
            metrics = metrics,
            cache = cache,
            painter = TerminalCursorPainter(colorCache, textPainter),
            textPainter = textPainter,
        )
    }

    private object FakeEmojiRasterizer : TerminalPlatformEmojiRasterizer {
        override val available: Boolean = true

        override fun rasterize(
            text: String,
            pixelSize: Int,
        ): BufferedImage {
            val image = BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB)
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    image.setRGB(x, y, TEST_RED)
                    x++
                }
                y++
            }
            return image
        }
    }

    private fun cursor(
        column: Int = 0,
        row: Int = 0,
        visible: Boolean = true,
        blinking: Boolean = false,
        shape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK,
    ): TerminalRenderCursor =
        TerminalRenderCursor(
            column = column,
            row = row,
            visible = visible,
            blinking = blinking,
            shape = shape,
            generation = 1,
        )

    private fun copyWithCursor(cursor: TerminalRenderCursor): TestRenderFrame =
        TestRenderFrame(
            cells = arrayOf(arrayOf(TestCell(codeWord = 'A'.code, flags = TerminalRenderCellFlags.CODEPOINT))),
            cursorValue = cursor,
        )
}
