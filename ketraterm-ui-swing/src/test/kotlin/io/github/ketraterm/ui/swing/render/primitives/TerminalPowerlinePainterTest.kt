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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPowerlinePainterTest {
    @ParameterizedTest
    @ValueSource(
        ints = [
            0xE0B0, 0xE0B1, 0xE0B2, 0xE0B3, 0xE0B4, 0xE0B5, 0xE0B6, 0xE0B7,
            0xE0B8, 0xE0B9, 0xE0BA, 0xE0BB, 0xE0BC, 0xE0BD, 0xE0BE, 0xE0BF,
        ],
    )
    fun `every geometric separator paints inside its cell`(codePoint: Int) {
        assertTrue(TerminalCellPrimitivePainter().canPaint(codePoint))
        val image = render(codePoint)
        var ink = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) ushr 24 == 0) continue
                assertTrue(x in 4 until 28 && y in 4 until 44, "Outside cell: ($x,$y)")
                assertEquals(0xFF0000, image.getRGB(x, y) and 0xFFFFFF)
                ink++
            }
        }
        assertTrue(ink > 20, "Separator ${codePoint.toString(16)} did not paint")
    }

    @ParameterizedTest
    @CsvSource(
        "0xE0B0, 0xE0B2",
        "0xE0B1, 0xE0B3",
        "0xE0B4, 0xE0B6",
        "0xE0B5, 0xE0B7",
        "0xE0B8, 0xE0BA",
        "0xE0B9, 0xE0BB",
        "0xE0BC, 0xE0BE",
        "0xE0BD, 0xE0BF",
    )
    fun `left and right separators are horizontal mirrors`(
        leftCodePoint: Int,
        rightCodePoint: Int,
    ) {
        val left = render(leftCodePoint)
        val right = render(rightCodePoint)
        for (y in 0 until 40) {
            for (x in 0 until 24) {
                val a = left.getRGB(x + 4, y + 4) ushr 24
                val b = right.getRGB(27 - x, y + 4) ushr 24
                assertTrue(abs(a - b) <= 2, "Mirror differs at ($x,$y): $a vs $b")
            }
        }
    }

    @Test
    fun `filled diagonal separators occupy their specified corners`() {
        val codePoints = intArrayOf(0xE0B8, 0xE0BA, 0xE0BC, 0xE0BE)
        val cornerX = intArrayOf(6, 25, 6, 25)
        val cornerY = intArrayOf(41, 41, 6, 6)
        for (i in codePoints.indices) {
            val image = render(codePoints[i])
            assertEquals(255, image.getRGB(cornerX[i], cornerY[i]) ushr 24)
            assertEquals(0, image.getRGB(31 - cornerX[i], 47 - cornerY[i]) ushr 24)
        }
    }

    @Test
    fun `rounded caps curve outside triangle coverage and outlines leave the base open`() {
        val triangle = render(0xE0B0)
        val rounded = render(0xE0B4)
        assertEquals(0, triangle.getRGB(16, 9) ushr 24)
        assertEquals(255, rounded.getRGB(16, 9) ushr 24)
        for (cp in intArrayOf(0xE0B1, 0xE0B3, 0xE0B5, 0xE0B7)) {
            val image = render(cp)
            assertEquals(0, image.getRGB(if (cp and 2 == 0) 4 else 27, 24) ushr 24)
            assertTrue(image.getRGB(if (cp and 2 == 0) 27 else 4, 24) ushr 24 > 0)
        }
    }

    @Test
    fun `painting preserves graphics hints stroke color and composite`() {
        val image = BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color.RED
            g.stroke = BasicStroke(7f)
            g.composite =
                java.awt.AlphaComposite.SrcOver
                    .derive(0.5f)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            val hints = g.renderingHints
            val stroke = g.stroke
            val composite = g.composite
            val painter = TerminalPowerlinePainter()
            for (cp in 0xE0B0..0xE0BF) {
                g.clipRect(4, 4, 24, 40)
                painter.paint(g, cp, 4, 4, 24, 40)
                assertEquals(hints, g.renderingHints)
                assertEquals(stroke, g.stroke)
                assertEquals(composite, g.composite)
                assertEquals(Color.RED, g.color)
            }
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `unhandled private use symbols retain font fallback and degenerate cells are empty`() {
        val router = TerminalCellPrimitivePainter()
        for (cp in intArrayOf(0xE0A0, 0xE0AF, 0xE0C0, 0xE0D4)) assertFalse(router.canPaint(cp))
        for ((width, height) in listOf(0 to 20, 20 to 0, -1 to 20, 20 to -1)) {
            val image = render(0xE0B0, width, height)
            assertTrue(image.getRGB(0, 0, 32, 48, null, 0, 32).all { it == 0 })
        }
    }

    private fun render(
        codePoint: Int,
        width: Int = 24,
        height: Int = 40,
    ): BufferedImage {
        val image = BufferedImage(32, 48, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color.RED
            g.setClip(4, 4, maxOf(0, width), maxOf(0, height))
            TerminalPowerlinePainter().paint(g, codePoint, 4, 4, width, height)
        } finally {
            g.dispose()
        }
        return image
    }
}
