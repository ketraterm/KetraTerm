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
package io.github.jvterm.core.util

import java.util.*

/**
 * High-performance Unicode East Asian Width (EAW) and Zero-Width calculator.
 * Uses a triple-bitset architecture for O(1) lookups on the BMP and SMP.
 */
internal object UnicodeWidth {
    private val wide = BitSet(GeneratedUnicodeWidthTable.BITSET_LIMIT)
    private val zero = BitSet(GeneratedUnicodeWidthTable.BITSET_LIMIT)
    private val ambiguous = BitSet(GeneratedUnicodeWidthTable.BITSET_LIMIT)
    private val terminalCellGraphics = BitSet(GeneratedUnicodeWidthTable.BITSET_LIMIT)
    private val emojiVariationBase = BitSet(GeneratedUnicodeWidthTable.BITSET_LIMIT)

    init {
        populate(wide, GeneratedUnicodeWidthTable.WIDE_RANGES)
        populate(zero, GeneratedUnicodeWidthTable.ZERO_RANGES)
        populate(ambiguous, GeneratedUnicodeWidthTable.AMBIGUOUS_RANGES)
        populate(terminalCellGraphics, GeneratedUnicodeWidthTable.TERMINAL_CELL_GRAPHIC_RANGES)
        populate(emojiVariationBase, GeneratedUnicodeWidthTable.EMOJI_VARIATION_BASE_RANGES)
    }

    /**
     * Calculates the rendering width of a codepoint on the terminal grid.
     * @param cp The Unicode codepoint.
     * @param ambiguousAsWide Runtime configuration defining how to treat 'Ambiguous' East Asian Width characters.
     * @return 0 (Zero-width), 1 (Narrow), or 2 (Wide).
     */
    fun calculate(
        cp: Int,
        ambiguousAsWide: Boolean,
    ): Int {
        // Fast-path: Standard ASCII Printable (99% of text)
        if (cp in 0x20..0x7E) return 1

        // Fast-path: Control Characters (C0, DEL, C1)
        if (cp < 0x20 || cp in 0x7F..0x9F) return 0

        // O(1) BMP/SMP Lookup using BitSets
        if (cp < GeneratedUnicodeWidthTable.BITSET_LIMIT) {
            if (zero.get(cp)) return 0
            if (terminalCellGraphics.get(cp)) return 1
            if (wide.get(cp)) return 2
            if (ambiguous.get(cp)) return if (ambiguousAsWide) 2 else 1
            return 1
        }

        // O(log N) Astral Lookup
        if (binarySearch(GeneratedUnicodeWidthTable.ZERO_ASTRAL_RANGES, cp)) return 0
        if (binarySearch(GeneratedUnicodeWidthTable.TERMINAL_CELL_GRAPHIC_ASTRAL_RANGES, cp)) return 1
        if (binarySearch(GeneratedUnicodeWidthTable.WIDE_ASTRAL_RANGES, cp)) return 2
        if (binarySearch(GeneratedUnicodeWidthTable.AMBIGUOUS_ASTRAL_RANGES, cp)) return if (ambiguousAsWide) 2 else 1

        return 1
    }

    fun calculateCluster(
        codepoints: IntArray,
        length: Int,
        ambiguousAsWide: Boolean,
    ): Int {
        if (length == 0) return 0

        if (contains(codepoints, length, TEXT_PRESENTATION_SELECTOR)) return 1

        val base = codepoints[0]
        val baseWidth =
            if (contains(codepoints, length, EMOJI_PRESENTATION_SELECTOR) && isEmojiVariationBase(base)) {
                2
            } else {
                calculate(base, ambiguousAsWide)
            }

        return if (baseWidth > 0) baseWidth else 1
    }

    fun isEmojiVariationBase(codepoint: Int): Boolean =
        if (codepoint < GeneratedUnicodeWidthTable.BITSET_LIMIT) {
            emojiVariationBase.get(codepoint)
        } else {
            binarySearch(GeneratedUnicodeWidthTable.EMOJI_VARIATION_BASE_ASTRAL_RANGES, codepoint)
        }

    private fun contains(
        codepoints: IntArray,
        length: Int,
        needle: Int,
    ): Boolean {
        var index = 1
        while (index < length) {
            if (codepoints[index] == needle) return true
            index++
        }
        return false
    }

    private fun populate(
        bitSet: BitSet,
        ranges: IntArray,
    ) {
        for (i in ranges.indices step 2) {
            bitSet.set(ranges[i], ranges[i + 1] + 1)
        }
    }

    private fun binarySearch(
        ranges: IntArray,
        cp: Int,
    ): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]

            if (cp < start) {
                high = mid - 1
            } else if (cp > end) {
                low = mid + 1
            } else {
                return true
            }
        }
        return false
    }

    private const val TEXT_PRESENTATION_SELECTOR: Int = 0xFE0E
    private const val EMOJI_PRESENTATION_SELECTOR: Int = 0xFE0F
}
