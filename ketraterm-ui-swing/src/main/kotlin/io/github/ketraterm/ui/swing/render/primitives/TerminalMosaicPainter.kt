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
package io.github.ketraterm.ui.swing.render.primitives

import java.awt.Graphics2D

/** Filled 2x3 sextants and 2x4 octants, numbered left-to-right and top-to-bottom. */
internal object TerminalMosaicPainter {
    fun canPaint(codePoint: Int): Boolean = codePoint in 0x1FB00..0x1FB3B || codePoint in 0x1CD00..0x1CDE5

    fun paint(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val rows =
            when (codePoint) {
                in 0x1FB00..0x1FB3B -> 3
                in 0x1CD00..0x1CDE5 -> 4
                else -> return
            }
        val mask =
            if (rows == 3) {
                // U+258C/U+2590 already encode the left/right columns (masks 0x15/0x2A).
                var value = codePoint - 0x1FB00 + 1
                if (value >= 0x15) value++
                if (value >= 0x2A) value++
                value
            } else {
                octants[codePoint - 0x1CD00]
            }
        val halfWidth = width / 2
        for (row in 0 until rows) {
            // The same integer boundary ends one band and starts the next. Even
            // tiny or translucent cells have neither cracks nor overlapping fills.
            val top = y + row * height / rows
            val bandHeight = y + (row + 1) * height / rows - top
            when ((mask ushr (row * 2)) and 3) {
                1 -> g.fillRect(x, top, halfWidth, bandHeight)
                2 -> g.fillRect(x + halfWidth, top, width - halfWidth, bandHeight)
                3 -> g.fillRect(x, top, width, bandHeight)
            }
        }
    }

    // Unicode 17.0.0 BLOCK OCTANT names omit patterns encoded by older blocks.
    // Source: https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt
    // Independently checked against names in test resources; Unicode license in META-INF.
    private val octants =
        intArrayOf(
            0x04,
            0x06,
            0x07,
            0x08,
            0x09,
            0x0B,
            0x0C,
            0x0D,
            0x0E,
            0x10,
            0x11,
            0x12,
            0x13,
            0x15,
            0x16,
            0x17,
            0x18,
            0x19,
            0x1A,
            0x1B,
            0x1C,
            0x1D,
            0x1E,
            0x1F,
            0x20,
            0x21,
            0x22,
            0x23,
            0x24,
            0x25,
            0x26,
            0x27,
            0x29,
            0x2A,
            0x2B,
            0x2C,
            0x2D,
            0x2E,
            0x2F,
            0x30,
            0x31,
            0x32,
            0x33,
            0x34,
            0x35,
            0x36,
            0x37,
            0x38,
            0x39,
            0x3A,
            0x3B,
            0x3C,
            0x3D,
            0x3E,
            0x41,
            0x42,
            0x43,
            0x44,
            0x45,
            0x46,
            0x47,
            0x48,
            0x49,
            0x4A,
            0x4B,
            0x4C,
            0x4D,
            0x4E,
            0x4F,
            0x51,
            0x52,
            0x53,
            0x54,
            0x56,
            0x57,
            0x58,
            0x59,
            0x5B,
            0x5C,
            0x5D,
            0x5E,
            0x60,
            0x61,
            0x62,
            0x63,
            0x64,
            0x65,
            0x66,
            0x67,
            0x68,
            0x69,
            0x6A,
            0x6B,
            0x6C,
            0x6D,
            0x6E,
            0x6F,
            0x70,
            0x71,
            0x72,
            0x73,
            0x74,
            0x75,
            0x76,
            0x77,
            0x78,
            0x79,
            0x7A,
            0x7B,
            0x7C,
            0x7D,
            0x7E,
            0x7F,
            0x81,
            0x82,
            0x83,
            0x84,
            0x85,
            0x86,
            0x87,
            0x88,
            0x89,
            0x8A,
            0x8B,
            0x8C,
            0x8D,
            0x8E,
            0x8F,
            0x90,
            0x91,
            0x92,
            0x93,
            0x94,
            0x95,
            0x96,
            0x97,
            0x98,
            0x99,
            0x9A,
            0x9B,
            0x9C,
            0x9D,
            0x9E,
            0x9F,
            0xA1,
            0xA2,
            0xA3,
            0xA4,
            0xA6,
            0xA7,
            0xA8,
            0xA9,
            0xAB,
            0xAC,
            0xAD,
            0xAE,
            0xB0,
            0xB1,
            0xB2,
            0xB3,
            0xB4,
            0xB5,
            0xB6,
            0xB7,
            0xB8,
            0xB9,
            0xBA,
            0xBB,
            0xBC,
            0xBD,
            0xBE,
            0xBF,
            0xC1,
            0xC2,
            0xC3,
            0xC4,
            0xC5,
            0xC6,
            0xC7,
            0xC8,
            0xC9,
            0xCA,
            0xCB,
            0xCC,
            0xCD,
            0xCE,
            0xCF,
            0xD0,
            0xD1,
            0xD2,
            0xD3,
            0xD4,
            0xD5,
            0xD6,
            0xD7,
            0xD8,
            0xD9,
            0xDA,
            0xDB,
            0xDC,
            0xDD,
            0xDE,
            0xDF,
            0xE0,
            0xE1,
            0xE2,
            0xE3,
            0xE4,
            0xE5,
            0xE6,
            0xE7,
            0xE8,
            0xE9,
            0xEA,
            0xEB,
            0xEC,
            0xED,
            0xEE,
            0xEF,
            0xF1,
            0xF2,
            0xF3,
            0xF4,
            0xF6,
            0xF7,
            0xF8,
            0xF9,
            0xFB,
            0xFD,
            0xFE,
        )
}
