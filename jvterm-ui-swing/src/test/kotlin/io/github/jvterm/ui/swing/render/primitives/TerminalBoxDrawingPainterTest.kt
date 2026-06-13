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

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.assertTrue

class TerminalBoxDrawingPainterTest {
    private val width = 20
    private val height = 20
    private val cx = width / 2
    private val cy = height / 2

    @Nested
    inner class ContinuousSpans {
        @Test
        fun `horizontal heavy line terminates exactly on boundaries`() {
            val (image, g, painter) = fixture()
            painter.paint(g, 0x2501, 0, 0, width, height) // Heavy horizontal

            // A heavy line spans the whole width, but does NOT touch top/bottom bounds
            assertTrue(isPainted(image, 0, cy))
            assertTrue(isPainted(image, width - 1, cy))
            assertTrue(!isPainted(image, cx, 0))
            assertTrue(!isPainted(image, cx, height - 1))
            g.dispose()
        }

        @Test
        fun `half-span elbow connects center to edge without overshooting`() {
            val (image, g, painter) = fixture()
            painter.paint(g, 0x2510, 0, 0, width, height) // ┐ Light Down and Left

            // Should touch Left and Bottom bounds
            assertTrue(isPainted(image, 0, cy))
            assertTrue(isPainted(image, cx, height - 1))

            // Should NOT touch Top and Right bounds
            assertTrue(!isPainted(image, cx, 0))
            assertTrue(!isPainted(image, width - 1, cy))
            g.dispose()
        }
    }

    @Nested
    inner class RoundedGeometry {
        @Test
        fun `Top-Left rounded corner stays strictly within upper-left bounding box`() {
            val (image, g, painter) = fixture()
            // Important: We test the geometry bounds, not exact pixels due to anti-aliasing variations
            painter.paint(g, 0x256D, 0, 0, width, height) // ╭ Top-Left Corner

            // It connects the Right and Bottom bounds, meaning the arc lives in the Bottom-Right quadrant
            assertTrue(isPainted(image, width - 1, cy), "Did not connect to right edge")
            assertTrue(isPainted(image, cx, height - 1), "Did not connect to bottom edge")

            // It MUST NOT bleed into the Top or Left bounds
            assertTrue(!isPainted(image, 0, cy), "Bled into left edge")
            assertTrue(!isPainted(image, cx, 0), "Bled into top edge")
            g.dispose()
        }
    }

    private fun fixture(): Triple<BufferedImage, Graphics2D, TerminalBoxDrawingPainter> {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.RED
        // Override stroke to prevent AA bleed during exact pixel tests
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        return Triple(image, g, TerminalBoxDrawingPainter())
    }

    private fun isPainted(
        image: BufferedImage,
        x: Int,
        y: Int,
    ): Boolean = image.getRGB(x, y) != 0
}
