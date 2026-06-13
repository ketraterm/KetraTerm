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
import java.awt.font.GlyphVector
import java.awt.geom.Point2D

/**
 * Bounded cache for ASCII glyph vectors with terminal-cell positions.
 */
internal class TerminalAsciiGlyphVectorCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val glyphPosition = Point2D.Float()
    private val lookupKey = LookupKey()
    private val layouts =
        object : LinkedHashMap<Key, GlyphVector>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, GlyphVector>?): Boolean = size > capacity
        }
    private var fontRenderContext: FontRenderContext? = null

    /**
     * Clears all cached glyph vectors.
     */
    fun clear() {
        fontRenderContext = null
        layouts.clear()
    }

    /**
     * Returns a positioned glyph vector for a run in [chars].
     */
    fun glyphVector(
        chars: CharArray,
        offset: Int,
        length: Int,
        font: Font,
        style: Int,
        cellWidth: Int,
        fontRenderContext: FontRenderContext,
    ): GlyphVector {
        prepare(fontRenderContext)

        lookupKey.update(chars, offset, length, font, style, cellWidth)
        val cached = layouts[lookupKey]
        if (cached != null) return cached

        val storedChars = chars.copyOfRange(offset, offset + length)
        val key = CacheKey(storedChars, font, style, cellWidth)
        val glyphVector = font.createGlyphVector(fontRenderContext, storedChars)
        var glyph = 0
        val glyphCount = glyphVector.numGlyphs
        while (glyph <= glyphCount) {
            glyphPosition.x = glyph * cellWidth.toFloat()
            glyphPosition.y = 0f
            glyphVector.setGlyphPosition(glyph, glyphPosition)
            glyph++
        }
        layouts[key] = glyphVector
        return glyphVector
    }

    private fun prepare(nextFontRenderContext: FontRenderContext) {
        if (nextFontRenderContext == fontRenderContext) return

        fontRenderContext = nextFontRenderContext
        layouts.clear()
    }

    private abstract class Key {
        abstract val font: Font
        abstract val style: Int
        abstract val cellWidth: Int
        abstract val length: Int
        abstract val hash: Int

        abstract fun charAt(index: Int): Char

        final override fun hashCode(): Int = hash

        final override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            if (
                font != other.font ||
                style != other.style ||
                cellWidth != other.cellWidth ||
                length != other.length
            ) {
                return false
            }

            var index = 0
            while (index < length) {
                if (charAt(index) != other.charAt(index)) return false
                index++
            }
            return true
        }
    }

    private class LookupKey : Key() {
        private lateinit var chars: CharArray
        private var offset: Int = 0
        override lateinit var font: Font
        override var style: Int = 0
            private set
        override var cellWidth: Int = 0
            private set
        override var length: Int = 0
            private set
        override var hash: Int = 0
            private set

        fun update(
            chars: CharArray,
            offset: Int,
            length: Int,
            font: Font,
            style: Int,
            cellWidth: Int,
        ) {
            this.chars = chars
            this.offset = offset
            this.length = length
            this.font = font
            this.style = style
            this.cellWidth = cellWidth
            hash = contentHash(chars, offset, length, font, style, cellWidth)
        }

        override fun charAt(index: Int): Char = chars[offset + index]
    }

    private class CacheKey(
        private val chars: CharArray,
        override val font: Font,
        override val style: Int,
        override val cellWidth: Int,
    ) : Key() {
        override val length: Int = chars.size
        override val hash: Int = contentHash(chars, 0, chars.size, font, style, cellWidth)

        override fun charAt(index: Int): Char = chars[index]
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 4096
        private const val LOAD_FACTOR = 0.75f

        private fun contentHash(
            chars: CharArray,
            offset: Int,
            length: Int,
            font: Font,
            style: Int,
            cellWidth: Int,
        ): Int {
            var result = font.hashCode()
            result = 31 * result + style
            result = 31 * result + cellWidth
            result = 31 * result + length
            var index = 0
            while (index < length) {
                result = 31 * result + chars[offset + index].code
                index++
            }
            return result
        }
    }
}
