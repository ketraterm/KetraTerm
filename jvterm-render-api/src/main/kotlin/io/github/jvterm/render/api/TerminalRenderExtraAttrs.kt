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
 * Stable public render extra-attribute word decoder and packer.
 *
 * This word carries less common attributes so renderers that do not need them
 * can pass `null` for `extraAttrWords` and avoid the extra copy channel.
 *
 * The bit layout is:
 *
 * - bits 0..1: underline color kind.
 * - bits 2..25: underline color value.
 * - bit 26: overline.
 * - bits 27..63: reserved and currently zero.
 */
object TerminalRenderExtraAttrs {
    /**
     * Empty extra attributes.
     */
    const val DEFAULT: Long = 0L

    private const val UNDERLINE_KIND_SHIFT = 0
    private const val UNDERLINE_VALUE_SHIFT = 2
    private const val OVERLINE_SHIFT = 26

    private const val COLOR_KIND_MASK = 0x3L
    private const val COLOR_VALUE_MASK = 0xFF_FFFFL
    private const val OVERLINE_MASK = 1L shl OVERLINE_SHIFT

    /**
     * Returns the underline color kind.
     *
     * @param word public render extra-attribute word.
     * @return one of the [TerminalRenderColorKind] constants.
     */
    fun underlineColorKind(word: Long): Int = ((word ushr UNDERLINE_KIND_SHIFT) and COLOR_KIND_MASK).toInt()

    /**
     * Returns the underline color value.
     *
     * @param word public render extra-attribute word.
     * @return zero for default colors, `0..255` for indexed colors, or
     * `0xRRGGBB` for RGB colors.
     */
    fun underlineColorValue(word: Long): Int = ((word ushr UNDERLINE_VALUE_SHIFT) and COLOR_VALUE_MASK).toInt()

    /**
     * Returns whether overline decoration is enabled.
     *
     * @param word public render extra-attribute word.
     * @return `true` when overline is enabled.
     */
    fun isOverline(word: Long): Boolean = word and OVERLINE_MASK != 0L

    /**
     * Packs a public render extra-attribute word.
     *
     * @param underlineColorKind underline color kind.
     * @param underlineColorValue underline color value for [underlineColorKind].
     * @param overline whether overline decoration is enabled.
     * @return packed public render extra-attribute word.
     */
    fun pack(
        underlineColorKind: Int = TerminalRenderColorKind.DEFAULT,
        underlineColorValue: Int = 0,
        overline: Boolean = false,
    ): Long {
        requireColor("underline", underlineColorKind, underlineColorValue)
        var word =
            (underlineColorKind.toLong() shl UNDERLINE_KIND_SHIFT) or
                (underlineColorValue.toLong() shl UNDERLINE_VALUE_SHIFT)
        if (overline) word = word or OVERLINE_MASK
        return word
    }
}
