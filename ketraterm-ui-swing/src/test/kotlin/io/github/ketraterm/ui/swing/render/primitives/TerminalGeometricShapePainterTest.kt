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

import io.github.ketraterm.ui.swing.render.primitives.TerminalGeometricShapeGlyphs.BLACK_SQUARE
import io.github.ketraterm.ui.swing.render.primitives.TerminalGeometricShapeGlyphs.WHITE_SQUARE
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalGeometricShapePainterTest {
    @Test
    fun `black square fills the cell width when terminal cells are taller than wide`() {
        val (image, g, painter) = fixture(width = 10, height = 18)

        painter.paint(g, BLACK_SQUARE, 0, 0, image.width, image.height)
        g.dispose()

        val centerY = image.height / 2
        assertTrue(isPainted(image, 0, centerY))
        assertTrue(isPainted(image, image.width - 1, centerY))
        assertFalse(isPainted(image, image.width / 2, 0))
        assertFalse(isPainted(image, image.width / 2, image.height - 1))
    }

    @Test
    fun `black square keeps stable height when rounded device cell widths differ`() {
        val image = BufferedImage(21, 18, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.RED
        val painter = TerminalGeometricShapePainter()

        painter.paint(g, BLACK_SQUARE, 0, 0, 10, image.height, nominalCellWidth = 10)
        painter.paint(g, BLACK_SQUARE, 10, 0, 11, image.height, nominalCellWidth = 10)
        g.dispose()

        assertEquals(4, firstPaintedY(image, 0, 10))
        assertEquals(13, lastPaintedY(image, 0, 10))
        assertEquals(4, firstPaintedY(image, 10, 21))
        assertEquals(13, lastPaintedY(image, 10, 21))
        assertTrue(isPainted(image, 9, image.height / 2))
        assertTrue(isPainted(image, 10, image.height / 2))
        assertTrue(isPainted(image, 20, image.height / 2))
    }

    @Test
    fun `white square paints an outline without filling the center`() {
        val (image, g, painter) = fixture(width = 16, height = 16)

        painter.paint(g, WHITE_SQUARE, 0, 0, image.width, image.height)
        g.dispose()

        assertTrue(isPainted(image, 0, 0))
        assertTrue(isPainted(image, image.width - 1, 0))
        assertTrue(isPainted(image, 0, image.height - 1))
        assertTrue(isPainted(image, image.width - 1, image.height - 1))
        assertEquals(0, image.getRGB(image.width / 2, image.height / 2))
    }

    private fun fixture(
        width: Int,
        height: Int,
    ): Triple<BufferedImage, Graphics2D, TerminalGeometricShapePainter> {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.RED
        return Triple(image, g, TerminalGeometricShapePainter())
    }

    private fun isPainted(
        image: BufferedImage,
        x: Int,
        y: Int,
    ): Boolean = image.getRGB(x, y) != 0

    private fun firstPaintedY(
        image: BufferedImage,
        xStart: Int,
        xEnd: Int,
    ): Int {
        var y = 0
        while (y < image.height) {
            var x = xStart
            while (x < xEnd) {
                if (isPainted(image, x, y)) return y
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
                if (isPainted(image, x, y)) return y
                x++
            }
            y--
        }
        return -1
    }
}
