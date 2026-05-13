package com.gagik.core.util

import java.util.*

/**
 * High-performance Unicode East Asian Width (EAW) and Zero-Width calculator.
 * Uses a triple-bitset architecture for O(1) lookups on the BMP and SMP.
 */
internal object UnicodeWidth {

    private val wide = BitSet(0x20000)
    private val zero = BitSet(0x20000)
    private val ambiguous = BitSet(0x20000)

    // Astral Wide: CJK Extensions B through H (SIP & TIP)
    private val astralWideRanges = intArrayOf(
        0x20000, 0x2FFFD,
        0x30000, 0x3FFFD
    )

    // Astral Zero: Tags and Variation Selectors Supplement
    private val astralZeroRanges = intArrayOf(
        0xE0000, 0xE007F,
        0xE0100, 0xE01EF
    )

    init {
        // 1. Populate Zero-Width Ranges (Including Arabic, Indic, Hebrew)
        val zeroRanges = intArrayOf(
            0x00AD, 0x00AD, // Soft Hyphen
            0x0300, 0x036F, // Combining Diacritical Marks
            0x0483, 0x0489, // Cyrillic Combining
            0x0591, 0x05BD, // Hebrew Combining
            0x05BF, 0x05BF,
            0x05C1, 0x05C2,
            0x05C4, 0x05C5,
            0x05C7, 0x05C7,
            0x0610, 0x061A, // Arabic Combining
            0x064B, 0x065F,
            0x0670, 0x0670,
            0x06D6, 0x06DC,
            0x06DF, 0x06E4,
            0x06E7, 0x06E8,
            0x06EA, 0x06ED,
            0x0711, 0x0711, // Syriac Combining
            0x0730, 0x074A,
            0x07A6, 0x07B0, // Thaana Combining
            0x07EB, 0x07F3, // NKo Combining
            0x0816, 0x0819, // Mandaic / Arabic Extended
            0x081B, 0x0823,
            0x0825, 0x0827,
            0x0829, 0x082D,
            0x0859, 0x085B,
            0x08D3, 0x0902, // Indic (Devanagari) Combining
            0x093A, 0x093A,
            0x093C, 0x093C,
            0x0941, 0x0948,
            0x094D, 0x094D,
            0x0951, 0x0957,
            0x0962, 0x0963,
            0x0E31, 0x0E31, // Thai combining
            0x0E34, 0x0E3A,
            0x0E47, 0x0E4E,
            0x0EB1, 0x0EB1, // Lao combining
            0x0EB4, 0x0EBC,
            0x0EC8, 0x0ECD,
            0x1DC0, 0x1DFF, // Combining Supplement
            0x200B, 0x200F, // Zero Width Spaces / Formatting
            0x202A, 0x202E, // BiDi Formatting
            0x2060, 0x206F, // Invisible Formatting
            0xFE00, 0xFE0F  // Variation Selectors
        )
        for (i in zeroRanges.indices step 2) {
            zero.set(zeroRanges[i], zeroRanges[i + 1] + 1)
        }

        // 2. Populate Wide Ranges (CJK, Fullwidth, and Emoji)
        val wideRanges = intArrayOf(
            0x1100, 0x115F, // Hangul Jamo
            0x231A, 0x231B, // Watches
            0x2329, 0x232A, // Angle brackets
            0x2600, 0x26FF, // Misc Symbols (Legacy Terminal Hack for BMP Emojis)
            0x2700, 0x27BF, // Dingbats (Legacy Terminal Hack)
            0x2E80, 0x303E, // CJK Radicals
            0x3040, 0xA4CF, // CJK Unified & Kana
            0xAC00, 0xD7A3, // Hangul Syllables
            0xF900, 0xFAFF, // CJK Compatibility
            0xFE10, 0xFE19, // Vertical forms
            0xFE30, 0xFE6F, // CJK Compatibility Forms
            0xFF00, 0xFF60, // Fullwidth ASCII
            0xFFE0, 0xFFE6, // Fullwidth symbols

            // SMP: Emoticons, Symbols, Pictographs
            0x1F300, 0x1F64F,
            0x1F680, 0x1F6FF,
            0x1F700, 0x1F77F, // Alchemical
            0x1F780, 0x1F7FF, // Geometric Shapes Ext
            0x1F800, 0x1F8FF, // Supplemental Arrows
            0x1F900, 0x1F9FF,
            0x1FA00, 0x1FAFF
        )
        for (i in wideRanges.indices step 2) {
            wide.set(wideRanges[i], wideRanges[i + 1] + 1)
        }

        // 3. Populate Ambiguous Ranges
        val ambiguousRanges = intArrayOf(
            0x00A1, 0x00A1, 0x00A4, 0x00A4, 0x00A7, 0x00A8,
            0x00AA, 0x00AA, 0x00AE, 0x00AE, 0x00B0, 0x00B4, // Removed 0x00AD
            0x00B6, 0x00BA, 0x00BC, 0x00BF, 0x00C6, 0x00C6,
            0x00D0, 0x00D0, 0x00D7, 0x00D8, 0x00DE, 0x00E1,
            0x00E6, 0x00E6, 0x00F0, 0x00F0, 0x00F8, 0x00F8,
            0x00FE, 0x0101, 0x0111, 0x0111, 0x0113, 0x0113,
            0x011B, 0x011B, 0x0126, 0x0127, 0x012B, 0x012B,
            0x0131, 0x0133, 0x0138, 0x0138, 0x013F, 0x0142,
            0x0144, 0x0144, 0x0148, 0x014B, 0x014D, 0x014D,
            0x0152, 0x0153, 0x0166, 0x0167, 0x016B, 0x016B,
            0x01CE, 0x01CE, 0x01D0, 0x01D0, 0x01D2, 0x01D2,
            0x01D4, 0x01D4, 0x01D6, 0x01D6, 0x01D8, 0x01D8,
            0x01DA, 0x01DA, 0x01DC, 0x01DC, 0x0251, 0x0251,
            0x0261, 0x0261, 0x02C4, 0x02C4, 0x02C7, 0x02C7,
            0x02C9, 0x02CB, 0x02CD, 0x02CD, 0x02D0, 0x02D0,
            0x02D8, 0x02DB, 0x02DD, 0x02DD, 0x02DF, 0x02DF,
            0x0391, 0x03C9, // Greek characters
            0x0401, 0x045F, // Cyrillic characters
            0x2010, 0x2016, // Punctuation
            0x2018, 0x2019,
            0x201C, 0x201D,
            0x2020, 0x2022,
            0x2024, 0x2027,
            0x2030, 0x2030,
            0x2032, 0x2033,
            0x2035, 0x2035,
            0x203B, 0x203B,
            0x203E, 0x203E,
            0x2074, 0x2074,
            0x207F, 0x207F,
            0x2081, 0x2084,
            0x20AC, 0x20AC, // Euro Sign
            0x2103, 0x2103, 0x2105, 0x2105, 0x2109, 0x2109,
            0x2113, 0x2113, 0x2116, 0x2116, 0x2121, 0x2122,
            0x2126, 0x2126, 0x212B, 0x212B, 0x2153, 0x2154,
            0x215B, 0x215E, 0x2160, 0x216B, 0x2170, 0x2179,
            0x2189, 0x2189, 0x2190, 0x2199, 0x21B8, 0x21B9,
            0x21D2, 0x21D2, 0x21D4, 0x21D4, 0x21E7, 0x21E7,
            0x2200, 0x2200, 0x2202, 0x2203, 0x2207, 0x2208,
            0x220B, 0x220B, 0x220F, 0x220F, 0x2211, 0x2211,
            0x2215, 0x2215, 0x221A, 0x221A, 0x221D, 0x2220,
            0x2223, 0x2223, 0x2225, 0x2225, 0x2227, 0x222C,
            0x222E, 0x222E, 0x2234, 0x2237, 0x223C, 0x223D,
            0x2248, 0x2248, 0x224C, 0x224C, 0x2252, 0x2252,
            0x2260, 0x2261, 0x2264, 0x2267, 0x226A, 0x226B,
            0x226E, 0x226F, 0x2282, 0x2283, 0x2286, 0x2287,
            0x2295, 0x2295, 0x2299, 0x2299, 0x22A5, 0x22A5,
            0x22BF, 0x22BF,
            0x2312, 0x2312, // Math operators
            0x2460, 0x24E9, // Enclosed Alphanumerics
            0x2500, 0x257F, // Box Drawing
            0x2580, 0x259F, // Block Elements
            0x25A0, 0x25FF, // Geometric Shapes
            0x2605, 0x2606, 0x2609, 0x2609, 0x260E, 0x260F,
            0x2614, 0x2615, 0x261C, 0x261C, 0x261E, 0x261E,
            0x2640, 0x2640, 0x2642, 0x2642, 0x2660, 0x2661,
            0x2663, 0x2665, 0x2667, 0x266A, 0x266C, 0x266D,
            0x266F, 0x266F, 0x273D, 0x273D, 0x2776, 0x277F,
            0x3248, 0x324F  // Circled Numbers
        )
        for (i in ambiguousRanges.indices step 2) {
            ambiguous.set(ambiguousRanges[i], ambiguousRanges[i + 1] + 1)
        }
    }

