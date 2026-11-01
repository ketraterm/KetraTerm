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

import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.BasicStroke
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalSearchPainterTest {
    private val metrics = SwingMetrics(7, 11, 8, 9, 5, 0, 1)

    @ParameterizedTest
    @CsvSource("1,1", "1,2", "2,1", "2,2", "2,3", "3,2", "3,3", "7,11")
    fun `outline corners blend once and tiny matches stay inside their cells`(
        width: Int,
        height: Int,
    ) {
        val image = BufferedImage(width * 3, height + 2, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val stroke = BasicStroke(8f)
        g.stroke = stroke
        g.clip = Rectangle(0, 0, image.width, image.height)
        val originalClip = g.clip
        try {
            TerminalSearchPainter(AwtColorCache()).paint(
                g,
                SwingMetrics(width, height, 0, 0, 0, 0, 1),
                0,
                highlights(1, 2),
                SwingSearchMatchStyle(0, border = 0x80ff0000.toInt()),
                SwingSearchMatchStyle(0),
            )
            assertEquals(originalClip, g.clip)
            assertEquals(stroke, g.stroke)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val edge =
                        x in width until width * 2 &&
                            y < height &&
                            (x == width || x == width * 2 - 1 || y == 0 || y == height - 1)
                    assertEquals(if (edge) 0x80ff0000.toInt() else 0, image.getRGB(x, y), "pixel=$x,$y")
                }
            }
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `normal and active segments select independent fills and borders across wrapped rows`() {
        val image = BufferedImage(28, 22, BufferedImage.TYPE_INT_ARGB)
        val projection =
            TerminalSearchViewportHighlights().apply {
                reset(2)
                add(0, 1, 3, false)
                add(1, 0, 1, true)
                finish()
            }
        val painter = TerminalSearchPainter(AwtColorCache())
        val g = image.createGraphics()
        try {
            for (row in 0..1) {
                painter.paint(
                    g,
                    metrics,
                    row,
                    projection,
                    SwingSearchMatchStyle(TEST_BLUE, border = TEST_RED),
                    SwingSearchMatchStyle(TEST_GREEN, border = TEST_WHITE),
                )
            }
        } finally {
            g.dispose()
        }
        assertEquals(TEST_RED, image.getRGB(7, 0))
        assertEquals(TEST_BLUE, image.getRGB(10, 5))
        assertEquals(TEST_WHITE, image.getRGB(0, 11))
        assertEquals(TEST_GREEN, image.getRGB(3, 16))
        assertEquals(0, image.getRGB(0, 0))
        assertEquals(0, image.getRGB(8, 16))
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 2.0])
    fun `scaled outlines respect match bounds and an existing repaint clip`(scale: Double) {
        val image = BufferedImage(70, 45, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.scale(scale, scale)
            g.clip = Rectangle(10, 2, 11, 7)
            val originalClip = g.clip
            TerminalSearchPainter(AwtColorCache()).paint(
                g,
                metrics,
                0,
                highlights(1, 3),
                SwingSearchMatchStyle(0, border = TEST_RED),
                SwingSearchMatchStyle(0),
            )
            assertEquals(originalClip, g.clip)
            var painted = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getRGB(x, y) == 0) continue
                    painted++
                    assertTrue(x >= floor(10 * scale) && x < ceil(21 * scale), "x=$x scale=$scale")
                    assertTrue(y >= floor(2 * scale) && y < ceil(9 * scale), "y=$y scale=$scale")
                }
            }
            assertTrue(painted > 0, "The clipped right edge must remain visible")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `bidi matches decorate their logical cells without crossing unrelated visual cells`() {
        val cache = renderCache(TestRenderFrame.text("a\u05d0\u05d1b\u05d2c"))
        val bidi = TerminalBidiLayout().row(cache, 0)
        val image = BufferedImage(cache.columns * metrics.cellWidth, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            TerminalSearchPainter(AwtColorCache()).paint(
                g,
                metrics,
                0,
                highlights(0, 2),
                SwingSearchMatchStyle(TEST_BLUE, border = TEST_RED),
                SwingSearchMatchStyle(0),
                bidi,
            )
        } finally {
            g.dispose()
        }
        val selected = BooleanArray(cache.columns)
        for (logical in 0..1) selected[bidi?.visualColumn(logical) ?: logical] = true
        for (column in selected.indices) {
            assertEquals(if (selected[column]) TEST_BLUE else 0, image.getRGB(column * 7 + 3, 5))
            assertEquals(if (selected[column]) TEST_RED else 0, image.getRGB(column * 7 + 3, 0))
        }
    }

    private fun highlights(
        start: Int,
        end: Int,
    ): TerminalSearchViewportHighlights =
        TerminalSearchViewportHighlights().apply {
            reset(1)
            add(0, start, end, false)
            finish()
        }
}
