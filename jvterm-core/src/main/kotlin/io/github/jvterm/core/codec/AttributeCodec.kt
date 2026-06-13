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
package io.github.jvterm.core.codec

import io.github.jvterm.core.model.CellAttributes
import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.CellColorKind
import io.github.jvterm.core.model.UnderlineStyle

/**
 * Encodes terminal cell attributes into two compact Long words.
 *
 * Primary word:
 * - bits `0..25` foreground tagged color
 * - bits `26..51` background tagged color
 * - bit `52` bold
 * - bit `53` italic
 * - bit `54` inverse/reverse-video
 * - bit `55` blink
 * - bit `56` faint/dim
 * - bit `57` selective-erase protection (DECSCA)
 *
 * Extended word:
 * - bits `0..25` underline tagged color
 * - bits `26..28` underline style
 * - bit `29` strikethrough
 * - bit `30` overline
 * - bit `31` conceal/hidden
 * - bits `32..63` hyperlink id
 */
internal object AttributeCodec {
    /** Maximum color value for standard ANSI colors (0 = default, 1-16 = ANSI). */
    const val MAX_ANSI_COLOR = 16

    /** Maximum indexed color code value (0 = default, 1-256 = indexed palette colors). */
    const val MAX_COLOR = 256

    /** Maximum xterm-style indexed palette value. */
    const val MAX_INDEXED_COLOR = 255

    const val DEFAULT_ATTR: Long = 0L
    const val DEFAULT_EXTENDED_ATTR: Long = 0L

    /** Core-internal color kind for terminal default color slots. */
    const val COLOR_KIND_DEFAULT = 0

    /** Core-internal color kind for indexed palette color slots. */
    const val COLOR_KIND_INDEXED = 1

    /** Core-internal color kind for direct RGB color slots. */
    const val COLOR_KIND_RGB = 2

    private const val COLOR_VALUE_MASK = 0xFF_FF_FF
    private const val COLOR_KIND_SHIFT = 24
    private const val COLOR_SLOT_MASK = (1L shl 26) - 1L

    private const val BG_SHIFT = 26
    private const val BOLD_BIT = 52
    private const val ITALIC_BIT = 53
    private const val INVERSE_BIT = 54
    private const val BLINK_BIT = 55
    private const val FAINT_BIT = 56
    private const val PROTECTED_BIT = 57

    private const val UNDERLINE_STYLE_SHIFT = 26
    private const val UNDERLINE_STYLE_MASK = 0b111L
    private const val STRIKETHROUGH_BIT = 29
    private const val OVERLINE_BIT = 30
    private const val CONCEAL_BIT = 31
    private const val HYPERLINK_ID_SHIFT = 32

    fun pack(
        fg: Int,
        bg: Int,
        bold: Boolean = false,
        faint: Boolean = false,
        italic: Boolean = false,
        blink: Boolean = false,
        inverse: Boolean = false,
        protected: Boolean = false,
    ): Long {
        require(fg in 0..MAX_COLOR) { "fg must be in 0..$MAX_COLOR, was $fg" }
        require(bg in 0..MAX_COLOR) { "bg must be in 0..$MAX_COLOR, was $bg" }

        var v = encodeColorCode(fg).toLong()
        v = v or (encodeColorCode(bg).toLong() shl BG_SHIFT)
        return v or packFlags(bold, faint, italic, blink, inverse, protected)
    }

