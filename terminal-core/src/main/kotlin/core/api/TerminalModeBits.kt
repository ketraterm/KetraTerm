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
package com.gagik.core.api

/**
 * Public contract for the packed terminal mode bit layout.
 *
 * The constants in this object define the bits inside snapshots returned by
 * [TerminalInputState.getInputModeBits]. Core owns the values, while input and
 * rendering code may read them through the helper methods without depending on
 * core internals.
 */
object TerminalModeBits {
    /** Insert/replace mode flag (IRM). */
    const val INSERT_MODE: Long = 1L shl 0

    /** Auto-wrap mode flag (DECAWM). */
    const val AUTO_WRAP: Long = 1L shl 1

    /** Application cursor keys mode flag (DECCKM). */
    const val APPLICATION_CURSOR_KEYS: Long = 1L shl 2

    /** Application keypad mode flag (DECNKM). */
    const val APPLICATION_KEYPAD: Long = 1L shl 3

    /** Origin mode flag (DECOM). */
    const val ORIGIN_MODE: Long = 1L shl 4

    /** New-line mode flag (LNM). */
    const val NEW_LINE_MODE: Long = 1L shl 5

    /** Left/right margin mode flag (DECLRMM). */
    const val LEFT_RIGHT_MARGIN_MODE: Long = 1L shl 6

    /** Reverse-video presentation flag (DECSCNM). */
    const val REVERSE_VIDEO: Long = 1L shl 7

    /** Cursor visibility presentation flag. */
    const val CURSOR_VISIBLE: Long = 1L shl 8

    /** Cursor blinking presentation flag. */
    const val CURSOR_BLINKING: Long = 1L shl 9

    /** Bracketed paste reporting flag. */
    const val BRACKETED_PASTE: Long = 1L shl 10

    /** Focus in/out reporting flag. */
    const val FOCUS_REPORTING: Long = 1L shl 11

    /** Ambiguous-width Unicode policy flag. */
    const val AMBIGUOUS_WIDE: Long = 1L shl 12

    /** Synchronized output mode flag (?2026). */
    const val SYNCHRONIZED_OUTPUT: Long = 1L shl 13

    /** Starting bit for the packed modify-other-keys mode field. */
    const val MODIFY_OTHER_KEYS_SHIFT: Int = 16

    /** Width in bits of the packed modify-other-keys mode field. */
    const val MODIFY_OTHER_KEYS_WIDTH: Int = 3

    /** Bit mask for the packed modify-other-keys mode field. */
    const val MODIFY_OTHER_KEYS_MASK: Long =
        ((1L shl MODIFY_OTHER_KEYS_WIDTH) - 1L) shl MODIFY_OTHER_KEYS_SHIFT

    /** Starting bit for the packed mouse tracking mode field. */
    const val MOUSE_TRACKING_SHIFT: Int = 20

    /** Width in bits of the packed mouse tracking mode field. */
    const val MOUSE_TRACKING_WIDTH: Int = 4

    /** Bit mask for the packed mouse tracking mode field. */
    const val MOUSE_TRACKING_MASK: Long =
        ((1L shl MOUSE_TRACKING_WIDTH) - 1L) shl MOUSE_TRACKING_SHIFT

    /** Starting bit for the packed mouse encoding mode field. */
    const val MOUSE_ENCODING_SHIFT: Int = 24

    /** Width in bits of the packed mouse encoding mode field. */
    const val MOUSE_ENCODING_WIDTH: Int = 2

    /** Bit mask for the packed mouse encoding mode field. */
    const val MOUSE_ENCODING_MASK: Long =
        ((1L shl MOUSE_ENCODING_WIDTH) - 1L) shl MOUSE_ENCODING_SHIFT

    /** Starting bit for the packed format-other-keys mode field. */
    const val FORMAT_OTHER_KEYS_SHIFT: Int = 26

    /** Width in bits of the packed format-other-keys mode field. */
    const val FORMAT_OTHER_KEYS_WIDTH: Int = 2

    /** Bit mask for the packed format-other-keys mode field. */
    const val FORMAT_OTHER_KEYS_MASK: Long =
        ((1L shl FORMAT_OTHER_KEYS_WIDTH) - 1L) shl FORMAT_OTHER_KEYS_SHIFT

    /** Starting bit for the packed Kitty keyboard progressive-enhancement flags. */
    const val KITTY_KEYBOARD_FLAGS_SHIFT: Int = 28

    /** Width in bits of the packed Kitty keyboard progressive-enhancement flags. */
    const val KITTY_KEYBOARD_FLAGS_WIDTH: Int = 5

    /** Bit mask for the packed Kitty keyboard progressive-enhancement flags. */
    const val KITTY_KEYBOARD_FLAGS_MASK: Long =
        ((1L shl KITTY_KEYBOARD_FLAGS_WIDTH) - 1L) shl KITTY_KEYBOARD_FLAGS_SHIFT

    /**
     * Returns true when [flag] is set in [bits].
     *
     * @param bits packed mode snapshot.
     * @param flag one boolean flag constant from this object.
     */
    @JvmStatic
    fun hasFlag(
        bits: Long,
        flag: Long,
    ): Boolean = (bits and flag) != 0L

    /**
     * Extracts a packed integer field from [bits].
     *
     * @param bits packed mode snapshot.
     * @param mask mask covering the packed field.
     * @param shift number of low bits to shift away after masking.
     */
    @JvmStatic
    fun packedValue(
        bits: Long,
        mask: Long,
        shift: Int,
    ): Int = ((bits and mask) ushr shift).toInt()

    /**
     * Returns [bits] with one packed integer field replaced by [value].
     *
     * @param bits original packed mode snapshot.
     * @param mask mask covering the packed field.
     * @param shift number of bits to shift [value] before storing it.
     * @param value non-negative integer value to store in the field.
     * @throws IllegalArgumentException when [value] does not fit inside [mask].
     */
    @JvmStatic
    fun withPackedValue(
        bits: Long,
        mask: Long,
        shift: Int,
        value: Int,
    ): Long {
        val shifted = value.toLong() shl shift
        require((shifted and mask.inv()) == 0L) {
            "packed value $value does not fit mask ${mask.toString(16)}"
        }
        return (bits and mask.inv()) or shifted
    }
}
