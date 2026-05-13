package com.gagik.terminal.ui.swing.render

import java.awt.Font

/**
 * Caches terminal font style variants for one settings snapshot.
 */
internal class TerminalFontCache {
    private var baseFont: Font? = null
    private var fallbackBaseFonts: List<Font> = emptyList()
    private var systemFallbackBaseFonts: List<Font> = emptyList()
    private var useSystemFallbackFonts: Boolean = false
    private val styleFonts = arrayOfNulls<Font>(STYLE_COUNT)
    private var fallbackStyleFonts: Array<Array<Font?>> = emptyArray()
    private var systemStyleFonts: Array<Array<Font?>> = emptyArray()
    private val resolvedTextFonts = Array(STYLE_COUNT) { HashMap<String, Font>() }
    private var fontGeneration: Int = 0

    /**
     * Increments whenever font resolution can produce different fonts.
     */
    val generation: Int
        get() = fontGeneration

    /**
     * Rebuilds cached style variants when [font] changes.
     *
     * @param font base terminal font.
     * @return `true` when cached font state changed.
     */
    fun update(font: Font, fallbackFonts: List<Font>, useSystemFallbackFonts: Boolean): Boolean {
        if (
            font == baseFont &&
            fallbackFonts == fallbackBaseFonts &&
            useSystemFallbackFonts == this.useSystemFallbackFonts
        ) {
            return false
        }

        baseFont = font
        fallbackBaseFonts = fallbackFonts
        this.useSystemFallbackFonts = useSystemFallbackFonts
        styleFonts.fill(null)
        styleFonts[font.style and STYLE_MASK] = font
        fallbackStyleFonts = Array(fallbackFonts.size) { arrayOfNulls(STYLE_COUNT) }
        systemFallbackBaseFonts = if (useSystemFallbackFonts) {
            TerminalSystemFallbackFonts.fontsOrStartLoading()
        } else {
            emptyList()
        }
        systemStyleFonts = Array(systemFallbackBaseFonts.size) { arrayOfNulls(STYLE_COUNT) }
        for (cache in resolvedTextFonts) {
            cache.clear()
        }
        fontGeneration++
        return true
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
            refreshSystemFallbackFonts()
            index = 0
            while (index < systemFallbackBaseFonts.size) {
                val fallback = systemFallbackFont(index, normalizedStyle)
                if (fallback.canDisplayUpTo(text) < 0) {
                    styleResolvedTextFonts[text] = fallback
                    return fallback
                }
                index++
            }
        }

        styleResolvedTextFonts[text] = primary
        return primary
    }

    /**
     * Refreshes asynchronously loaded system fallback fonts, if enabled.
     *
     * @return `true` when font resolution changed.
     */
    fun refreshSystemFallbackFonts(): Boolean {
        if (!useSystemFallbackFonts) return false

        val loadedFonts = TerminalSystemFallbackFonts.fontsOrStartLoading()
        if (loadedFonts == systemFallbackBaseFonts) return false

        systemFallbackBaseFonts = loadedFonts
        systemStyleFonts = Array(loadedFonts.size) { arrayOfNulls(STYLE_COUNT) }
        for (cache in resolvedTextFonts) {
            cache.clear()
        }
        fontGeneration++
        return true
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
        val fallback = systemFallbackBaseFonts[index]
            .deriveFont(normalizedStyle, base.size2D)
        systemStyleFonts[index][normalizedStyle] = fallback
        return fallback
    }

    private companion object {
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
    }
}
