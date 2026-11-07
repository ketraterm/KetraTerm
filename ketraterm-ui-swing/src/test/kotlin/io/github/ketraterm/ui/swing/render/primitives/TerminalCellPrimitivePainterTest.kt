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
package io.github.ketraterm.ui.swing.render.primitives

import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TerminalCellPrimitivePainterTest {
    @Test
    fun `geometric square height is stable across fractional device cell widths`() {
        val image = BufferedImage(40, 32, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics =
            SwingMetrics(
                cellWidth = 9,
                cellHeight = 18,
                baseline = 14,
                underlineY = 15,
                strikethroughY = 9,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val painter = TerminalCellPrimitivePainter()

        g.color = Color.RED
        g.scale(1.25, 1.25)
        painter.paint(g, TerminalGeometricShapeGlyphs.BLACK_SQUARE, column = 0, row = 0, metrics = metrics)
        painter.paint(g, TerminalGeometricShapeGlyphs.BLACK_SQUARE, column = 1, row = 0, metrics = metrics)
        g.dispose()

        assertEquals(firstPaintedY(image, 0, 11), firstPaintedY(image, 11, 23))
        assertEquals(lastPaintedY(image, 0, 11), lastPaintedY(image, 11, 23))
    }

    @Test
    fun `primitive painter converts partial logical clip to device clip`() {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 10,
                baseline = 8,
                underlineY = 9,
                strikethroughY = 5,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val painter = TerminalCellPrimitivePainter()

        g.color = Color.RED
        g.scale(2.0, 2.0)
        g.clip = Rectangle(0, 0, 6, 10)
        painter.paint(g, 0x2501, column = 0, row = 0, metrics = metrics)
        g.dispose()

        assertNotEquals(0, image.getRGB(10, 10), "Device pixels inside the scaled clip were incorrectly clipped out")
        assertEquals(0, image.getRGB(12, 10), "Primitive painted outside the scaled clip")
    }

    @Test
    fun `box drawing thickness is stable across fractional device cell widths`() {
        val image = BufferedImage(48, 24, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics =
            SwingMetrics(
                cellWidth = 7,
                cellHeight = 9,
                baseline = 7,
                underlineY = 8,
                strikethroughY = 5,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val painter = TerminalCellPrimitivePainter()

        g.color = Color.RED
        g.scale(1.25, 1.25)
        painter.paint(g, 0x2501, column = 1, row = 0, metrics = metrics)
        painter.paint(g, 0x2501, column = 2, row = 0, metrics = metrics)
        g.dispose()

        assertEquals(firstPaintedY(image, 9, 18), firstPaintedY(image, 18, 26))
        assertEquals(lastPaintedY(image, 9, 18), lastPaintedY(image, 18, 26))
    }

    @Test
    fun `primitive painter clips antialiased strokes to the device cell`() {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics =
            SwingMetrics(
                cellWidth = 20,
                cellHeight = 20,
                baseline = 16,
                underlineY = 18,
                strikethroughY = 10,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val painter = TerminalCellPrimitivePainter()

        g.color = Color.RED
        painter.paint(g, 0x2571, column = 0, row = 0, metrics = metrics)
        g.dispose()

        assertPaintedOnlyInside(image, maxXExclusive = 20, maxYExclusive = 20)
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 1.75, 2.0])
    fun `braille dots keep equal size across adjacent fractional device cells`(scale: Double) {
        val image = BufferedImage(160, 100, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = SwingMetrics(9, 18, 14, 15, 9, 0, 1)
        val painter = TerminalCellPrimitivePainter()
        try {
            g.color = Color.RED
            g.translate(0.4, 0.6)
            g.scale(scale, scale)
            val transform = g.transform
            for (row in 0..1) {
                for (column in 0..3) painter.paint(g, 0x28FF, column, row, metrics)
            }
            assertEquals(transform, g.transform, "Primitive painting must restore the caller's transform")
        } finally {
            g.dispose()
        }
        var firstCellPixels = -1
        var total = 0
        for (row in 0..1) {
            for (column in 0..3) {
                val left = kotlin.math.round(column * 9 * scale + 0.4).toInt()
                val right = kotlin.math.round((column + 1) * 9 * scale + 0.4).toInt()
                val top = kotlin.math.round(row * 18 * scale + 0.6).toInt()
                val bottom = kotlin.math.round((row + 1) * 18 * scale + 0.6).toInt()
                var painted = 0
                for (y in top until bottom) {
                    for (x in left until right) if (image.getRGB(x, y) != 0) painted++
                }
                if (firstCellPixels < 0) firstCellPixels = painted
                assertEquals(firstCellPixels, painted, "Dot weight changed at cell ($column,$row), scale $scale")
                total += painted
            }
        }
        kotlin.test.assertTrue(firstCellPixels >= 8, "Each of the eight dots must remain visible")
        var allPainted = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) if (image.getRGB(x, y) != 0) allPainted++
        }
        assertEquals(total, allPainted, "Braille painted outside its device cells")
    }

    @Test
    fun `braille honors partial clips and restores graphics state`() {
        val image = BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = SwingMetrics(12, 24, 18, 20, 12, 0, 1)
        try {
            g.color = Color.RED
            g.scale(1.25, 1.25)
            g.clip = Rectangle(0, 0, 6, 24)
            val transform = g.transform
            val clip = g.clipBounds
            TerminalCellPrimitivePainter().paint(g, 0x28FF, 0, 0, metrics)
            assertEquals(transform, g.transform)
            assertEquals(clip, g.clipBounds)
        } finally {
            g.dispose()
        }
        var painted = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) != 0) {
                    kotlin.test.assertTrue(x < 8 && y < 30, "Braille escaped the scaled clip at ($x,$y)")
                    painted++
                }
            }
        }
        kotlin.test.assertTrue(painted > 0)
    }

    private fun firstPaintedY(
        image: BufferedImage,
        xStart: Int,
        xEnd: Int,
    ): Int {
        var y = 0
        while (y < image.height) {
            var x = xStart
            while (x < xEnd) {
                if (image.getRGB(x, y) != 0) return y
                x++
            }
            y++
        }
        return -1
    }

    private fun lastPaintedY(
        image: BufferedImage,
        xStart: Int,
        xEnd: Int,
    ): Int {
        var y = image.height - 1
        while (y >= 0) {
            var x = xStart
            while (x < xEnd) {
                if (image.getRGB(x, y) != 0) return y
                x++
            }
            y--
        }
        return -1
    }

    private fun assertPaintedOnlyInside(
        image: BufferedImage,
        maxXExclusive: Int,
        maxYExclusive: Int,
    ) {
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (image.getRGB(x, y) != 0 && (x >= maxXExclusive || y >= maxYExclusive)) {
                    assertEquals(0, image.getRGB(x, y), "Primitive painted outside its device cell at ($x, $y)")
                }
                x++
            }
            y++
        }
    }
}