    /**
     * Calculates the rendering width of a codepoint on the terminal grid.
     * @param cp The Unicode codepoint.
     * @param ambiguousAsWide Runtime configuration defining how to treat 'Ambiguous' East Asian Width characters.
     * @return 0 (Zero-width), 1 (Narrow), or 2 (Wide).
     */
    fun calculate(cp: Int, ambiguousAsWide: Boolean): Int {
        // Fast-path: Standard ASCII Printable (99% of text)
        if (cp in 0x20..0x7E) return 1

        // Fast-path: Control Characters (C0, DEL, C1)
        if (cp < 0x20 || cp in 0x7F..0x9F) return 0

        // O(1) BMP/SMP Lookup using BitSets
        if (cp < 0x20000) {
            if (zero.get(cp)) return 0
            if (wide.get(cp)) return 2 // Reordered: Wide checked before Ambiguous for performance
            if (ambiguous.get(cp)) return if (ambiguousAsWide) 2 else 1
            return 1
        }

        // O(log N) Astral Lookup
        if (binarySearch(astralWideRanges, cp)) return 2
        if (binarySearch(astralZeroRanges, cp)) return 0

        return 1
    }

    private fun binarySearch(ranges: IntArray, cp: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]

            if (cp < start) high = mid - 1
            else if (cp > end) low = mid + 1
            else return true
        }
        return false
    }
}
