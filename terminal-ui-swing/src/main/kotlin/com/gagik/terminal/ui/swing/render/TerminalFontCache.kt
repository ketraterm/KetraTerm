package com.gagik.terminal.ui.swing.render

import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * Caches terminal font style variants for one settings snapshot.
 */
internal class TerminalFontCache {
    private var baseFont: Font? = null
    private var fallbackBaseFonts: List<Font> = emptyList()
    private var useSystemFallbackFonts: Boolean = false
    private val styleFonts = arrayOfNulls<Font>(STYLE_COUNT)
    private var fallbackStyleFonts: Array<Array<Font?>> = emptyArray()
    private var systemStyleFonts: Array<Array<Font?>> = emptyArray()
    private val resolvedTextFonts = Array(STYLE_COUNT) { HashMap<String, Font>() }

    /**
     * Rebuilds cached style variants when [font] changes.
     *
     * @param font base terminal font.
     */
    fun update(font: Font, fallbackFonts: List<Font>, useSystemFallbackFonts: Boolean) {
        if (
            font == baseFont &&
            fallbackFonts == fallbackBaseFonts &&
            useSystemFallbackFonts == this.useSystemFallbackFonts
        ) {
            return
        }

        baseFont = font
        fallbackBaseFonts = fallbackFonts
        this.useSystemFallbackFonts = useSystemFallbackFonts
        styleFonts.fill(null)
        styleFonts[font.style and STYLE_MASK] = font
        fallbackStyleFonts = Array(fallbackFonts.size) { arrayOfNulls(STYLE_COUNT) }
        systemStyleFonts = if (useSystemFallbackFonts) {
            Array(systemFallbackFonts.size) { arrayOfNulls(STYLE_COUNT) }
        } else {
            emptyArray()
        }
        for (cache in resolvedTextFonts) {
            cache.clear()
        }
    }

    /**
     * Returns a cached font variant for [style].
     *
     * @param style AWT style bit mask.
     * @return cached style font.
     */
    fun font(style: Int): Font {
        val normalizedStyle = style and STYLE_MASK
        val cached = styleFonts[normalizedStyle]
        if (cached != null) return cached

        val font = requireNotNull(baseFont) {
            "TerminalFontCache.update must be called before font"
        }.deriveFont(normalizedStyle)
        styleFonts[normalizedStyle] = font
        return font
    }

    /**
     * Returns the first cached style font that can display all UTF-16 units in
     * [text], falling back to [font] when no configured fallback covers it.
     */
    fun fontForText(text: String, style: Int): Font {
        val normalizedStyle = style and STYLE_MASK
        val primary = font(normalizedStyle)
        if (primary.canDisplayUpTo(text) < 0) return primary

        val styleResolvedTextFonts = resolvedTextFonts[normalizedStyle]
        val cached = styleResolvedTextFonts[text]
        if (cached != null) return cached

        var index = 0
        while (index < fallbackBaseFonts.size) {
            val fallback = fallbackFont(index, normalizedStyle)
            if (fallback.canDisplayUpTo(text) < 0) {
                styleResolvedTextFonts[text] = fallback
                return fallback
            }
            index++
        }

        if (useSystemFallbackFonts) {
            index = 0
            while (index < systemFallbackFonts.size) {
                val fallback = systemFallbackFont(index, normalizedStyle)
                if (fallback.canDisplayUpTo(text) < 0) {
                    styleResolvedTextFonts[text] = fallback
                    return fallback
                }
                index++
            }
        }

        return primary
    }

    private fun fallbackFont(index: Int, style: Int): Font {
        val normalizedStyle = style and STYLE_MASK
        val cached = fallbackStyleFonts[index][normalizedStyle]
        if (cached != null) return cached

        val base = requireNotNull(baseFont) {
            "TerminalFontCache.update must be called before fallbackFont"
        }
        val fallback = fallbackBaseFonts[index]
            .deriveFont(normalizedStyle, base.size2D)
        fallbackStyleFonts[index][normalizedStyle] = fallback
        return fallback
    }

    private fun systemFallbackFont(index: Int, style: Int): Font {
        val normalizedStyle = style and STYLE_MASK
        val cached = systemStyleFonts[index][normalizedStyle]
        if (cached != null) return cached

        val base = requireNotNull(baseFont) {
            "TerminalFontCache.update must be called before systemFallbackFont"
        }
        val fallback = systemFallbackFonts[index]
            .deriveFont(normalizedStyle, base.size2D)
        systemStyleFonts[index][normalizedStyle] = fallback
        return fallback
    }

    private companion object {
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC

        private val systemFallbackFonts: List<Font> by lazy {
            GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .allFonts
                .distinctBy { it.family }
        }
    }
}
