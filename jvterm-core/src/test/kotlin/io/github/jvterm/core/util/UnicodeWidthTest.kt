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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

@DisplayName("UnicodeWidth Test Suite")
class UnicodeWidthTest {
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
        @ValueSource(ints = [0x00, 0x1F, 0x7F, 0x85, 0x9F, -1])
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

        @ParameterizedTest(name = "Private-use ambiguous cp=0x{0} follows toggle")
        @ValueSource(ints = [0xE000, 0xF0000])
        fun `generated private-use ambiguous ranges honor toggle`(cp: Int) {
            assertEquals(1, UnicodeWidth.calculate(cp, ambiguousAsWide = false))
            assertEquals(2, UnicodeWidth.calculate(cp, ambiguousAsWide = true))
        }

        @ParameterizedTest(name = "Non-ambiguous cp=0x{0} ignores toggle")
        @MethodSource("io.github.jvterm.core.util.UnicodeWidthTest#nonAmbiguousSamples")
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
        @MethodSource("io.github.jvterm.core.util.UnicodeWidthTest#astralBoundaryCases")
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
    }
}
