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
package io.github.ketraterm.render.api

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
 * @property isDark host-declared dark/light theme preference, independent of
 * individual color values. Defaults to the built-in dark fallback; hosts with
 * light or custom themes must supply their preference explicitly.
 * @param indexedColors 256-entry indexed palette in packed ARGB form.
 */
public class TerminalColorPalette private constructor(
    public val defaultForeground: Int,
    public val defaultBackground: Int,
    public val selectionForeground: Int,
    public val selectionBackground: Int,
    public val cursorForeground: Int,
    public val cursorBackground: Int,
    /** Rendering behavior flags from [TerminalColorBehavior], independent of fallback ARGB values. */
    public val colorBehavior: Int,
    // Keep colorBehavior before indexedColors to distinguish the public default-argument bridge in ABI tools.
    indexedColors: IntArray,
    public val boldAsBright: Boolean,
    public val isDark: Boolean,
) {
    public constructor(
        defaultForeground: Int = 0xFFFFFFFF.toInt(),
        defaultBackground: Int = 0xFF000000.toInt(),
        selectionForeground: Int = 0xFFFFFFFF.toInt(),
        selectionBackground: Int = 0xFF000000.toInt(),
        cursorForeground: Int = 0xFF000000.toInt(),
        cursorBackground: Int = 0xFFFFFFFF.toInt(),
        indexedColors: IntArray = defaultIndexedColors(),
        boldAsBright: Boolean = true,
        isDark: Boolean = true,
    ) : this(
        defaultForeground,
        defaultBackground,
        selectionForeground,
        selectionBackground,
        cursorForeground,
        cursorBackground,
        0,
        indexedColors,
        boldAsBright,
        isDark,
    )

    /** Creates the built-in dark palette with host-controlled selection presentation. */
    public constructor() : this(isDark = true)

    private val indexedColorStorage: IntArray

    init {
        require(colorBehavior and TerminalColorBehavior.ALL.inv() == 0) { "Unknown color behavior flags: $colorBehavior" }
        require(
            colorBehavior and TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND == 0 ||
                colorBehavior and TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND != 0,
        ) { "Dynamic selection foreground requires a selection foreground override" }
        require(
            colorBehavior and TerminalColorBehavior.DYNAMIC_SELECTION_BACKGROUND == 0 ||
                colorBehavior and TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND != 0,
        ) { "Dynamic selection background requires a selection background override" }
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
    public fun foreground(attrWord: Long): Int {
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
    public fun background(attrWord: Long): Int =
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
    public fun indexedColor(index: Int): Int {
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
    public fun copyIndexedColorsInto(
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
    public fun toIndexedColorsArray(): IntArray = indexedColorStorage.copyOf()

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
     * @param isDark host theme preference; retained when individual colors change.
     * @return immutable palette copy.
     */
    public fun copy(
        defaultForeground: Int = this.defaultForeground,
        defaultBackground: Int = this.defaultBackground,
        selectionForeground: Int = this.selectionForeground,
        selectionBackground: Int = this.selectionBackground,
        cursorForeground: Int = this.cursorForeground,
        cursorBackground: Int = this.cursorBackground,
        indexedColors: IntArray = indexedColorStorage,
        boldAsBright: Boolean = this.boldAsBright,
        isDark: Boolean = this.isDark,
    ): TerminalColorPalette =
        copyWithColorBehavior(
            colorBehavior = colorBehavior,
            defaultForeground = defaultForeground,
            defaultBackground = defaultBackground,
            selectionForeground = selectionForeground,
            selectionBackground = selectionBackground,
            cursorForeground = cursorForeground,
            cursorBackground = cursorBackground,
            indexedColors = indexedColors,
            boldAsBright = boldAsBright,
            isDark = isDark,
        )

    /**
     * Creates a palette replacing [colorBehavior] and selected colors together.
     *
     * Dynamic cursor and selection colors resolve from the original cell colors;
     * their stored ARGB values remain available as fallbacks. Selection override
     * flags request exact palette colors instead of the renderer's host selection
     * presentation. Dynamic selection requires the corresponding override flag.
     * Unknown flags are rejected. Unspecified properties retain their values.
     */
    public fun copyWithColorBehavior(
        colorBehavior: Int,
        defaultForeground: Int = this.defaultForeground,
        defaultBackground: Int = this.defaultBackground,
        selectionForeground: Int = this.selectionForeground,
        selectionBackground: Int = this.selectionBackground,
        cursorForeground: Int = this.cursorForeground,
        cursorBackground: Int = this.cursorBackground,
        indexedColors: IntArray = indexedColorStorage,
        boldAsBright: Boolean = this.boldAsBright,
        isDark: Boolean = this.isDark,
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
            isDark = isDark,
            colorBehavior = colorBehavior,
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
            boldAsBright == other.boldAsBright &&
            isDark == other.isDark &&
            colorBehavior == other.colorBehavior &&
            indexedColorStorage.contentEquals(other.indexedColorStorage)
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
        result = 31 * result + isDark.hashCode()
        result = 31 * result + colorBehavior
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
            "boldAsBright=$boldAsBright, " +
            "isDark=$isDark, " +
            "colorBehavior=$colorBehavior" +
            ")"

    public companion object {
        private const val INDEXED_COLOR_COUNT = 256

        /**
         * Builds the default 256-color terminal palette.
         *
         * @return 256 packed ARGB colors covering ANSI 16, color cube, and gray
         * ramp entries.
         */
        @JvmStatic
        public fun defaultIndexedColors(): IntArray {
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
