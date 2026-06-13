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
package io.github.jvterm.ui.swing.render.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

class TerminalAsciiDrawCharsCacheTest {
    @Test
    fun `compatible monospace font can use drawChars`() {
        val font = TerminalCacheTestFonts.primary(18f)
        val frc = FontRenderContext(AffineTransform(), false, false)
        val cellWidth = fixedAsciiAdvance(font, frc)
        val cache = TerminalAsciiDrawCharsCache()

        assertTrue(cache.canDrawChars(font, Font.PLAIN, cellWidth, frc))
    }

    @Test
    fun `mismatched cell width cannot use drawChars`() {
        val font = TerminalCacheTestFonts.primary(18f)
        val frc = FontRenderContext(AffineTransform(), false, false)
        val cellWidth = fixedAsciiAdvance(font, frc)
        val cache = TerminalAsciiDrawCharsCache()

        assertFalse(cache.canDrawChars(font, Font.PLAIN, cellWidth + 1, frc))
    }

    @Test
    fun `style entries are cached independently`() {
        val plain = TerminalCacheTestFonts.primary(18f)
        val bold = TerminalCacheTestFonts.primary(18f)
        val frc = FontRenderContext(AffineTransform(), false, false)
        val plainWidth = fixedAsciiAdvance(plain, frc)
        val boldWidth = fixedAsciiAdvance(bold, frc)
        val cache = TerminalAsciiDrawCharsCache()

        assertTrue(cache.canDrawChars(plain, Font.PLAIN, plainWidth, frc))
        assertTrue(cache.canDrawChars(bold, Font.BOLD, boldWidth, frc))
        assertTrue(cache.canDrawChars(plain, Font.PLAIN, plainWidth, frc))
    }

    @Test
    fun `render context is part of compatibility key`() {
        val font = TerminalCacheTestFonts.primary(18f)
        val firstContext = FontRenderContext(AffineTransform(), false, false)
        val secondContext = FontRenderContext(AffineTransform.getScaleInstance(2.0, 2.0), false, false)
        val cache = TerminalAsciiDrawCharsCache()

        val firstWidth = fixedAsciiAdvance(font, firstContext)
        val secondWidth = fixedAsciiAdvance(font, secondContext)

        assertTrue(cache.canDrawChars(font, Font.PLAIN, firstWidth, firstContext))
        assertTrue(cache.canDrawChars(font, Font.PLAIN, secondWidth, secondContext))
    }

    private fun fixedAsciiAdvance(
        font: Font,
        frc: FontRenderContext,
    ): Int {
        val probe = CharArray(1)
        probe[0] = ' '.code.toChar()
        val firstAdvance = font.createGlyphVector(frc, probe).getGlyphPosition(1).x
        assertEquals(firstAdvance.toInt().toDouble(), firstAdvance, "test font advance is fractional")

        var codepoint = 0x21
        while (codepoint <= 0x7e) {
            probe[0] = codepoint.toChar()
            val advance = font.createGlyphVector(frc, probe).getGlyphPosition(1).x
            assertEquals(firstAdvance, advance, "test font is not fixed-width for printable ASCII")
            codepoint++
        }

        return firstAdvance.toInt()
    }
}
