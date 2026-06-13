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
package io.github.jvterm.render.api

/**
 * Immutable resolved terminal color palette.
 *
 * Colors are stored as packed ARGB integers so painting code can resolve cell
 * foreground and background colors without allocating platform color objects
 * per cell.
 *
 * @property defaultForeground default foreground ARGB color.
 * @property defaultBackground default background ARGB color.
 * @property selectionForeground selection foreground ARGB color.
 * @property selectionBackground selection background ARGB color.
 * @property cursorForeground cursor foreground ARGB color.
 * @property cursorBackground cursor background ARGB color.
 * @property boldAsBright whether indexed ANSI 0..7 foreground colors should use
 * bright variants 8..15 when bold is active.
 * @param indexedColors 256-entry indexed palette in packed ARGB form.
 */
class TerminalColorPalette(
    val defaultForeground: Int = 0xFFFFFFFF.toInt(),
    val defaultBackground: Int = 0xFF000000.toInt(),
    val selectionForeground: Int = 0xFFFFFFFF.toInt(),
    val selectionBackground: Int = 0xFF000000.toInt(),
    val cursorForeground: Int = 0xFF000000.toInt(),
    val cursorBackground: Int = 0xFFFFFFFF.toInt(),
    indexedColors: IntArray = defaultIndexedColors(),
    val boldAsBright: Boolean = true,
) {
    private val indexedColorStorage: IntArray

    init {
        require(indexedColors.size == INDEXED_COLOR_COUNT) {
            "indexedColors must contain $INDEXED_COLOR_COUNT colors, got ${indexedColors.size}"
        }
        indexedColorStorage = indexedColors.copyOf()
    }

    /**
     * Resolves the foreground color for [attrWord].
     *
     * @param attrWord public render attribute word.
     * @return packed ARGB foreground color.
     */
    fun foreground(attrWord: Long): Int {
        if (TerminalRenderAttrs.isInvisible(attrWord)) {
            return background(attrWord)
        }

        val color =
            resolveColor(
                kind = TerminalRenderAttrs.foregroundKind(attrWord),
                value = TerminalRenderAttrs.foregroundValue(attrWord),
                defaultColor = defaultForeground,
                bold = TerminalRenderAttrs.isBold(attrWord),
                applyBoldAsBright = true,
            )

        return if (TerminalRenderAttrs.isInverse(attrWord)) {
            backgroundWithoutInverse(attrWord)
        } else {
            color
        }
    }

    /**
     * Resolves the background color for [attrWord].
     *
     * @param attrWord public render attribute word.
     * @return packed ARGB background color.
     */
    fun background(attrWord: Long): Int =
        if (TerminalRenderAttrs.isInverse(attrWord)) {
            foregroundWithoutInverse(attrWord)
        } else {
            backgroundWithoutInverse(attrWord)
        }

    /**
     * Returns an indexed palette color without exposing mutable palette storage.
     *
     * @param index ANSI indexed color in `0..255`.
     * @return packed ARGB color.
     */
    fun indexedColor(index: Int): Int {
        require(index in 0 until INDEXED_COLOR_COUNT) {
            "indexed color index out of range: $index"
        }
        return indexedColorStorage[index]
    }

    /**
     * Copies the indexed palette into [destination].
     *
     * This is the allocation-free way for hosts to read the full palette.
     *
     * @param destination destination array receiving 256 packed ARGB colors.
     * @param offset first destination index to write.
     */
    fun copyIndexedColorsInto(
        destination: IntArray,
        offset: Int = 0,
    ) {
        require(offset >= 0 && destination.size - offset >= INDEXED_COLOR_COUNT) {
            "destination has insufficient capacity: size=${destination.size}, offset=$offset, required=$INDEXED_COLOR_COUNT"
        }
        indexedColorStorage.copyInto(destination, destinationOffset = offset)
    }

    /**
     * Returns a newly allocated copy of the indexed palette.
     *
     * @return 256-entry indexed palette in packed ARGB form.
     */
    fun toIndexedColorsArray(): IntArray = indexedColorStorage.copyOf()

    /**
     * Creates a palette with selected properties replaced.
     *
     * @param defaultForeground default foreground ARGB color.
     * @param defaultBackground default background ARGB color.
     * @param selectionForeground selection foreground ARGB color.
     * @param selectionBackground selection background ARGB color.
     * @param cursorForeground cursor foreground ARGB color.
     * @param cursorBackground cursor background ARGB color.
     * @param indexedColors 256-entry indexed palette in packed ARGB form.
     * @param boldAsBright whether indexed ANSI 0..7 foreground colors should use
     * bright variants 8..15 when bold is active.
     * @return immutable palette copy.
     */
    fun copy(
        defaultForeground: Int = this.defaultForeground,
        defaultBackground: Int = this.defaultBackground,
        selectionForeground: Int = this.selectionForeground,
        selectionBackground: Int = this.selectionBackground,
        cursorForeground: Int = this.cursorForeground,
        cursorBackground: Int = this.cursorBackground,
        indexedColors: IntArray = indexedColorStorage,
        boldAsBright: Boolean = this.boldAsBright,
    ): TerminalColorPalette =
        TerminalColorPalette(
            defaultForeground = defaultForeground,
            defaultBackground = defaultBackground,
            selectionForeground = selectionForeground,
            selectionBackground = selectionBackground,
            cursorForeground = cursorForeground,
            cursorBackground = cursorBackground,
            indexedColors = indexedColors,
            boldAsBright = boldAsBright,
        )

    private fun foregroundWithoutInverse(attrWord: Long): Int {
        val color =
            resolveColor(
                kind = TerminalRenderAttrs.foregroundKind(attrWord),
                value = TerminalRenderAttrs.foregroundValue(attrWord),
                defaultColor = defaultForeground,
                bold = TerminalRenderAttrs.isBold(attrWord),
                applyBoldAsBright = true,
            )
        return color
    }

    private fun backgroundWithoutInverse(attrWord: Long): Int =
        resolveColor(
            kind = TerminalRenderAttrs.backgroundKind(attrWord),
            value = TerminalRenderAttrs.backgroundValue(attrWord),
            defaultColor = defaultBackground,
            bold = false,
            applyBoldAsBright = false,
        )

    private fun resolveColor(
        kind: Int,
        value: Int,
        defaultColor: Int,
        bold: Boolean,
        applyBoldAsBright: Boolean,
    ): Int =
        when (kind) {
            TerminalRenderColorKind.DEFAULT -> defaultColor
            TerminalRenderColorKind.INDEXED -> {
                val index =
                    if (boldAsBright && applyBoldAsBright && bold && value in 0..7) {
                        value + 8
                    } else {
                        value
                    }
                indexedColorStorage[index]
            }
            TerminalRenderColorKind.RGB -> 0xFF000000.toInt() or value
            else -> defaultColor
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalColorPalette) return false

        return defaultForeground == other.defaultForeground &&
            defaultBackground == other.defaultBackground &&
            selectionForeground == other.selectionForeground &&
            selectionBackground == other.selectionBackground &&
            cursorForeground == other.cursorForeground &&
            cursorBackground == other.cursorBackground &&
            indexedColorStorage.contentEquals(other.indexedColorStorage) &&
            boldAsBright == other.boldAsBright
    }

    override fun hashCode(): Int {
        var result = defaultForeground
        result = 31 * result + defaultBackground
        result = 31 * result + selectionForeground
        result = 31 * result + selectionBackground
        result = 31 * result + cursorForeground
        result = 31 * result + cursorBackground
        result = 31 * result + indexedColorStorage.contentHashCode()
        result = 31 * result + boldAsBright.hashCode()
        return result
    }

    override fun toString(): String =
        "TerminalColorPalette(" +
            "defaultForeground=$defaultForeground, " +
            "defaultBackground=$defaultBackground, " +
            "selectionForeground=$selectionForeground, " +
            "selectionBackground=$selectionBackground, " +
            "cursorForeground=$cursorForeground, " +
            "cursorBackground=$cursorBackground, " +
            "indexedColors=${indexedColorStorage.contentToString()}, " +
            "boldAsBright=$boldAsBright" +
            ")"

    companion object {
        private const val INDEXED_COLOR_COUNT = 256

        /**
         * Builds the default 256-color terminal palette.
         *
         * @return 256 packed ARGB colors covering ANSI 16, color cube, and gray
         * ramp entries.
         */
        @JvmStatic
        fun defaultIndexedColors(): IntArray {
            val colors = IntArray(INDEXED_COLOR_COUNT)
            val ansi16 =
                intArrayOf(
                    0xFF000000.toInt(),
                    0xFF800000.toInt(),
                    0xFF008000.toInt(),
                    0xFF808000.toInt(),
                    0xFF000080.toInt(),
                    0xFF800080.toInt(),
                    0xFF008080.toInt(),
                    0xFFC0C0C0.toInt(),
                    0xFF808080.toInt(),
                    0xFFFF0000.toInt(),
                    0xFF00FF00.toInt(),
                    0xFFFFFF00.toInt(),
                    0xFF0000FF.toInt(),
                    0xFFFF00FF.toInt(),
                    0xFF00FFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                )
            ansi16.copyInto(colors)

            var index = 16
            for (red in 0..5) {
                for (green in 0..5) {
                    for (blue in 0..5) {
                        colors[index++] = rgb(cubeLevel(red), cubeLevel(green), cubeLevel(blue))
                    }
                }
            }

            for (gray in 0..23) {
                val level = 8 + gray * 10
                colors[index++] = rgb(level, level, level)
            }

            return colors
        }

        private fun cubeLevel(value: Int): Int = if (value == 0) 0 else 55 + value * 40

        private fun rgb(
            red: Int,
            green: Int,
            blue: Int,
        ): Int = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }
}
