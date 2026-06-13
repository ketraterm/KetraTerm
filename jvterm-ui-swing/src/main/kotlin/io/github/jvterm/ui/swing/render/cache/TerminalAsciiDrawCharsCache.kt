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

import java.awt.Font
import java.awt.font.FontRenderContext
import java.util.*

/**
 * Caches whether Java2D's natural ASCII advances match terminal cell advances.
 *
 * When every printable ASCII glyph in a style advances by exactly one cell,
 * `Graphics2D.drawChars` is the cheapest correct path. If any glyph has a
 * different advance, the renderer must use positioned glyph vectors so glyph
 * origins stay pinned to terminal columns.
 */
internal class TerminalAsciiDrawCharsCache {
    private val fonts = arrayOfNulls<Font>(STYLE_COUNT)
    private val contexts = arrayOfNulls<FontRenderContext>(STYLE_COUNT)
    private val cellWidths = IntArray(STYLE_COUNT)
    private val compatible = BooleanArray(STYLE_COUNT)
    private val probe = CharArray(1)

    /**
     * Clears cached compatibility decisions after font settings change.
     */
    fun clear() {
        Arrays.fill(fonts, null)
        Arrays.fill(contexts, null)
        Arrays.fill(cellWidths, 0)
        Arrays.fill(compatible, false)
    }

    /**
     * Returns true when [font] can use `drawChars` for printable ASCII cells.
     */
    fun canDrawChars(
        font: Font,
        style: Int,
        cellWidth: Int,
        fontRenderContext: FontRenderContext,
    ): Boolean {
        val normalizedStyle = style and STYLE_MASK
        if (
            fonts[normalizedStyle] == font &&
            contexts[normalizedStyle] == fontRenderContext &&
            cellWidths[normalizedStyle] == cellWidth
        ) {
            return compatible[normalizedStyle]
        }

        val nextCompatible = computeCompatibility(font, cellWidth, fontRenderContext)
        fonts[normalizedStyle] = font
        contexts[normalizedStyle] = fontRenderContext
        cellWidths[normalizedStyle] = cellWidth
        compatible[normalizedStyle] = nextCompatible
        return nextCompatible
    }

    private fun computeCompatibility(
        font: Font,
        cellWidth: Int,
        fontRenderContext: FontRenderContext,
    ): Boolean {
        var codepoint = FIRST_PRINTABLE_ASCII
        while (codepoint <= LAST_PRINTABLE_ASCII) {
            probe[0] = codepoint.toChar()
            val glyphVector = font.createGlyphVector(fontRenderContext, probe)
            if (glyphVector.getGlyphPosition(1).x != cellWidth.toDouble()) {
                return false
            }
            codepoint++
        }
        return true
    }

    private companion object {
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
        private const val FIRST_PRINTABLE_ASCII = 0x20
        private const val LAST_PRINTABLE_ASCII = 0x7e
    }
}
