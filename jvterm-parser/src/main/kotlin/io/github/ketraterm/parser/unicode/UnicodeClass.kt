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

internal object UnicodeClass {
    const val GRAPHEME_OTHER: Int = 0
    const val GRAPHEME_CR: Int = 1
    const val GRAPHEME_LF: Int = 2
    const val GRAPHEME_CONTROL: Int = 3
    const val GRAPHEME_EXTEND: Int = 4
    const val GRAPHEME_ZWJ: Int = 5
    const val GRAPHEME_REGIONAL_INDICATOR: Int = 6
    const val GRAPHEME_SPACING_MARK: Int = 7
    const val GRAPHEME_PREPEND: Int = 8
    const val GRAPHEME_L: Int = 9
    const val GRAPHEME_V: Int = 10
    const val GRAPHEME_T: Int = 11
    const val GRAPHEME_LV: Int = 12
    const val GRAPHEME_LVT: Int = 13

    @JvmStatic
    fun graphemeBreakClass(codepoint: Int): Int = GeneratedGraphemeBreakTable.graphemeBreakClass(codepoint)

    @JvmStatic
    fun isExtendedPictographic(codepoint: Int): Boolean = GeneratedGraphemeBreakTable.isExtendedPictographic(codepoint)
}
