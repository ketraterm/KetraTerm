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
package io.github.jvterm.render.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderAttrsTest {
    @Test
    fun `default word decodes to default colors and no styles`() {
        val word = TerminalRenderAttrs.DEFAULT

        assertAll(
            { assertEquals(TerminalRenderColorKind.DEFAULT, TerminalRenderAttrs.foregroundKind(word)) },
            { assertEquals(0, TerminalRenderAttrs.foregroundValue(word)) },
            { assertEquals(TerminalRenderColorKind.DEFAULT, TerminalRenderAttrs.backgroundKind(word)) },
            { assertEquals(0, TerminalRenderAttrs.backgroundValue(word)) },
            { assertFalse(TerminalRenderAttrs.isBold(word)) },
            { assertFalse(TerminalRenderAttrs.isFaint(word)) },
            { assertFalse(TerminalRenderAttrs.isItalic(word)) },
            { assertEquals(TerminalRenderUnderline.NONE, TerminalRenderAttrs.underlineStyle(word)) },
            { assertFalse(TerminalRenderAttrs.isBlink(word)) },
            { assertFalse(TerminalRenderAttrs.isInverse(word)) },
            { assertFalse(TerminalRenderAttrs.isInvisible(word)) },
            { assertFalse(TerminalRenderAttrs.isStrikethrough(word)) },
        )
    }

    @Test
    fun `packed indexed and rgb colors decode through public ABI`() {
        val word =
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.INDEXED,
                foregroundValue = 196,
                backgroundKind = TerminalRenderColorKind.RGB,
                backgroundValue = 0x12_34_56,
            )

        assertAll(
            { assertEquals(TerminalRenderColorKind.INDEXED, TerminalRenderAttrs.foregroundKind(word)) },
            { assertEquals(196, TerminalRenderAttrs.foregroundValue(word)) },
            { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.backgroundKind(word)) },
            { assertEquals(0x12_34_56, TerminalRenderAttrs.backgroundValue(word)) },
        )
    }

    @Test
    fun `packed style bits decode independently`() {
        val word =
            TerminalRenderAttrs.pack(
                bold = true,
                faint = true,
                italic = true,
                underlineStyle = TerminalRenderUnderline.DOTTED,
                blink = true,
                inverse = true,
                invisible = true,
                strikethrough = true,
            )

        assertAll(
            { assertTrue(TerminalRenderAttrs.isBold(word)) },
            { assertTrue(TerminalRenderAttrs.isFaint(word)) },
            { assertTrue(TerminalRenderAttrs.isItalic(word)) },
            { assertEquals(TerminalRenderUnderline.DOTTED, TerminalRenderAttrs.underlineStyle(word)) },
            { assertTrue(TerminalRenderAttrs.isBlink(word)) },
            { assertTrue(TerminalRenderAttrs.isInverse(word)) },
            { assertTrue(TerminalRenderAttrs.isInvisible(word)) },
            { assertTrue(TerminalRenderAttrs.isStrikethrough(word)) },
        )
    }

    @Test
    fun `packed word uses documented stable bit layout`() {
        val word =
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.RGB,
                foregroundValue = 0x11_22_33,
                backgroundKind = TerminalRenderColorKind.INDEXED,
                backgroundValue = 245,
                bold = true,
                faint = true,
                italic = true,
                underlineStyle = TerminalRenderUnderline.DASHED,
                blink = true,
                inverse = true,
                invisible = true,
                strikethrough = true,
            )

        val expected =
            (TerminalRenderColorKind.RGB.toLong() shl 0) or
                (0x11_22_33L shl 2) or
                (TerminalRenderColorKind.INDEXED.toLong() shl 26) or
                (245L shl 28) or
                (1L shl 52) or
                (1L shl 53) or
                (1L shl 54) or
                (TerminalRenderUnderline.DASHED.toLong() shl 55) or
                (1L shl 58) or
                (1L shl 59) or
                (1L shl 60) or
                (1L shl 61)

        assertEquals(expected, word)
    }

    @Test
    fun `all public underline styles round trip`() {
        assertAll(
            { assertUnderlineRoundTrip(TerminalRenderUnderline.NONE) },
            { assertUnderlineRoundTrip(TerminalRenderUnderline.SINGLE) },
            { assertUnderlineRoundTrip(TerminalRenderUnderline.DOUBLE) },
            { assertUnderlineRoundTrip(TerminalRenderUnderline.CURLY) },
            { assertUnderlineRoundTrip(TerminalRenderUnderline.DOTTED) },
            { assertUnderlineRoundTrip(TerminalRenderUnderline.DASHED) },
        )
    }

    @Test
    fun `packer rejects invalid public color values`() {
        assertAll(
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderAttrs.pack(
                        foregroundKind = TerminalRenderColorKind.DEFAULT,
                        foregroundValue = 1,
                    )
                }
            },
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderAttrs.pack(
                        foregroundKind = TerminalRenderColorKind.INDEXED,
                        foregroundValue = 256,
                    )
                }
            },
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderAttrs.pack(
                        backgroundKind = TerminalRenderColorKind.RGB,
                        backgroundValue = 0x01_00_00_00,
                    )
                }
            },
            {
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalRenderAttrs.pack(foregroundKind = 3)
                }
            },
        )
    }

    @Test
    fun `packer rejects invalid underline styles`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalRenderAttrs.pack(underlineStyle = 6)
        }
    }

    private fun assertUnderlineRoundTrip(style: Int) {
        assertEquals(style, TerminalRenderAttrs.underlineStyle(TerminalRenderAttrs.pack(underlineStyle = style)))
    }
}
