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
package com.gagik.parser.unicode

/**
 * Seed grapheme break table.
 *
 * Contains a curated subset of UAX #29 ranges for structural terminal parsing.
 * In a production build, this file should be fully generated from Unicode data files.
 */
internal object GeneratedGraphemeBreakTable {
    private val EXTEND_RANGES: IntArray =
        intArrayOf(
            0x0300,
            0x036F,
            0x0483,
            0x0489,
            0x0591,
            0x05BD,
            0x05BF,
            0x05BF,
            0x05C1,
            0x05C2,
            0x05C4,
            0x05C5,
            0x05C7,
            0x05C7,
            0x0610,
            0x061A,
            0x064B,
            0x065F,
            0x0670,
            0x0670,
            0x06D6,
            0x06DC,
            0x06DF,
            0x06E4,
            0x06E7,
            0x06E8,
            0x06EA,
            0x06ED,
            0x0E31,
            0x0E31,
            0x0E34,
            0x0E3A,
            0x0E47,
            0x0E4E,
            0x0EB1,
            0x0EB1,
            0x0EB4,
            0x0EBC,
            0x0EC8,
            0x0ECD,
            0x1AB0,
            0x1AFF,
            0x1DC0,
            0x1DFF,
            0x20D0,
            0x20FF,
            0xFE00,
            0xFE0F,
            0xFE20,
            0xFE2F,
            0xE0100,
            0xE01EF,
        )

    private val SPACING_MARK_RANGES: IntArray =
        intArrayOf(
            0x0903,
            0x0903,
            0x093B,
            0x093B,
            0x093E,
            0x0940,
            0x0949,
            0x094C,
            0x0982,
            0x0983,
            0x0BBE,
            0x0BBF,
            0x0BC1,
            0x0BC2,
        )

    private val PREPEND_RANGES: IntArray =
        intArrayOf(
            0x0600,
            0x0605,
            0x06DD,
            0x06DD,
            0x070F,
            0x070F,
            0x08E2,
            0x08E2,
        )

    private val EXTENDED_PICTOGRAPHIC_RANGES: IntArray =
        intArrayOf(
            0x00A9,
            0x00A9,
            0x00AE,
            0x00AE,
            0x203C,
            0x203C,
            0x2049,
            0x2049,
            0x2122,
            0x2122,
            0x2139,
            0x2139,
            0x2194,
            0x2199,
            0x21A9,
            0x21AA,
            0x231A,
            0x231B,
            0x2328,
            0x2328,
            0x23CF,
            0x23CF,
            0x23E9,
            0x23F3,
            0x23F8,
            0x23FA,
            0x24C2,
            0x24C2,
            0x25AA,
            0x25AB,
            0x25B6,
            0x25B6,
            0x25C0,
            0x25C0,
            0x25FB,
            0x25FE,
            0x2600,
            0x27BF,
            0x2934,
            0x2935,
            0x2B05,
            0x2B55,
            0x3030,
            0x3030,
            0x303D,
            0x303D,
            0x3297,
            0x3297,
            0x3299,
            0x3299,
            0x1F000,
            0x1FAFF,
        )

    @JvmStatic
    fun graphemeBreakClass(codepoint: Int): Int =
        when {
            codepoint == 0x000D -> UnicodeClass.GRAPHEME_CR
            codepoint == 0x000A -> UnicodeClass.GRAPHEME_LF
            isControl(codepoint) -> UnicodeClass.GRAPHEME_CONTROL
            contains(PREPEND_RANGES, codepoint) -> UnicodeClass.GRAPHEME_PREPEND
            codepoint == 0x200D -> UnicodeClass.GRAPHEME_ZWJ
            codepoint in 0x1F1E6..0x1F1FF -> UnicodeClass.GRAPHEME_REGIONAL_INDICATOR
            codepoint in 0x1100..0x115F || codepoint in 0xA960..0xA97C -> UnicodeClass.GRAPHEME_L
            codepoint in 0x1160..0x11A7 || codepoint in 0xD7B0..0xD7C6 -> UnicodeClass.GRAPHEME_V
            codepoint in 0x11A8..0x11FF || codepoint in 0xD7CB..0xD7FB -> UnicodeClass.GRAPHEME_T
            isHangulLv(codepoint) -> UnicodeClass.GRAPHEME_LV
            codepoint in 0xAC00..0xD7A3 -> UnicodeClass.GRAPHEME_LVT
            contains(EXTEND_RANGES, codepoint) -> UnicodeClass.GRAPHEME_EXTEND
            contains(SPACING_MARK_RANGES, codepoint) -> UnicodeClass.GRAPHEME_SPACING_MARK
            else -> UnicodeClass.GRAPHEME_OTHER
        }

    @JvmStatic
    fun isExtendedPictographic(codepoint: Int): Boolean {
        if (codepoint in 0x1F1E6..0x1F1FF) {
            return false
        }
        return contains(EXTENDED_PICTOGRAPHIC_RANGES, codepoint)
    }

    private fun isControl(codepoint: Int): Boolean = codepoint in 0x0000..0x001F || codepoint in 0x007F..0x009F

    private fun isHangulLv(codepoint: Int): Boolean = codepoint in 0xAC00..0xD7A3 && ((codepoint - 0xAC00) % 28) == 0

    private fun contains(
        ranges: IntArray,
        codepoint: Int,
    ): Boolean {
        var low = 0
        var high = (ranges.size / 2) - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]
            if (codepoint < start) {
                high = mid - 1
            } else if (codepoint > end) {
                low = mid + 1
            } else {
                return true
            }
        }

        return false
    }
}
