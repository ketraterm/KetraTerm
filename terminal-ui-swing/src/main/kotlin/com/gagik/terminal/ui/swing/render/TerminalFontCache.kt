package com.gagik.terminal.ui.swing.render

import java.awt.Font

/**
 * Caches terminal font style variants for one settings snapshot.
 */
internal class TerminalFontCache {
    private var baseFont: Font? = null
    private val styleFonts = arrayOfNulls<Font>(STYLE_COUNT)

    /**
     * Rebuilds cached style variants when [font] changes.
     *
     * @param font base terminal font.
     */
    fun update(font: Font) {
        if (font == baseFont) return

        baseFont = font
        styleFonts.fill(null)
        styleFonts[font.style and STYLE_MASK] = font
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

    private companion object {
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
    }
}
