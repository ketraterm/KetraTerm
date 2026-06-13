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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

class TerminalAsciiGlyphVectorCacheTest {
    @Nested
    inner class Lookup {
        @Test
        fun `reuses glyph vector for same text font style cell width and render context`() {
            val cache = TerminalAsciiGlyphVectorCache()
            val font = Font(Font.SERIF, Font.PLAIN, 18)
            val frc = FontRenderContext(AffineTransform(), false, true)

            val chars = "ii".toCharArray()

            val first = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            val second = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)

            assertSame(first, second)
        }

        @Test
        fun `lookup does not retain mutable caller buffer`() {
            val cache = TerminalAsciiGlyphVectorCache()
            val font = Font(Font.SERIF, Font.PLAIN, 18)
            val frc = FontRenderContext(AffineTransform(), false, true)
            val chars = "ab".toCharArray()

            val first = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            chars[0] = 'z'
            chars[0] = 'a'
            val second = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)

            assertSame(first, second)
        }

        @Test
        fun `lookup supports offset runs without copying on hits`() {
            val cache = TerminalAsciiGlyphVectorCache()
            val font = Font(Font.SERIF, Font.PLAIN, 18)
            val frc = FontRenderContext(AffineTransform(), false, true)
            val firstChars = "xxabc".toCharArray()
            val secondChars = "abcxx".toCharArray()

            val first = cache.glyphVector(firstChars, 2, 3, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            val second = cache.glyphVector(secondChars, 0, 3, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)

            assertSame(first, second)
        }

        @Test
        fun `splits entries by style font and cell width`() {
            val cache = TerminalAsciiGlyphVectorCache()
            val plain = Font(Font.SERIF, Font.PLAIN, 18)
            val bold = Font(Font.SERIF, Font.BOLD, 18)
            val sans = Font(Font.SANS_SERIF, Font.PLAIN, 18)
            val frc = FontRenderContext(AffineTransform(), false, true)
            val chars = "ii".toCharArray()

            val base = cache.glyphVector(chars, 0, chars.size, plain, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            val styleSplit = cache.glyphVector(chars, 0, chars.size, plain, Font.BOLD, cellWidth = 18, fontRenderContext = frc)
            val fontSplit = cache.glyphVector(chars, 0, chars.size, sans, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            val widthSplit = cache.glyphVector(chars, 0, chars.size, bold, Font.BOLD, cellWidth = 20, fontRenderContext = frc)

            assertNotSame(base, styleSplit)
            assertNotSame(base, fontSplit)
            assertNotSame(styleSplit, widthSplit)
        }

        @Test
        fun `invalidates cached vectors when render context changes`() {
            val cache = TerminalAsciiGlyphVectorCache()
            val font = Font(Font.SERIF, Font.PLAIN, 18)
            val firstContext = FontRenderContext(AffineTransform(), false, false)
            val secondContext = FontRenderContext(AffineTransform.getScaleInstance(2.0, 2.0), false, false)
            val chars = "ii".toCharArray()

            val first = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = firstContext)
            val second = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = secondContext)

            assertNotSame(first, second)
        }
    }

    @Nested
    inner class Positioning {
        @Test
        fun `pins glyph origins to terminal cell boundaries`() {
            val cache = TerminalAsciiGlyphVectorCache()
            val font = Font(Font.SERIF, Font.PLAIN, 18)
            val frc = FontRenderContext(AffineTransform(), false, true)
            val chars = "iii".toCharArray()

            val glyphVector = cache.glyphVector(chars, 0, chars.size, font, Font.PLAIN, cellWidth = 17, fontRenderContext = frc)

            assertEquals(0.0, glyphVector.getGlyphPosition(0).x, 0.0)
            assertEquals(17.0, glyphVector.getGlyphPosition(1).x, 0.0)
            assertEquals(34.0, glyphVector.getGlyphPosition(2).x, 0.0)
            assertEquals(51.0, glyphVector.getGlyphPosition(3).x, 0.0)
        }
    }

    @Nested
    inner class Capacity {
        @Test
        fun `evicts least recently used vector when capacity is exceeded`() {
            val cache = TerminalAsciiGlyphVectorCache(capacity = 2)
            val font = Font(Font.SERIF, Font.PLAIN, 18)
            val frc = FontRenderContext(AffineTransform(), false, true)
            val aa = "aa".toCharArray()
            val bb = "bb".toCharArray()
            val cc = "cc".toCharArray()

            val first = cache.glyphVector(aa, 0, aa.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            val second = cache.glyphVector(bb, 0, bb.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)
            assertSame(first, cache.glyphVector(aa, 0, aa.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc))

            cache.glyphVector(cc, 0, cc.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc)

            assertSame(first, cache.glyphVector(aa, 0, aa.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc))
            assertNotSame(second, cache.glyphVector(bb, 0, bb.size, font, Font.PLAIN, cellWidth = 18, fontRenderContext = frc))
        }

        @Test
        fun `rejects non positive capacity`() {
            assertThrows(IllegalArgumentException::class.java) {
                TerminalAsciiGlyphVectorCache(capacity = 0)
            }
        }
    }
}
