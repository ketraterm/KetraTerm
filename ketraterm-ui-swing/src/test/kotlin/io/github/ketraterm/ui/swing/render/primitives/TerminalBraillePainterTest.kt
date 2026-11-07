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

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalBraillePainterTest {
    @Test
    fun `all patterns paint exactly the Unicode dot positions including blank`() {
        // Dot numbering is deliberately explicit, independent of the painter's bit traversal.
        val dotX = intArrayOf(3, 3, 3, 9, 9, 9, 3, 9)
        val dotY = intArrayOf(3, 9, 15, 3, 9, 15, 21, 21)
        val router = TerminalCellPrimitivePainter()
        assertFalse(router.canPaint(0x27FF))
        assertFalse(router.canPaint(0x2900))
        for (pattern in 0..255) {
            assertTrue(router.canPaint(0x2800 + pattern))
            val image = BufferedImage(16, 28, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = Color.RED
                TerminalBraillePainter.paint(g, 0x2800 + pattern, 2, 2, 12, 24)
            } finally {
                g.dispose()
            }
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    var expected = 0
                    for (dot in 0 until 8) {
                        if (pattern and (1 shl dot) != 0 &&
                            x in dotX[dot] + 1..dotX[dot] + 3 &&
                            y in dotY[dot] + 1..dotY[dot] + 3
                        ) {
                            expected = Color.RED.rgb
                        }
                    }
                    assertEquals(expected, image.getRGB(x, y), "Pattern ${pattern.toString(16)} at ($x,$y)")
                }
            }
        }
    }

    @Test
    fun `tiny and degenerate cells stay within their bounds`() {
        for (width in 0..3) {
            for (height in 0..7) {
                val image = BufferedImage(8, 12, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try {
                    g.color = Color.RED
                    TerminalBraillePainter.paint(g, 0x28FF, 2, 2, width, height)
                } finally {
                    g.dispose()
                }
                var painted = 0
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        if (image.getRGB(x, y) == 0) continue
                        assertTrue(x in 2 until 2 + width && y in 2 until 2 + height)
                        painted++
                    }
                }
                assertEquals(width > 0 && height > 0, painted > 0)
            }
        }
    }
}
