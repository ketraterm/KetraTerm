package com.gagik.terminal.ui.swing.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Font

class TerminalFontCacheTest {
    @Test
    fun `font returns cached primary style variant`() {
        val base = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val cache = TerminalFontCache()

        cache.update(base, emptyList(), useSystemFallbackFonts = false)

        assertSame(base, cache.font(Font.PLAIN))
        assertSame(cache.font(Font.BOLD), cache.font(Font.BOLD))
    }

    @Test
    fun `update reports whether font settings changed`() {
        val base = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val fallback = Font("Dialog", Font.PLAIN, 14)
        val cache = TerminalFontCache()

        assertTrue(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        assertFalse(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = false))
        assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = true))
    }

    @Test
    fun `generation changes only when font settings change`() {
        val base = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val fallback = Font("Dialog", Font.PLAIN, 14)
        val cache = TerminalFontCache()

        val initialGeneration = cache.generation
        assertTrue(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        val firstGeneration = cache.generation
        assertFalse(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        assertEquals(firstGeneration, cache.generation)
        assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = false))

        assertEquals(initialGeneration + 1, firstGeneration)
        assertEquals(firstGeneration + 1, cache.generation)
    }

    @Test
    fun `fontForText uses configured fallback when primary cannot display text`() {
        val primary = Font("Courier New", Font.PLAIN, 17)
        val fallback = Font("Dialog", Font.PLAIN, 11)
        val thai = "\u0E01\u0E34"

        assumeTrue(primary.canDisplayUpTo(thai) >= 0)
        assumeTrue(fallback.canDisplayUpTo(thai) < 0)

        val cache = TerminalFontCache()
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(thai, Font.BOLD)

        assertEquals(Font.BOLD, resolved.style)
        assertEquals(primary.size2D, resolved.size2D)
        assertEquals(fallback.family, resolved.family)
    }

    @Test
    fun `fontForText caches fallback fonts per style`() {
        val primary = Font("Courier New", Font.PLAIN, 17)
        val fallback = Font("Dialog", Font.PLAIN, 11)
        val thai = "\u0E01\u0E34"

        assumeTrue(primary.canDisplayUpTo(thai) >= 0)
        assumeTrue(fallback.canDisplayUpTo(thai) < 0)

        val cache = TerminalFontCache()
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val plain = cache.fontForText(thai, Font.PLAIN)
        val bold = cache.fontForText(thai, Font.BOLD)

        assertEquals(Font.PLAIN, plain.style)
        assertEquals(Font.BOLD, bold.style)
        assertEquals(fallback.family, plain.family)
        assertEquals(fallback.family, bold.family)
    }

    @Test
    fun `fontForText caches missing glyph resolution to primary font`() {
        val primary = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val missing = String(Character.toChars(0x10FFFF))

        assumeTrue(primary.canDisplayUpTo(missing) >= 0)

        val cache = TerminalFontCache()
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(missing, Font.PLAIN)

        assertSame(primary, resolved)
        assertSame(primary, cache.resolvedFontCache(Font.PLAIN)[missing])
    }

    @Test
    fun `fontForText caches missing glyph resolution per style`() {
        val primary = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val missing = String(Character.toChars(0x10FFFF))

        assumeTrue(primary.canDisplayUpTo(missing) >= 0)

        val cache = TerminalFontCache()
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(missing, Font.BOLD)

        assertEquals(Font.BOLD, resolved.style)
        assertSame(resolved, cache.resolvedFontCache(Font.BOLD)[missing])
        assertNull(cache.resolvedFontCache(Font.PLAIN)[missing])
    }

    @Suppress("UNCHECKED_CAST")
    private fun TerminalFontCache.resolvedFontCache(style: Int): Map<String, Font> {
        val field = TerminalFontCache::class.java.getDeclaredField("resolvedTextFonts")
        field.isAccessible = true
        val caches = field.get(this) as Array<HashMap<String, Font>>
        return caches[style and (Font.BOLD or Font.ITALIC)]
    }
}
