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
package io.github.jvterm.core.model

/**
 * Public representation of cell attributes for UI/rendering.
 *
 * [foreground], [background], and [underlineColor] are renderer-facing color
 * descriptors. [underlineColor] uses [CellColor.DEFAULT] to mean "derive
 * from the effective foreground color" unless the renderer has a different
 * product policy.
 *
 * [hyperlinkId] is a numeric handle for OSC 8 hyperlinks. `0` means no link;
 * positive ids are owned by the host or host layer's URL pool.
 *
 * @property foreground Text foreground color.
 * @property background Text background color.
 * @property underlineColor Explicit underline color, or default/foreground.
 * @property bold Bold/intense text weight.
 * @property faint Faint/dim text intensity.
 * @property italic Italic text style.
 * @property underlineStyle Underline shape.
 * @property strikethrough Strikethrough decoration.
 * @property overline Overline decoration.
 * @property blink Blinking text presentation.
 * @property inverse Reverse-video presentation.
 * @property conceal Concealed/hidden text presentation.
 * @property selectiveEraseProtected Whether DEC selective erase skips the cell.
 * @property hyperlinkId OSC 8 hyperlink handle; `0` means no hyperlink.
 */
data class CellAttributes(
    val foreground: CellColor = CellColor.DEFAULT,
    val background: CellColor = CellColor.DEFAULT,
    val underlineColor: CellColor = CellColor.DEFAULT,
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underlineStyle: UnderlineStyle = UnderlineStyle.NONE,
    val strikethrough: Boolean = false,
    val overline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val conceal: Boolean = false,
    val selectiveEraseProtected: Boolean = false,
    val hyperlinkId: Int = 0,
) {
    init {
        require(hyperlinkId >= 0) { "hyperlinkId must be non-negative, was $hyperlinkId" }
    }
}

/**
 * Renderer-facing color descriptor for a cell attribute.
 *
 * [value] is unused for [CellColorKind.DEFAULT], is `0..255` for
 * [CellColorKind.INDEXED], and is `0xRRGGBB` for [CellColorKind.RGB].
 */
data class CellColor(
    val kind: CellColorKind,
    val value: Int = 0,
) {
    init {
        when (kind) {
            CellColorKind.DEFAULT ->
                require(value == 0) {
                    "default color value must be 0, was $value"
                }
            CellColorKind.INDEXED ->
                require(value in 0..255) {
                    "indexed color value must be in 0..255, was $value"
                }
            CellColorKind.RGB ->
                require(value in 0..0xFF_FF_FF) {
                    "RGB color value must be in 0x000000..0xFFFFFF, was $value"
                }
        }
    }

    companion object {
        /** Terminal default color descriptor. */
        val DEFAULT = CellColor(CellColorKind.DEFAULT)

        private val INDEXED_COLORS =
            Array(256) { index ->
                CellColor(CellColorKind.INDEXED, index)
            }

        /**
         * Returns an indexed palette color.
         *
         * @param index Palette index in `0..255`.
         * @return Indexed color descriptor.
         */
        fun indexed(index: Int): CellColor {
            require(index in 0..255) { "indexed color value must be in 0..255, was $index" }
            return INDEXED_COLORS[index]
        }

        /**
         * Creates an RGB color from separate 8-bit channels.
         *
         * @param red Red channel in `0..255`.
         * @param green Green channel in `0..255`.
         * @param blue Blue channel in `0..255`.
         * @return RGB color descriptor.
         */
        fun rgb(
            red: Int,
            green: Int,
            blue: Int,
        ): CellColor {
            require(red in 0..255) { "red must be in 0..255, was $red" }
            require(green in 0..255) { "green must be in 0..255, was $green" }
            require(blue in 0..255) { "blue must be in 0..255, was $blue" }
            return CellColor(CellColorKind.RGB, (red shl 16) or (green shl 8) or blue)
        }

        /**
         * Creates an RGB color from a packed `0xRRGGBB` integer.
         *
         * @param rgb Packed RGB value in `0x000000..0xFFFFFF`.
         * @return RGB color descriptor.
         */
        fun rgb(rgb: Int): CellColor = CellColor(CellColorKind.RGB, rgb)
    }
}

/** Kind tag for [CellColor]. */
enum class CellColorKind {
    /** Terminal default color. */
    DEFAULT,

    /** Indexed palette color in the range `0..255`. */
    INDEXED,

    /** Direct RGB color stored as `0xRRGGBB`. */
    RGB,
}

/**
 * SGR underline style stored in the extended cell attribute word.
 *
 * [sgrCode] is the value used by colon SGR underline forms such as `CSI 4:3 m`.
 */
enum class UnderlineStyle(
    val sgrCode: Int,
) {
    /** No underline. */
    NONE(0),

    /** Single straight underline. */
    SINGLE(1),

    /** Double straight underline. */
    DOUBLE(2),

    /** Curly underline. */
    CURLY(3),

    /** Dotted underline. */
    DOTTED(4),

    /** Dashed underline. */
    DASHED(5),
    ;

    companion object {
        /**
         * Returns the underline style for an SGR underline-style code.
         *
         * @param code SGR underline-style code.
         * @return Matching style, or `null` when [code] is unsupported.
         */
        fun fromSgrCode(code: Int): UnderlineStyle? =
            when (code) {
                NONE.sgrCode -> NONE
                SINGLE.sgrCode -> SINGLE
                DOUBLE.sgrCode -> DOUBLE
                CURLY.sgrCode -> CURLY
                DOTTED.sgrCode -> DOTTED
                DASHED.sgrCode -> DASHED
                else -> null
            }
    }
}
