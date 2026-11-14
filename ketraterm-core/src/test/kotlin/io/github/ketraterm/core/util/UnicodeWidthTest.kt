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
package io.github.ketraterm.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

@DisplayName("UnicodeWidth Test Suite")
class UnicodeWidthTest {
    @ParameterizedTest
    @ValueSource(
        ints = [
            0x0600, 0x0605, 0x06DD, 0x070F, 0x0890, 0x0891, 0x08E2, 0x0D4E,
            0x110BD, 0x110CD, 0x111C2, 0x111C3, 0x113D1, 0x1193F, 0x11941,
            0x11A84, 0x11A89, 0x11D46, 0x11F02,
        ],
    )
    fun `leading Prepend characters defer width to the following base`(prepend: Int) {
        for (ambiguousAsWide in listOf(false, true)) {
            assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(prepend, 'A'.code), 2, ambiguousAsWide))
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(prepend, 0x754C), 2, ambiguousAsWide))
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(prepend, 0x1F600), 2, ambiguousAsWide))
            assertEquals(if (ambiguousAsWide) 2 else 1, UnicodeWidth.calculateCluster(intArrayOf(prepend, 0x00A1), 2, ambiguousAsWide))
        }
    }

    @Test
    fun `multiple Prepend characters select one base within the used prefix`() {
        assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x0D4E, 0x110BD, 0x754C), 4, false))
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x0D4E, 0x754C), 2, false))
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(0x0600, -1), 1, false))
        assertEquals(
            1,
            UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x0301, 0x754C), 3, false),
            "only leading Prepend characters are skipped",
        )
        assertEquals(
            1,
            UnicodeWidth.calculateCluster(intArrayOf(0x11A3A, 0x754C), 2, false),
            "non-Prepend narrow bases retain their width",
        )
        assertEquals(0, UnicodeWidth.calculateCluster(intArrayOf(0x0600), 0, false))
    }

    @ParameterizedTest
    @ValueSource(ints = [0x231A, 0x2615, 0x2764, 0x1F610])
    fun `presentation selectors apply to the base after leading Prepend characters`(base: Int) {
        for (ambiguousAsWide in listOf(false, true)) {
            assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x110BD, base, 0xFE0E), 4, ambiguousAsWide))
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x110BD, base, 0xFE0F), 4, ambiguousAsWide))
            assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(0x0600, base, 0xFE0F, 0xFE0E), 4, ambiguousAsWide))
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x754C, 0xFE0E), 3, ambiguousAsWide))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0x0600, 0x0D4E, 0x110BD, 0x11F02])
    fun `prefix only clusters keep one cell even for repeated Prepend characters`(prepend: Int) {
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(prepend), 1, false))
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(prepend, prepend), 2, true))
    }

    @ParameterizedTest
    @ValueSource(ints = [0x231A, 0x2615, 0x2620, 0x2699, 0x26A1, 0x2708, 0x2764, 0x2B50, 0x1F610])
    fun `recognized emoji variation bases use the explicit presentation width`(base: Int) {
        assertTrue(UnicodeWidth.isEmojiVariationBase(base))
        for (ambiguousAsWide in listOf(false, true)) {
            assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(base, 0xFE0E), 2, ambiguousAsWide))
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(base, 0xFE0F), 2, ambiguousAsWide))
            assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(base, 0xFE0F, 0xFE0E), 3, ambiguousAsWide))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0x1100, 0x3042, 0x4E00, 0xAC00, 0xFF01, 0xFAFF, 0x1F600, 0x1FAEA, 0x20000])
    fun `unsupported VS15 bases retain their wide cell allocation`(base: Int) {
        assertFalse(UnicodeWidth.isEmojiVariationBase(base))
        for (ambiguousAsWide in listOf(false, true)) {
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(base, 0xFE0E), 2, ambiguousAsWide))
            assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(base, 0x0301, 0xFE0E), 3, ambiguousAsWide))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0x00A1, 0x0391, 0xE000, 0xF0000, 0xFFFD])
    fun `unsupported VS15 bases retain ambiguous width policy`(base: Int) {
        assertFalse(UnicodeWidth.isEmojiVariationBase(base))
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(base, 0xFE0E), 2, false))
        assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(base, 0xFE0E), 2, true))
    }

    @Test
    fun `VS15 only affects the selected base in the used cluster prefix`() {
        assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(0x4E00, 0x2764, 0xFE0E), 3, false))
        assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(0x2615, 0xFE0E), 1, false))
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(0xFE0E), 1, false))
        assertEquals(0, UnicodeWidth.calculate(0xFE0E, false))
        assertEquals(2, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0x2615, 0xFE0E), 2, false))
        assertEquals(1, UnicodeWidth.calculateCluster(intArrayOf(0x0600, 0xFE0F), 2, false))
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -2, -1, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0x110000, Int.MAX_VALUE])
    fun `non scalars have no width and cannot hide behind presentation selectors`(cp: Int) {
        for (wide in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) { UnicodeWidth.calculate(cp, wide) }
            for (cluster in listOf(
                intArrayOf(cp),
                intArrayOf(cp, 0xFE0E),
                intArrayOf(0x2764, 0xFE0F, cp),
                intArrayOf(0x0600, cp),
                intArrayOf(0x0600, 0x2764, 0xFE0F, cp),
            )) {
                assertThrows(IllegalArgumentException::class.java) {
                    UnicodeWidth.calculateCluster(cluster, cluster.size, wide)
                }
            }
        }
        assertFalse(UnicodeWidth.isEmojiVariationBase(cp))
    }

    @ParameterizedTest
    @ValueSource(ints = [0x0378, 0x0380, 0xE0000, 0xFDD0, 0xFDEF, 0xFFFE, 0xFFFF, 0x2FFFE, 0x3FFFF, 0x10FFFF])
    fun `ordinary unassigned scalars and noncharacters are narrow`(cp: Int) {
        assertEquals(1, UnicodeWidth.calculate(cp, false))
        assertEquals(1, UnicodeWidth.calculate(cp, true))
    }

    @ParameterizedTest
    @ValueSource(ints = [0xFAFF, 0x2FFFD, 0x3FFFD])
    fun `reserved CJK scalars retain Unicode wide defaults`(cp: Int) {
        assertEquals(2, UnicodeWidth.calculate(cp, false))
        assertEquals(2, UnicodeWidth.calculate(cp, true))
    }

    @ParameterizedTest
    @ValueSource(ints = [0xE000, 0xF8FF, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD, 0xFFFD])
    fun `private use and replacement scalars follow ambiguous mode`(cp: Int) {
        assertEquals(1, UnicodeWidth.calculate(cp, false))
        assertEquals(2, UnicodeWidth.calculate(cp, true))
    }

    @Nested
    @DisplayName("ASCII & Control Fast Paths")
    inner class AsciiAndControlFastPathTests {
        @ParameterizedTest(name = "ASCII cp=0x{0} returns width 1")
        @ValueSource(ints = [0x20, 0x41, 0x7E])
        fun `ascii printable returns 1`(cp: Int) {
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Control cp=0x{0} returns width 0")
        @ValueSource(ints = [0x00, 0x1F, 0x7F, 0x85, 0x9F])
        fun `control ranges return 0`(cp: Int) {
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }
    }

    @Nested
    @DisplayName("Zero Width")
    inner class ZeroWidthTests {
        @ParameterizedTest(name = "BMP zero-width cp=0x{0} returns 0")
        @ValueSource(ints = [0x00AD, 0x0300, 0x05BF, 0x0E34, 0x0E48, 0x0EB4, 0x0ECD, 0x200B, 0xFE0F])
        fun `bmp zero-width samples return 0`(cp: Int) {
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Astral zero-width cp=0x{0} returns 0")
        @ValueSource(ints = [0x1D173, 0xE0001, 0xE007F, 0xE0100, 0xE01EF])
        fun `astral zero-width samples return 0`(cp: Int) {
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }
    }

    @Nested
    @DisplayName("Wide Width")
    inner class WideWidthTests {
        @ParameterizedTest(name = "BMP wide cp=0x{0} returns 2")
        @ValueSource(ints = [0x1100, 0x231A, 0x3042, 0xAC00, 0xFF01])
        fun `bmp wide samples return 2`(cp: Int) {
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Astral wide cp=0x{0} returns 2")
        @ValueSource(ints = [0x1F600, 0x1FAEA, 0x20000, 0x2FFFD, 0x30000, 0x3FFFD])
        fun `astral wide samples return 2`(cp: Int) {
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }
    }

    @Nested
    @DisplayName("Ambiguous Width")
    inner class AmbiguousWidthTests {
        @ParameterizedTest(name = "Ambiguous cp=0x{0} follows toggle")
        @ValueSource(ints = [0x00A1, 0x0391, 0x20AC, 0x2014])
        fun `ambiguous codepoints honor toggle`(cp: Int) {
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Terminal cell graphic cp=0x{0} remains narrow")
        @ValueSource(ints = [0x2500, 0x257F, 0x2588, 0x2591, 0x2800, 0x28FF, 0x1FB00, 0x1FBFF])
        fun `terminal cell graphics stay narrow in ambiguous wide mode`(cp: Int) {
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Zero-width cp=0x{0} is not generated ambiguous")
        @ValueSource(ints = [0x00AD, 0x0300, 0x036F, 0xFE00, 0xFE0F, 0xE0100, 0xE01EF])
        fun `zero-width codepoints are excluded from generated ambiguous ranges`(cp: Int) {
            assertFalse(isGeneratedAmbiguous(cp))
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(0, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Terminal graphic cp=0x{0} is not generated ambiguous")
        @ValueSource(ints = [0x2500, 0x254B, 0x2550, 0x2573, 0x2580, 0x258F, 0x2592, 0x2595])
        fun `terminal graphics are excluded from generated ambiguous ranges`(cp: Int) {
            assertFalse(isGeneratedAmbiguous(cp))
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Private-use ambiguous cp=0x{0} follows toggle")
        @ValueSource(ints = [0xE000, 0xF0000])
        fun `generated private-use ambiguous ranges honor toggle`(cp: Int) {
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Non-ambiguous cp=0x{0} ignores toggle")
        @MethodSource("io.github.ketraterm.core.util.UnicodeWidthTest#nonAmbiguousSamples")
        fun `non-ambiguous classes ignore toggle`(
            cp: Int,
            expected: Int,
        ) {
            assertEquals(expected, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(expected, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }
    }

    @Nested
    @DisplayName("Fallback & Range Boundaries")
    inner class FallbackAndBoundaryTests {
        @ParameterizedTest(name = "Default narrow fallback cp=0x{0} returns 1")
        @ValueSource(ints = [0x0102, 0x0370, 0x2691, 0x2764, 0x10FFFF])
        fun `default fallback returns narrow width 1`(cp: Int) {
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Boundary cp=0x{0} expected={1}")
        @MethodSource("io.github.ketraterm.core.util.UnicodeWidthTest#astralBoundaryCases")
        fun `astral boundary checks`(
            cp: Int,
            expected: Int,
        ) {
            assertEquals(expected, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(expected, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }
    }

    companion object {
        @JvmStatic
        fun nonAmbiguousSamples(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0x0300, 0), // zero-width combining
                Arguments.of(0x3042, 2), // wide Hiragana
                Arguments.of(0x0041, 1), // ASCII
            )

        @JvmStatic
        fun astralBoundaryCases(): Stream<Arguments> =
            Stream.of(
                // Astral wide ranges
                Arguments.of(0x1FFFF, 1),
                Arguments.of(0x20000, 2),
                Arguments.of(0x2FFFD, 2),
                Arguments.of(0x2FFFE, 1),
                Arguments.of(0x2FFFF, 1),
                Arguments.of(0x30000, 2),
                Arguments.of(0x3FFFD, 2),
                Arguments.of(0x3FFFE, 1),
                // Astral zero-width ranges
                Arguments.of(0xDFFFF, 1),
                Arguments.of(0xE0000, 1),
                Arguments.of(0xE0001, 0),
                Arguments.of(0xE007F, 0),
                Arguments.of(0xE0080, 1),
                Arguments.of(0xE00FF, 1),
                Arguments.of(0xE0100, 0),
                Arguments.of(0xE01EF, 0),
                Arguments.of(0xE01F0, 1),
            )

        private fun isGeneratedAmbiguous(codepoint: Int): Boolean =
            if (codepoint < GeneratedUnicodeWidthTable.BITSET_LIMIT) {
                contains(GeneratedUnicodeWidthTable.AMBIGUOUS_RANGES, codepoint)
            } else {
                contains(GeneratedUnicodeWidthTable.AMBIGUOUS_ASTRAL_RANGES, codepoint)
            }

        private fun contains(
            ranges: IntArray,
            codepoint: Int,
        ): Boolean {
            var low = 0
            var high = ranges.size / 2 - 1
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
}
