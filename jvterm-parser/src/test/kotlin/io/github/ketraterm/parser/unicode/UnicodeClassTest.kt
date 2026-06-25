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
package io.github.ketraterm.parser.unicode

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("UnicodeClass")
class UnicodeClassTest {
    @Nested
    @DisplayName("grapheme break class")
    inner class GraphemeBreakClass {
        @Test
        fun `classifies combining marks and variation selectors as Extend`() {
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0301))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x1AB0))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0xFE0F))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0xE0100))
        }

        @Test
        fun `classifies Thai and Lao combining marks as Extend`() {
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0E31))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0E34))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0E4D))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0EB1))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0EB4))
            assertEquals(UnicodeClass.GRAPHEME_EXTEND, UnicodeClass.graphemeBreakClass(0x0ECD))
        }

        @Test
        fun `classifies ZWJ regional indicators and spacing marks`() {
            assertEquals(UnicodeClass.GRAPHEME_ZWJ, UnicodeClass.graphemeBreakClass(0x200D))
            assertEquals(UnicodeClass.GRAPHEME_REGIONAL_INDICATOR, UnicodeClass.graphemeBreakClass(0x1F1FA))
            assertEquals(UnicodeClass.GRAPHEME_SPACING_MARK, UnicodeClass.graphemeBreakClass(0x0903))
            assertEquals(UnicodeClass.GRAPHEME_SPACING_MARK, UnicodeClass.graphemeBreakClass(0x11F03))
        }

        @Test
        fun `classifies CR LF control and prepend classes`() {
            assertEquals(UnicodeClass.GRAPHEME_CR, UnicodeClass.graphemeBreakClass(0x000D))
            assertEquals(UnicodeClass.GRAPHEME_LF, UnicodeClass.graphemeBreakClass(0x000A))
            assertEquals(UnicodeClass.GRAPHEME_CONTROL, UnicodeClass.graphemeBreakClass(0x0000))
            assertEquals(UnicodeClass.GRAPHEME_CONTROL, UnicodeClass.graphemeBreakClass(0x009F))
            assertEquals(UnicodeClass.GRAPHEME_CONTROL, UnicodeClass.graphemeBreakClass(0x1D173))
            assertEquals(UnicodeClass.GRAPHEME_PREPEND, UnicodeClass.graphemeBreakClass(0x0600))
        }

        @Test
        fun `classifies Hangul Jamo and syllable classes`() {
            assertEquals(UnicodeClass.GRAPHEME_L, UnicodeClass.graphemeBreakClass(0x1100))
            assertEquals(UnicodeClass.GRAPHEME_V, UnicodeClass.graphemeBreakClass(0x1161))
            assertEquals(UnicodeClass.GRAPHEME_T, UnicodeClass.graphemeBreakClass(0x11A8))
            assertEquals(UnicodeClass.GRAPHEME_LV, UnicodeClass.graphemeBreakClass(0xAC00))
            assertEquals(UnicodeClass.GRAPHEME_LVT, UnicodeClass.graphemeBreakClass(0xAC01))
        }
    }

    @Nested
    @DisplayName("extended pictographic")
    inner class ExtendedPictographic {
        @Test
        fun `classifies emoji and symbol bases`() {
            assertTrue(UnicodeClass.isExtendedPictographic(0x1F468))
            assertTrue(UnicodeClass.isExtendedPictographic(0x2764))
            assertTrue(UnicodeClass.isExtendedPictographic(0x00A9))
            assertTrue(UnicodeClass.isExtendedPictographic(0x1FAE8))
        }

        @Test
        fun `does not classify ordinary letters or regional indicators as extended pictographic`() {
            assertFalse(UnicodeClass.isExtendedPictographic('A'.code))
            assertFalse(UnicodeClass.isExtendedPictographic(0x1F1FA))
        }
    }
}
