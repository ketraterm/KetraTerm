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
package io.github.jvterm.ui.swing.render.primitives

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalBlockElementPainterTest {
    // Use an even 16x16 grid so 1/8th fractional blocks divide perfectly without rounding errors
    private val width = 16
    private val height = 16

    @Test
    fun `full block paints entire bounding box`() {
        val (image, g, painter) = fixture()
        painter.paint(g, 0x2588, 0, 0, width, height)
        g.dispose()

        assertEquals(width * height, countPainted(image), "Full block did not fill the cell completely")
    }

    @Test
    fun `upper half block paints exactly top half`() {
        val (image, g, painter) = fixture()
        painter.paint(g, 0x2580, 0, 0, width, height)
        g.dispose()

        assertEquals((width * height) / 2, countPainted(image))
        // Verify specifically it's the top half
        assertTrue(isPainted(image, x = width / 2, y = 0))
        assertTrue(!isPainted(image, x = width / 2, y = height - 1))
    }

    @Test
    fun `fractional lower blocks increment progressively`() {
        val (image1, g1, painter1) = fixture()
        val (image2, g2, painter2) = fixture()

        painter1.paint(g1, 0x2581, 0, 0, width, height) // 1/8th
        painter2.paint(g2, 0x2582, 0, 0, width, height) // 1/4th (2/8ths)

        val count1 = countPainted(image1)
        val count2 = countPainted(image2)

        assertTrue(count1 > 0)
        assertEquals(count1 * 2, count2, "2/8th block should be exactly twice the area of 1/8th block")

        g1.dispose()
        g2.dispose()
    }

    @Test
    fun `quadrants paint explicit corners independently`() {
        val (image, g, painter) = fixture()
        painter.paint(g, 0x259A, 0, 0, width, height) // Upper-Left and Lower-Right
        g.dispose()

        assertTrue(isPainted(image, 0, 0), "Upper-Left missing")
        assertTrue(isPainted(image, width - 1, height - 1), "Lower-Right missing")
        assertTrue(!isPainted(image, width - 1, 0), "Upper-Right should be empty")
        assertTrue(!isPainted(image, 0, height - 1), "Lower-Left should be empty")
    }

    private fun fixture(): Triple<BufferedImage, Graphics2D, TerminalBlockElementPainter> {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.RED
        return Triple(image, g, TerminalBlockElementPainter())
    }

    private fun countPainted(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (image.getRGB(x, y) != 0) count++
            }
        }
        return count
    }

    private fun isPainted(
        image: BufferedImage,
        x: Int,
        y: Int,
    ): Boolean = image.getRGB(x, y) != 0
}