    fun packColors(
        foreground: CellColor = CellColor.DEFAULT,
        background: CellColor = CellColor.DEFAULT,
        bold: Boolean = false,
        faint: Boolean = false,
        italic: Boolean = false,
        blink: Boolean = false,
        inverse: Boolean = false,
        protected: Boolean = false,
    ): Long {
        var v = encodeColor(foreground).toLong()
        v = v or (encodeColor(background).toLong() shl BG_SHIFT)
        return v or packFlags(bold, faint, italic, blink, inverse, protected)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun packFlags(
        bold: Boolean,
        faint: Boolean,
        italic: Boolean,
        blink: Boolean,
        inverse: Boolean,
        protected: Boolean,
    ): Long {
        var v = 0L
        if (bold) v = v or (1L shl BOLD_BIT)
        if (italic) v = v or (1L shl ITALIC_BIT)
        if (inverse) v = v or (1L shl INVERSE_BIT)
        if (blink) v = v or (1L shl BLINK_BIT)
        if (faint) v = v or (1L shl FAINT_BIT)
        if (protected) v = v or (1L shl PROTECTED_BIT)
        return v
    }

    fun packExtended(
        underlineColor: Int = 0,
        underlineStyle: UnderlineStyle = UnderlineStyle.NONE,
        strikethrough: Boolean = false,
        overline: Boolean = false,
        conceal: Boolean = false,
        hyperlinkId: Int = 0,
    ): Long {
        require(underlineColor in 0..MAX_COLOR) {
            "underlineColor must be in 0..$MAX_COLOR, was $underlineColor"
        }
        return packExtendedColors(
            underlineColor = colorFromCode(underlineColor),
            underlineStyle = underlineStyle,
            strikethrough = strikethrough,
            overline = overline,
            conceal = conceal,
            hyperlinkId = hyperlinkId,
        )
    }

    fun packExtendedColors(
        underlineColor: CellColor = CellColor.DEFAULT,
        underlineStyle: UnderlineStyle = UnderlineStyle.NONE,
        strikethrough: Boolean = false,
        overline: Boolean = false,
        conceal: Boolean = false,
        hyperlinkId: Int = 0,
    ): Long {
        require(hyperlinkId >= 0) { "hyperlinkId must be non-negative, was $hyperlinkId" }
        var v = encodeColor(underlineColor).toLong()
        v = v or (underlineStyle.sgrCode.toLong() shl UNDERLINE_STYLE_SHIFT)
        if (strikethrough) v = v or (1L shl STRIKETHROUGH_BIT)
        if (overline) v = v or (1L shl OVERLINE_BIT)
        if (conceal) v = v or (1L shl CONCEAL_BIT)
        v = v or (hyperlinkId.toLong() shl HYPERLINK_ID_SHIFT)
        return v
    }

    fun foreground(v: Long): Int = colorCode((v and COLOR_SLOT_MASK).toInt())

    fun background(v: Long): Int = colorCode(((v ushr BG_SHIFT) and COLOR_SLOT_MASK).toInt())

    fun underline(v: Long): Int = colorCode((v and COLOR_SLOT_MASK).toInt())

    fun foregroundColorKind(v: Long): Int = colorKind((v and COLOR_SLOT_MASK).toInt())

    fun foregroundColorValue(v: Long): Int = colorValue((v and COLOR_SLOT_MASK).toInt())

    fun backgroundColorKind(v: Long): Int = colorKind(((v ushr BG_SHIFT) and COLOR_SLOT_MASK).toInt())

    fun backgroundColorValue(v: Long): Int = colorValue(((v ushr BG_SHIFT) and COLOR_SLOT_MASK).toInt())

    fun underlineColorKind(v: Long): Int = colorKind((v and COLOR_SLOT_MASK).toInt())

    fun underlineColorValue(v: Long): Int = colorValue((v and COLOR_SLOT_MASK).toInt())

    fun foregroundColor(v: Long): CellColor = decodeColor((v and COLOR_SLOT_MASK).toInt())

    fun backgroundColor(v: Long): CellColor = decodeColor(((v ushr BG_SHIFT) and COLOR_SLOT_MASK).toInt())

    fun underlineColor(v: Long): CellColor = decodeColor((v and COLOR_SLOT_MASK).toInt())

    fun isBold(v: Long): Boolean = v and (1L shl BOLD_BIT) != 0L

    fun isFaint(v: Long): Boolean = v and (1L shl FAINT_BIT) != 0L

    fun isItalic(v: Long): Boolean = v and (1L shl ITALIC_BIT) != 0L

    fun isInverse(v: Long): Boolean = v and (1L shl INVERSE_BIT) != 0L

    fun isBlink(v: Long): Boolean = v and (1L shl BLINK_BIT) != 0L

    fun isProtected(v: Long): Boolean = v and (1L shl PROTECTED_BIT) != 0L

    fun underlineStyle(v: Long): UnderlineStyle {
        val code = ((v ushr UNDERLINE_STYLE_SHIFT) and UNDERLINE_STYLE_MASK).toInt()
        return UnderlineStyle.fromSgrCode(code) ?: UnderlineStyle.NONE
    }

    fun isStrikethrough(v: Long): Boolean = v and (1L shl STRIKETHROUGH_BIT) != 0L

    fun isOverline(v: Long): Boolean = v and (1L shl OVERLINE_BIT) != 0L

    fun isConceal(v: Long): Boolean = v and (1L shl CONCEAL_BIT) != 0L

    fun hyperlinkId(v: Long): Int = (v ushr HYPERLINK_ID_SHIFT).toInt()

    fun withProtected(
        v: Long,
        enabled: Boolean,
    ): Long = if (enabled) v or (1L shl PROTECTED_BIT) else v and (1L shl PROTECTED_BIT).inv()

    fun withHyperlinkId(
        v: Long,
        hyperlinkId: Int,
    ): Long {
        require(hyperlinkId >= 0) { "hyperlinkId must be non-negative, was $hyperlinkId" }
        return (v and ((1L shl HYPERLINK_ID_SHIFT) - 1L)) or (hyperlinkId.toLong() shl HYPERLINK_ID_SHIFT)
    }

    fun sgrResetPrimary(v: Long): Long = if (isProtected(v)) 1L shl PROTECTED_BIT else DEFAULT_ATTR

    fun sgrResetExtended(v: Long): Long = hyperlinkId(v).toLong() shl HYPERLINK_ID_SHIFT

    fun unpack(
        primary: Long,
        extended: Long = DEFAULT_EXTENDED_ATTR,
    ): CellAttributes =
        CellAttributes(
            foreground = foregroundColor(primary),
            background = backgroundColor(primary),
            underlineColor = underlineColor(extended),
            bold = isBold(primary),
            faint = isFaint(primary),
            italic = isItalic(primary),
            underlineStyle = underlineStyle(extended),
            strikethrough = isStrikethrough(extended),
            overline = isOverline(extended),
            blink = isBlink(primary),
            inverse = isInverse(primary),
            conceal = isConceal(extended),
            selectiveEraseProtected = isProtected(primary),
            hyperlinkId = hyperlinkId(extended),
        )

    private fun encodeColorCode(code: Int): Int =
        if (code == 0) {
            COLOR_KIND_DEFAULT shl COLOR_KIND_SHIFT
        } else {
            (COLOR_KIND_INDEXED shl COLOR_KIND_SHIFT) or (code - 1)
        }

    private fun colorFromCode(code: Int): CellColor = if (code == 0) CellColor.DEFAULT else CellColor.indexed(code - 1)

    private fun encodeColor(color: CellColor): Int {
        val kind =
            when (color.kind) {
                CellColorKind.DEFAULT -> COLOR_KIND_DEFAULT
                CellColorKind.INDEXED -> COLOR_KIND_INDEXED
                CellColorKind.RGB -> COLOR_KIND_RGB
            }
        return (kind shl COLOR_KIND_SHIFT) or color.value
    }

    private fun decodeColor(encoded: Int): CellColor =
        when (colorKind(encoded)) {
            COLOR_KIND_INDEXED -> CellColor.indexed(colorValue(encoded))
            COLOR_KIND_RGB -> CellColor.rgb(colorValue(encoded))
            else -> CellColor.DEFAULT
        }

    private fun colorKind(encoded: Int): Int =
        when ((encoded ushr COLOR_KIND_SHIFT) and 0b11) {
            COLOR_KIND_INDEXED -> COLOR_KIND_INDEXED
            COLOR_KIND_RGB -> COLOR_KIND_RGB
            else -> COLOR_KIND_DEFAULT
        }

    private fun colorValue(encoded: Int): Int =
        when (colorKind(encoded)) {
            COLOR_KIND_INDEXED -> encoded and 0xFF
            COLOR_KIND_RGB -> encoded and COLOR_VALUE_MASK
            else -> 0
        }

    private fun colorCode(encoded: Int): Int {
        val kind = colorKind(encoded)
        val value = colorValue(encoded)
        return if (kind == COLOR_KIND_INDEXED) value + 1 else 0
    }
}
