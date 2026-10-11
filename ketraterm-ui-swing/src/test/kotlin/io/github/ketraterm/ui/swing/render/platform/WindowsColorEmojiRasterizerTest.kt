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
package io.github.ketraterm.ui.swing.render.platform

import io.github.ketraterm.ui.swing.render.cache.FontCache
import io.github.ketraterm.ui.swing.render.cache.TerminalCacheTestFonts
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsColorEmojiRasterizerTest {
    @Test
    fun `installed Windows symbol font supplies text glyphs for VS15`() {
        assumeTrue(System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("windows"))
        val symbols = Font("Segoe UI Symbol", Font.PLAIN, 18)
        val emoji = Font("Segoe UI Emoji", Font.PLAIN, 18)
        assumeTrue(symbols.family == "Segoe UI Symbol" && emoji.family == "Segoe UI Emoji")
        val cache = FontCache()
        cache.update(TerminalCacheTestFonts.primary(18f), listOf(emoji, symbols), useSystemFallbackFonts = false)
        val context = FontRenderContext(null, true, true)
        for (codePoint in intArrayOf(0x2764, 0x2615, 0x2708, 0x2620, 0x2699, 0x26A1, 0x2B50)) {
            val text = String(Character.toChars(codePoint)) + "\uFE0E"
            val font = cache.fontForText(text, Font.PLAIN)
            assertEquals(symbols.family, font.family, "VS15 must select the installed text font for U+${codePoint.toString(16)}")
            assertTrue(font.canDisplay(codePoint), "The selected font must supply a real glyph")
            val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = java.awt.Color.WHITE
                TextLayout(text, font, context).draw(g, 0f, 25f)
            } finally {
                g.dispose()
            }
            assertTrue((0 until 40).any { y -> (0 until 40).any { x -> image.getRGB(x, y) ushr 24 != 0 } })
            assertTrue(!image.hasSaturatedVisibleColor(), "VS15 must use the text foreground, rather than color emoji layers")
            assertEquals(emoji.family, cache.fontForText(text.replace('\uFE0E', '\uFE0F'), Font.PLAIN).family)
        }
    }

    @Test
    fun `installed Windows emoji font rasterizes color layers`() {
        assumeTrue(System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("windows"))

        val rasterizer = WindowsColorEmojiRasterizer.create()
        assumeTrue(rasterizer.available)

        val image = rasterizer.rasterize("\uD83D\uDE00", 32)
        assertNotNull(image)
        assertTrue(
            image.hasSaturatedVisibleColor(),
            "Windows emoji rasterizer produced a monochrome or empty image",
        )
    }

    private fun BufferedImage.hasSaturatedVisibleColor(): Boolean {
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val argb = getRGB(x, y)
                if ((argb ushr 24) > 32) {
                    val red = argb shr 16 and 0xFF
                    val green = argb shr 8 and 0xFF
                    val blue = argb and 0xFF
                    if (maxOf(red, green, blue) - minOf(red, green, blue) > 24) {
                        return true
                    }
                }
                x++
            }
            y++
        }
        return false
    }
}
