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

import io.github.jvterm.ui.swing.render.font.TerminalSystemFontFamilies
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.awt.Font

class TerminalFontCacheTest {
    @Test
    fun `font returns cached primary style variant`() {
        val base = TerminalCacheTestFonts.primary(14f)
        val cache = TerminalFontCache()

        cache.update(base, emptyList(), useSystemFallbackFonts = false)

        Assertions.assertSame(base, cache.font(Font.PLAIN))
        Assertions.assertSame(cache.font(Font.BOLD), cache.font(Font.BOLD))
    }

    @Test
    fun `update reports whether font settings changed`() {
        val base = TerminalCacheTestFonts.primary(14f)
        val fallback = TerminalCacheTestFonts.fallback(14f)
        val cache = TerminalFontCache()

        Assertions.assertTrue(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        Assertions.assertFalse(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        Assertions.assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = false))
        Assertions.assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = true))
    }

    @Test
    fun `generation changes only when font settings change`() {
        val base = TerminalCacheTestFonts.primary(14f)
        val fallback = TerminalCacheTestFonts.fallback(14f)
        val cache = TerminalFontCache()

        val initialGeneration = cache.generation
        Assertions.assertTrue(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        val firstGeneration = cache.generation
        Assertions.assertFalse(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        Assertions.assertEquals(firstGeneration, cache.generation)
        Assertions.assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = false))

        Assertions.assertEquals(initialGeneration + 1, firstGeneration)
        Assertions.assertEquals(firstGeneration + 1, cache.generation)
    }

    @Test
    fun `fontForText uses configured fallback when primary cannot display text`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallback = TerminalCacheTestFonts.fallback(11f)
        val fallbackOnlyText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT

        Assertions.assertTrue(primary.canDisplayUpTo(fallbackOnlyText) >= 0)
        Assertions.assertTrue(fallback.canDisplayUpTo(fallbackOnlyText) < 0)

        val cache = TerminalFontCache()
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(fallbackOnlyText, Font.BOLD)

        Assertions.assertEquals(Font.BOLD, resolved.style)
        Assertions.assertEquals(primary.size2D, resolved.size2D)
        Assertions.assertEquals(fallback.family, resolved.family)
    }

    @Test
    fun `fontForText caches fallback fonts per style`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallback = TerminalCacheTestFonts.fallback(11f)
        val fallbackOnlyText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT

        Assertions.assertTrue(primary.canDisplayUpTo(fallbackOnlyText) >= 0)
        Assertions.assertTrue(fallback.canDisplayUpTo(fallbackOnlyText) < 0)

        val cache = TerminalFontCache()
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val plain = cache.fontForText(fallbackOnlyText, Font.PLAIN)
        val bold = cache.fontForText(fallbackOnlyText, Font.BOLD)

        Assertions.assertEquals(Font.PLAIN, plain.style)
        Assertions.assertEquals(Font.BOLD, bold.style)
        Assertions.assertEquals(fallback.family, plain.family)
        Assertions.assertEquals(fallback.family, bold.family)
    }

    @Test
    fun `fontForText caches missing glyph resolution to primary font`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val missing = String(Character.toChars(TerminalCacheTestFonts.MISSING_CODE_POINT))

        Assertions.assertTrue(primary.canDisplayUpTo(missing) >= 0)

        val cache = TerminalFontCache()
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(missing, Font.PLAIN)

        Assertions.assertSame(primary, resolved)
        Assertions.assertSame(primary, cache.resolvedTextFontCache(Font.PLAIN)[missing])
    }

    @Test
    fun `fontForText caches missing glyph resolution per style`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val missing = String(Character.toChars(TerminalCacheTestFonts.MISSING_CODE_POINT))

        Assertions.assertTrue(primary.canDisplayUpTo(missing) >= 0)

        val cache = TerminalFontCache()
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(missing, Font.BOLD)

        Assertions.assertEquals(Font.BOLD, resolved.style)
        Assertions.assertSame(resolved, cache.resolvedTextFontCache(Font.BOLD)[missing])
        Assertions.assertNull(cache.resolvedTextFontCache(Font.PLAIN)[missing])
    }

    @Test
    fun `fontForText evicts old cluster fallback entries`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val cache = TerminalFontCache(textFallbackCapacityPerStyle = 2)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        cache.fontForText(String(Character.toChars(0x10FFFF)), Font.PLAIN)
        cache.fontForText(String(Character.toChars(0x10FFFE)), Font.PLAIN)
        cache.fontForText(String(Character.toChars(0x10FFFD)), Font.PLAIN)

        Assertions.assertEquals(2, cache.resolvedTextFontCache(Font.PLAIN).size)
    }

    @Test
    fun `fontForCodePoint uses primitive bounded cache`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val codePoint = TerminalCacheTestFonts.MISSING_CODE_POINT

        Assertions.assertFalse(primary.canDisplay(codePoint))

        val cache = TerminalFontCache(codePointFallbackCapacityPerStyle = 2)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForCodePoint(codePoint, Font.BOLD)

        Assertions.assertEquals(Font.BOLD, resolved.style)
        Assertions.assertEquals(1, cache.resolvedCodePointFontCacheSize(Font.BOLD))
        Assertions.assertTrue(cache.resolvedTextFontCache(Font.BOLD).isEmpty())
    }

    @Test
    fun `fontForCodePoint evicts old primitive fallback entries`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        Assertions.assertFalse(primary.canDisplay(0x10FFFF))
        Assertions.assertFalse(primary.canDisplay(0x10FFFE))
        Assertions.assertFalse(primary.canDisplay(0x10FFFD))

        val cache = TerminalFontCache(codePointFallbackCapacityPerStyle = 2)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        cache.fontForCodePoint(0x10FFFF, Font.PLAIN)
        cache.fontForCodePoint(0x10FFFE, Font.PLAIN)
        cache.fontForCodePoint(0x10FFFD, Font.PLAIN)

        Assertions.assertEquals(2, cache.resolvedCodePointFontCacheSize(Font.PLAIN))
    }

    @Test
    fun `system fallback uses lazy family fonts after configured fallbacks miss`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallbackFamily = TerminalCacheTestFonts.registerFallbackFamily()
        val systemFamilies = RecordingSystemFontFamilies(listOf(fallbackFamily))
        val fallbackOnlyText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT

        Assertions.assertTrue(primary.canDisplayUpTo(fallbackOnlyText) >= 0)

        val cache = TerminalFontCache(systemFontFamilies = systemFamilies)
        cache.update(primary, emptyList(), useSystemFallbackFonts = true)
        Assertions.assertEquals(0, systemFamilies.calls)

        val resolved = cache.fontForText(fallbackOnlyText, Font.PLAIN)

        Assertions.assertEquals(fallbackFamily, resolved.family)
        Assertions.assertEquals(1, systemFamilies.calls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun TerminalFontCache.resolvedTextFontCache(style: Int): Map<String, Font> {
        val field = TerminalFontCache::class.java.getDeclaredField("resolvedTextFonts")
        field.isAccessible = true
        val caches = field.get(this) as Array<Map<String, Font>>
        return caches[style and (Font.BOLD or Font.ITALIC)]
    }

    @Suppress("UNCHECKED_CAST")
    private fun TerminalFontCache.resolvedCodePointFontCacheSize(style: Int): Int {
        val field = TerminalFontCache::class.java.getDeclaredField("resolvedCodePointFonts")
        field.isAccessible = true
        val caches = field.get(this) as Array<Any>
        val sizeField = caches[style and (Font.BOLD or Font.ITALIC)].javaClass.getDeclaredField("size")
        sizeField.isAccessible = true
        return sizeField.getInt(caches[style and (Font.BOLD or Font.ITALIC)])
    }

    private class RecordingSystemFontFamilies(
        private val families: List<String>,
    ) : TerminalSystemFontFamilies {
        var calls = 0
            private set

        override fun familiesOrStartLoading(): List<String> {
            calls++
            return families
        }
    }
}
