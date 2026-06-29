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
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.assertEquals

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
}
