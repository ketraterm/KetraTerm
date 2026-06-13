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
import io.github.jvterm.ui.swing.render.*
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertEquals

class TerminalBackgroundPainterTest {
    @Nested
    inner class Clear {
        @Test
        fun `fills whole component with default background`() {
            val image = BufferedImage(12, 10, BufferedImage.TYPE_INT_ARGB)
            val palette = defaultTestSettings(background = TEST_BLUE).palette

            TerminalBackgroundPainter(AwtColorCache()).clear(image.createGraphics(), palette, image.width, image.height)

            assertEquals(TEST_BLUE, image.getRGB(0, 0))
            assertEquals(TEST_BLUE, image.getRGB(11, 9))
        }
    }

    @Nested
    inner class Rows {
        @Test
        fun `paints default and explicit background cells`() {
            val image = BufferedImage(50, 20, BufferedImage.TYPE_INT_ARGB)
            val settings = defaultTestSettings(background = TEST_BLACK)
            val metrics = testMetrics(image, settings)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(attr = TerminalRenderAttrs.DEFAULT),
                                TestCell(
                                    attr =
                                        TerminalRenderAttrs.pack(
                                            backgroundKind = TerminalRenderColorKind.RGB,
                                            backgroundValue = 0x0000FF,
                                        ),
                                ),
                            ),
                        ),
                    ),
                )

            TerminalBackgroundPainter(AwtColorCache()).paintRow(
                g = image.createGraphics(),
                cache = cache,
                palette = settings.palette,
                metrics = metrics,
                row = 0,
            )

            assertEquals(TEST_BLACK, image.getRGB(1, 1))
            assertEquals(TEST_BLUE, image.getRGB(metrics.cellWidth + 1, 1))
        }

        @Test
        fun `inverse swaps background to foreground color`() {
            val image = BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB)
            val settings = defaultTestSettings(foreground = TEST_GREEN, background = TEST_BLACK)
            val metrics = testMetrics(image, settings)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(TestCell(attr = TerminalRenderAttrs.pack(inverse = true))),
                        ),
                    ),
                )

            TerminalBackgroundPainter(AwtColorCache()).paintRow(
                g = image.createGraphics(),
                cache = cache,
                palette = settings.palette,
                metrics = metrics,
                row = 0,
            )

            assertEquals(TEST_GREEN, image.getRGB(1, 1))
        }
    }
}
