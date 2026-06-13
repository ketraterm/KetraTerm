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
 * Stable public render attribute word decoder and packer.
 *
 * The public ABI is intentionally separate from core's internal attribute
 * storage. The bit layout is:
 *
 * - bits 0..1: foreground color kind.
 * - bits 2..25: foreground color value.
 * - bits 26..27: background color kind.
 * - bits 28..51: background color value.
 * - bit 52: bold.
 * - bit 53: faint.
 * - bit 54: italic.
 * - bits 55..57: underline style.
 * - bit 58: blink.
 * - bit 59: inverse.
 * - bit 60: invisible.
 * - bit 61: strikethrough.
 * - bits 62..63: reserved and currently zero.
 *
 * RGB values are encoded as `0xRRGGBB`, indexed values as `0..255`, and default
 * color values as zero.
 */
object TerminalRenderAttrs {
    /**
     * Default terminal render attributes.
     */
    const val DEFAULT: Long = 0L

    private const val FG_KIND_SHIFT = 0
    private const val FG_VALUE_SHIFT = 2
    private const val BG_KIND_SHIFT = 26
    private const val BG_VALUE_SHIFT = 28
    private const val BOLD_SHIFT = 52
    private const val FAINT_SHIFT = 53
    private const val ITALIC_SHIFT = 54
    private const val UNDERLINE_SHIFT = 55
    private const val BLINK_SHIFT = 58
    private const val INVERSE_SHIFT = 59
    private const val INVISIBLE_SHIFT = 60
    private const val STRIKETHROUGH_SHIFT = 61

    private const val COLOR_KIND_MASK = 0x3L
    private const val COLOR_VALUE_MASK = 0xFF_FFFFL
    private const val UNDERLINE_MASK = 0x7L

    private const val BOLD_MASK = 1L shl BOLD_SHIFT
    private const val FAINT_MASK = 1L shl FAINT_SHIFT
    private const val ITALIC_MASK = 1L shl ITALIC_SHIFT
    private const val BLINK_MASK = 1L shl BLINK_SHIFT
    private const val INVERSE_MASK = 1L shl INVERSE_SHIFT
    private const val INVISIBLE_MASK = 1L shl INVISIBLE_SHIFT
    private const val STRIKETHROUGH_MASK = 1L shl STRIKETHROUGH_SHIFT

    /**
     * Returns the foreground color kind.
     *
     * @param word public render attribute word.
     * @return one of [TerminalRenderColorKind.DEFAULT],
     * [TerminalRenderColorKind.INDEXED], or [TerminalRenderColorKind.RGB].
     */
    fun foregroundKind(word: Long): Int = ((word ushr FG_KIND_SHIFT) and COLOR_KIND_MASK).toInt()

    /**
     * Returns the foreground color value.
     *
     * @param word public render attribute word.
     * @return zero for default colors, `0..255` for indexed colors, or
     * `0xRRGGBB` for RGB colors.
     */
    fun foregroundValue(word: Long): Int = ((word ushr FG_VALUE_SHIFT) and COLOR_VALUE_MASK).toInt()

    /**
     * Returns the background color kind.
     *
     * @param word public render attribute word.
     * @return one of [TerminalRenderColorKind.DEFAULT],
     * [TerminalRenderColorKind.INDEXED], or [TerminalRenderColorKind.RGB].
     */
    fun backgroundKind(word: Long): Int = ((word ushr BG_KIND_SHIFT) and COLOR_KIND_MASK).toInt()

    /**
     * Returns the background color value.
     *
     * @param word public render attribute word.
     * @return zero for default colors, `0..255` for indexed colors, or
     * `0xRRGGBB` for RGB colors.
     */
    fun backgroundValue(word: Long): Int = ((word ushr BG_VALUE_SHIFT) and COLOR_VALUE_MASK).toInt()

