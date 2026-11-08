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
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalMosaicPainterTest {
    @ParameterizedTest
    @CsvSource("12, 24", "7, 13", "1, 1", "2, 3", "22, 17")
    fun `all sextants and octants match Unicode names without gaps overlap or spill`(
        width: Int,
        height: Int,
    ) {
        val glyphs = glyphs()
        assertEquals(60, glyphs.count { it.rows == 3 })
        assertEquals(230, glyphs.count { it.rows == 4 })
        val router = TerminalCellPrimitivePainter()
        for (glyph in glyphs) {
            assertTrue(router.canPaint(glyph.codePoint))
            val image = BufferedImage(width + 4, height + 4, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = Color.RED
                g.composite = AlphaComposite.SrcOver.derive(0.5f)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                TerminalMosaicPainter.paint(g, glyph.codePoint, 2, 2, width, height)
            } finally {
                g.dispose()
            }
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val inside = x in 2 until width + 2 && y in 2 until height + 2
                    val expected =
                        if (inside) {
                            val column = if (x - 2 < width / 2) 0 else 1
                            val row = (0 until glyph.rows).first { y - 2 < (it + 1) * height / glyph.rows }
                            if (row * 2 + column + 1 in glyph.parts) 0x80FF0000.toInt() else 0
                        } else {
                            0
                        }
                    assertEquals(expected, image.getRGB(x, y), "U+${glyph.codePoint.toString(16)} at ($x,$y), ${width}x$height")
                }
            }
        }
    }

    @Test
    fun `complementary shapes tile a translucent cell exactly once`() {
        for ((a, b) in listOf(0x1FB00 to 0x1FB3B, 0x1CD00 to 0x1CDE3)) {
            val image = BufferedImage(7, 13, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = Color.RED
                g.composite = AlphaComposite.SrcOver.derive(0.5f)
                TerminalMosaicPainter.paint(g, a, 0, 0, 7, 13)
                TerminalMosaicPainter.paint(g, b, 0, 0, 7, 13)
            } finally {
                g.dispose()
            }
            assertTrue(image.getRGB(0, 0, 7, 13, null, 0, 7).all { it == 0x80FF0000.toInt() })
        }
    }

    @Test
    fun `unrelated supplementary symbols keep font fallback and invalid bounds are empty`() {
        val router = TerminalCellPrimitivePainter()
        for (cp in intArrayOf(0x1FAFF, 0x1FB3C, 0x1CCFF, 0x1CDE6)) assertFalse(router.canPaint(cp))
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color.RED
            for (cp in intArrayOf(0x1FB00, 0x1CD00)) {
                TerminalMosaicPainter.paint(g, cp, 0, 0, 0, 8)
                TerminalMosaicPainter.paint(g, cp, 0, 0, 8, 0)
                TerminalMosaicPainter.paint(g, cp, 0, 0, -1, 8)
                TerminalMosaicPainter.paint(g, cp, 0, 0, 8, -1)
            }
            TerminalMosaicPainter.paint(g, 0x1FB3C, 0, 0, 8, 8)
        } finally {
            g.dispose()
        }
        assertTrue(image.getRGB(0, 0, 8, 8, null, 0, 8).all { it == 0 })
    }

    private data class Glyph(
        val codePoint: Int,
        val rows: Int,
        val parts: Set<Int>,
    )

    private fun glyphs(): List<Glyph> =
        checkNotNull(javaClass.getResourceAsStream("/unicode/17.0.0/mosaic-blocks.txt"))
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines
                    .filter { it.isNotBlank() && !it.startsWith('#') }
                    .map { line ->
                        val (code, name) = line.split(';')
                        Glyph(
                            code.toInt(16),
                            if (name.startsWith("BLOCK SEXTANT-")) 3 else 4,
                            name.substringAfter('-').map { it.digitToInt() }.toSet(),
                        )
                    }.toList()
            }
}
