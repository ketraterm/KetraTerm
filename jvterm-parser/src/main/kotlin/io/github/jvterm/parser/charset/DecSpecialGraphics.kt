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
package io.github.jvterm.parser.charset

/**
 * DEC Special Graphics translation table.
 *
 * Applies to printable GL codepoints when the active GL charset is designated as DEC Special
 * Graphics via sequences such as ESC ( 0 or ESC ) 0.
 */
internal object DecSpecialGraphics {
    @JvmStatic
    fun map(codepoint: Int): Int =
        when (codepoint) {
            '`'.code -> 0x25c6 // ◆ black diamond
            'a'.code -> 0x2592 // ▒ checkerboard
            'b'.code -> 0x2409 // symbol for horizontal tab
            'c'.code -> 0x240c // symbol for form feed
            'd'.code -> 0x240d // symbol for carriage return
            'e'.code -> 0x240a // symbol for line feed
            'f'.code -> 0x00b0 // ° degree sign
            'g'.code -> 0x00b1 // ± plus/minus
            'h'.code -> 0x2424 // symbol for newline
            'i'.code -> 0x240b // symbol for vertical tab
            'j'.code -> 0x2518 // ┘ box drawings light up and left
            'k'.code -> 0x2510 // ┐ box drawings light down and left
            'l'.code -> 0x250c // ┌ box drawings light down and right
            'm'.code -> 0x2514 // └ box drawings light up and right
            'n'.code -> 0x253c // ┼ box drawings light vertical and horizontal
            'o'.code -> 0x23ba // ⎺ horizontal scan line 1
            'p'.code -> 0x23bb // ⎻ horizontal scan line 3
            'q'.code -> 0x2500 // ─ box drawings light horizontal
            'r'.code -> 0x23bc // ⎼ horizontal scan line 7
            's'.code -> 0x23bd // ⎽ horizontal scan line 9
            't'.code -> 0x251c // ├ box drawings light vertical and right
            'u'.code -> 0x2524 // ┤ box drawings light vertical and left
            'v'.code -> 0x2534 // ┴ box drawings light up and horizontal
            'w'.code -> 0x252c // ┬ box drawings light down and horizontal
            'x'.code -> 0x2502 // │ box drawings light vertical
            'y'.code -> 0x2264 // ≤ less-than-or-equal
            'z'.code -> 0x2265 // ≥ greater-than-or-equal
            '{'.code -> 0x03c0 // π greek small letter pi
            '|'.code -> 0x2260 // ≠ not equal
            '}'.code -> 0x00a3 // £ pound sign
            '~'.code -> 0x00b7 // · middle dot
            else -> codepoint
        }
}