    /**
     * Returns whether bold intensity is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when bold is enabled.
     */
    fun isBold(word: Long): Boolean = word and BOLD_MASK != 0L

    /**
     * Returns whether faint intensity is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when faint is enabled.
     */
    fun isFaint(word: Long): Boolean = word and FAINT_MASK != 0L

    /**
     * Returns whether italic style is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when italic is enabled.
     */
    fun isItalic(word: Long): Boolean = word and ITALIC_MASK != 0L

    /**
     * Returns the underline style.
     *
     * @param word public render attribute word.
     * @return one of the [TerminalRenderUnderline] style constants.
     */
    fun underlineStyle(word: Long): Int = ((word ushr UNDERLINE_SHIFT) and UNDERLINE_MASK).toInt()

    /**
     * Returns whether blink style is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when blink is enabled.
     */
    fun isBlink(word: Long): Boolean = word and BLINK_MASK != 0L

    /**
     * Returns whether inverse video is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when inverse video is enabled.
     */
    fun isInverse(word: Long): Boolean = word and INVERSE_MASK != 0L

    /**
     * Returns whether invisible text style is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when invisible text is enabled.
     */
    fun isInvisible(word: Long): Boolean = word and INVISIBLE_MASK != 0L

    /**
     * Returns whether strikethrough decoration is enabled.
     *
     * @param word public render attribute word.
     * @return `true` when strikethrough is enabled.
     */
    fun isStrikethrough(word: Long): Boolean = word and STRIKETHROUGH_MASK != 0L

    /**
     * Packs a public render attribute word.
     *
     * This helper validates the public ABI ranges and keeps test and core
     * translator code from duplicating bit arithmetic.
     *
     * @param foregroundKind foreground color kind.
     * @param foregroundValue foreground color value for [foregroundKind].
     * @param backgroundKind background color kind.
     * @param backgroundValue background color value for [backgroundKind].
     * @param bold whether bold intensity is enabled.
     * @param faint whether faint intensity is enabled.
     * @param italic whether italic style is enabled.
     * @param underlineStyle underline style constant.
     * @param blink whether blink style is enabled.
     * @param inverse whether inverse video is enabled.
     * @param invisible whether invisible text is enabled.
     * @param strikethrough whether strikethrough decoration is enabled.
     * @return packed public render attribute word.
     */
    fun pack(
        foregroundKind: Int = TerminalRenderColorKind.DEFAULT,
        foregroundValue: Int = 0,
        backgroundKind: Int = TerminalRenderColorKind.DEFAULT,
        backgroundValue: Int = 0,
        bold: Boolean = false,
        faint: Boolean = false,
        italic: Boolean = false,
        underlineStyle: Int = TerminalRenderUnderline.NONE,
        blink: Boolean = false,
        inverse: Boolean = false,
        invisible: Boolean = false,
        strikethrough: Boolean = false,
    ): Long {
        requireColor("foreground", foregroundKind, foregroundValue)
        requireColor("background", backgroundKind, backgroundValue)
        require(underlineStyle in TerminalRenderUnderline.NONE..TerminalRenderUnderline.DASHED) {
            "underlineStyle out of range: $underlineStyle"
        }

        var word =
            (foregroundKind.toLong() shl FG_KIND_SHIFT) or
                (foregroundValue.toLong() shl FG_VALUE_SHIFT) or
                (backgroundKind.toLong() shl BG_KIND_SHIFT) or
                (backgroundValue.toLong() shl BG_VALUE_SHIFT) or
                (underlineStyle.toLong() shl UNDERLINE_SHIFT)

        if (bold) word = word or BOLD_MASK
        if (faint) word = word or FAINT_MASK
        if (italic) word = word or ITALIC_MASK
        if (blink) word = word or BLINK_MASK
        if (inverse) word = word or INVERSE_MASK
        if (invisible) word = word or INVISIBLE_MASK
        if (strikethrough) word = word or STRIKETHROUGH_MASK
        return word
    }
}
