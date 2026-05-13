package com.gagik.terminal.ui.swing.settings

import com.gagik.terminal.render.api.TerminalRenderAttrs
import com.gagik.terminal.render.api.TerminalRenderColorKind

/**
 * Immutable resolved terminal color palette.
 *
 * Colors are stored as packed ARGB integers so painting code can resolve cell
 * foreground and background colors without allocating AWT [java.awt.Color]
 * instances per cell.
 *
 * @property defaultForeground default foreground ARGB color.
 * @property defaultBackground default background ARGB color.
 * @property selectionForeground selection foreground ARGB color.
 * @property selectionBackground selection background ARGB color.
 * @property cursorForeground cursor foreground ARGB color.
 * @property cursorBackground cursor background ARGB color.
 * @property indexedColors 256-entry indexed palette in packed ARGB form.
 * @property boldAsBright whether indexed ANSI 0..7 foreground colors should use
 * bright variants 8..15 when bold is active.
 */
class TerminalColorPalette(
    val defaultForeground: Int = 0xFFE6E8EF.toInt(),
    val defaultBackground: Int = 0xFF111318.toInt(),
    val selectionForeground: Int = 0xFFFFFFFF.toInt(),
    val selectionBackground: Int = 0xFF2F6FEB.toInt(),
    val cursorForeground: Int = 0xFF111318.toInt(),
    val cursorBackground: Int = 0xFFE6E8EF.toInt(),
    indexedColors: IntArray = defaultIndexedColors(),
    val boldAsBright: Boolean = true,
) {
    private val indexedColorStorage: IntArray

    /**
     * 256-entry indexed palette in packed ARGB form.
     */
    val indexedColors: IntArray
        get() = indexedColorStorage.copyOf()

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

        val color = resolveColor(
            kind = TerminalRenderAttrs.foregroundKind(attrWord),
            value = TerminalRenderAttrs.foregroundValue(attrWord),
            defaultColor = defaultForeground,
            bold = TerminalRenderAttrs.isBold(attrWord),
            applyBoldAsBright = true,
        )

        return if (TerminalRenderAttrs.isInverse(attrWord)) {
            backgroundWithoutInverse(attrWord)
        } else {
            maybeDim(color, attrWord)
        }
    }

    /**
     * Resolves the background color for [attrWord].
     *
     * @param attrWord public render attribute word.
     * @return packed ARGB background color.
     */
    fun background(attrWord: Long): Int {
        return if (TerminalRenderAttrs.isInverse(attrWord)) {
            maybeDim(foregroundWithoutInverse(attrWord), attrWord)
        } else {
            backgroundWithoutInverse(attrWord)
        }
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
    ): TerminalColorPalette {
        return TerminalColorPalette(
            defaultForeground = defaultForeground,
            defaultBackground = defaultBackground,
            selectionForeground = selectionForeground,
            selectionBackground = selectionBackground,
            cursorForeground = cursorForeground,
            cursorBackground = cursorBackground,
            indexedColors = indexedColors,
            boldAsBright = boldAsBright,
        )
    }

    private fun foregroundWithoutInverse(attrWord: Long): Int {
        val color = resolveColor(
            kind = TerminalRenderAttrs.foregroundKind(attrWord),
            value = TerminalRenderAttrs.foregroundValue(attrWord),
            defaultColor = defaultForeground,
            bold = TerminalRenderAttrs.isBold(attrWord),
            applyBoldAsBright = true,
        )
        return maybeDim(color, attrWord)
    }

    private fun backgroundWithoutInverse(attrWord: Long): Int {
        return resolveColor(
            kind = TerminalRenderAttrs.backgroundKind(attrWord),
            value = TerminalRenderAttrs.backgroundValue(attrWord),
            defaultColor = defaultBackground,
            bold = false,
            applyBoldAsBright = false,
        )
    }

    private fun resolveColor(
        kind: Int,
        value: Int,
        defaultColor: Int,
        bold: Boolean,
        applyBoldAsBright: Boolean,
    ): Int {
        return when (kind) {
            TerminalRenderColorKind.DEFAULT -> defaultColor
            TerminalRenderColorKind.INDEXED -> {
                val index = if (boldAsBright && applyBoldAsBright && bold && value in 0..7) {
                    value + 8
                } else {
                    value
                }
                indexedColorStorage[index]
            }
            TerminalRenderColorKind.RGB -> 0xFF000000.toInt() or value
            else -> defaultColor
        }
    }

    private fun maybeDim(color: Int, attrWord: Long): Int {
        if (!TerminalRenderAttrs.isFaint(attrWord)) return color

        val alpha = color and 0xFF000000.toInt()
        val red = ((color ushr 16) and 0xFF) / 2
        val green = ((color ushr 8) and 0xFF) / 2
        val blue = (color and 0xFF) / 2
        return alpha or (red shl 16) or (green shl 8) or blue
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

    override fun toString(): String {
        return "TerminalColorPalette(" +
            "defaultForeground=$defaultForeground, " +
            "defaultBackground=$defaultBackground, " +
            "selectionForeground=$selectionForeground, " +
            "selectionBackground=$selectionBackground, " +
            "cursorForeground=$cursorForeground, " +
            "cursorBackground=$cursorBackground, " +
            "indexedColors=${indexedColorStorage.contentToString()}, " +
            "boldAsBright=$boldAsBright" +
            ")"
    }

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
            val ansi16 = intArrayOf(
                0xFF1D2027.toInt(),
                0xFFC94F6D.toInt(),
                0xFF81B29A.toInt(),
                0xFFE6C07B.toInt(),
                0xFF6EA8FE.toInt(),
                0xFFC678DD.toInt(),
                0xFF56B6C2.toInt(),
                0xFFD8DEE9.toInt(),
                0xFF5C6370.toInt(),
                0xFFE06C75.toInt(),
                0xFF98C379.toInt(),
                0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(),
                0xFFC678DD.toInt(),
                0xFF56B6C2.toInt(),
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

        private fun rgb(red: Int, green: Int, blue: Int): Int {
            return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
    }
}
