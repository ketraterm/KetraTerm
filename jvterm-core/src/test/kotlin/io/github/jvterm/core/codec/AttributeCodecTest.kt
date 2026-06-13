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
package io.github.jvterm.core.codec

import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.CellColorKind
import io.github.jvterm.core.model.UnderlineStyle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("AttributeCodec")
class AttributeCodecTest {
    @Test
    fun `exports expected color constants`() {
        assertAll(
            { assertEquals(16, AttributeCodec.MAX_ANSI_COLOR) },
            { assertEquals(256, AttributeCodec.MAX_COLOR) },
            { assertEquals(255, AttributeCodec.MAX_INDEXED_COLOR) },
            { assertEquals(0, AttributeCodec.COLOR_KIND_DEFAULT) },
            { assertEquals(1, AttributeCodec.COLOR_KIND_INDEXED) },
            { assertEquals(2, AttributeCodec.COLOR_KIND_RGB) },
        )
    }

    @Nested
    inner class PrimaryWord {
        @Test
        fun `packs foreground background and primary flags`() {
            val packed =
                AttributeCodec.pack(
                    fg = 256,
                    bg = 17,
                    bold = true,
                    faint = true,
                    italic = true,
                    blink = true,
                    inverse = true,
                    protected = true,
                )

            assertAll(
                { assertEquals(256, AttributeCodec.foreground(packed)) },
                { assertEquals(17, AttributeCodec.background(packed)) },
                { assertTrue(AttributeCodec.isBold(packed)) },
                { assertTrue(AttributeCodec.isFaint(packed)) },
                { assertTrue(AttributeCodec.isItalic(packed)) },
                { assertTrue(AttributeCodec.isBlink(packed)) },
                { assertTrue(AttributeCodec.isInverse(packed)) },
                { assertTrue(AttributeCodec.isProtected(packed)) },
            )
        }

        @Test
        fun `packColors preserves indexed rgb and primary flags`() {
            val packed =
                AttributeCodec.packColors(
                    foreground = CellColor.indexed(244),
                    background = CellColor.rgb(0x12, 0x34, 0x56),
                    bold = true,
                    faint = true,
                    italic = false,
                    blink = true,
                    inverse = true,
                )
            val unpacked = AttributeCodec.unpack(packed)

            assertAll(
                { assertEquals(CellColorKind.INDEXED, unpacked.foreground.kind) },
                { assertEquals(244, unpacked.foreground.value) },
                { assertEquals(CellColorKind.RGB, unpacked.background.kind) },
                { assertEquals(0x12_34_56, unpacked.background.value) },
                { assertEquals(245, AttributeCodec.foreground(packed)) },
                { assertEquals(0, AttributeCodec.background(packed)) },
                { assertTrue(unpacked.bold) },
                { assertTrue(unpacked.faint) },
                { assertTrue(unpacked.blink) },
                { assertTrue(unpacked.inverse) },
            )
        }

        @Test
        fun `primitive primary color decode matches object color decode`() {
            val defaultPacked =
                AttributeCodec.packColors(
                    foreground = CellColor.DEFAULT,
                    background = CellColor.DEFAULT,
                )
            val indexedLowPacked =
                AttributeCodec.packColors(
                    foreground = CellColor.indexed(0),
                    background = CellColor.indexed(0),
                )
            val indexedHighPacked =
                AttributeCodec.packColors(
                    foreground = CellColor.indexed(255),
                    background = CellColor.indexed(255),
                )
            val rgbLowPacked =
                AttributeCodec.packColors(
                    foreground = CellColor.rgb(0x00_00_00),
                    background = CellColor.rgb(0x00_00_00),
                )
            val rgbHighPacked =
                AttributeCodec.packColors(
                    foreground = CellColor.rgb(0xFF_FF_FF),
                    background = CellColor.rgb(0xFF_FF_FF),
                )

            assertAll(
                { assertPrimaryColorDecode(defaultPacked, CellColor.DEFAULT, CellColor.DEFAULT) },
                { assertPrimaryColorDecode(indexedLowPacked, CellColor.indexed(0), CellColor.indexed(0)) },
                { assertPrimaryColorDecode(indexedHighPacked, CellColor.indexed(255), CellColor.indexed(255)) },
                { assertPrimaryColorDecode(rgbLowPacked, CellColor.rgb(0x00_00_00), CellColor.rgb(0x00_00_00)) },
                { assertPrimaryColorDecode(rgbHighPacked, CellColor.rgb(0xFF_FF_FF), CellColor.rgb(0xFF_FF_FF)) },
            )
        }

        @Test
        fun `primitive primary color decode preserves reserved kind fallback`() {
            val reservedForeground = 3L shl 24
            val reservedBackground = (3L shl 24) shl 26
            val packed = reservedForeground or reservedBackground

            assertPrimaryColorDecode(packed, CellColor.DEFAULT, CellColor.DEFAULT)
        }

        @ParameterizedTest
        @ValueSource(ints = [-1, 257, 1000, Int.MIN_VALUE, Int.MAX_VALUE])
        fun `rejects invalid foreground`(invalidFg: Int) {
            assertThrows(IllegalArgumentException::class.java) {
                AttributeCodec.pack(invalidFg, 0)
            }
        }

        @ParameterizedTest
        @ValueSource(ints = [-1, 257, 1000, Int.MIN_VALUE, Int.MAX_VALUE])
        fun `rejects invalid background`(invalidBg: Int) {
            assertThrows(IllegalArgumentException::class.java) {
                AttributeCodec.pack(0, invalidBg)
            }
        }
    }

    @Nested
    inner class ExtendedWord {
        @Test
        fun `packs underline color decorations conceal and hyperlink id`() {
            val extended =
                AttributeCodec.packExtended(
                    underlineColor = 200,
                    underlineStyle = UnderlineStyle.DASHED,
                    strikethrough = true,
                    overline = true,
                    conceal = true,
                    hyperlinkId = 42,
                )

            assertAll(
                { assertEquals(200, AttributeCodec.underline(extended)) },
                { assertEquals(UnderlineStyle.DASHED, AttributeCodec.underlineStyle(extended)) },
                { assertTrue(AttributeCodec.isStrikethrough(extended)) },
                { assertTrue(AttributeCodec.isOverline(extended)) },
                { assertTrue(AttributeCodec.isConceal(extended)) },
                { assertEquals(42, AttributeCodec.hyperlinkId(extended)) },
            )
        }

        @Test
        fun `packExtendedColors preserves rgb underline color`() {
            val extended =
                AttributeCodec.packExtendedColors(
                    underlineColor = CellColor.rgb(1, 2, 3),
                    underlineStyle = UnderlineStyle.CURLY,
                )
            val unpacked = AttributeCodec.unpack(0, extended)

            assertAll(
                { assertEquals(CellColor.rgb(1, 2, 3), unpacked.underlineColor) },
                { assertEquals(UnderlineStyle.CURLY, unpacked.underlineStyle) },
            )
        }

        @Test
        fun `primitive underline color decode matches object color decode`() {
            val defaultExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = CellColor.DEFAULT,
                )
            val indexedLowExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = CellColor.indexed(0),
                )
            val indexedHighExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = CellColor.indexed(255),
                )
            val rgbLowExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = CellColor.rgb(0x00_00_00),
                )
            val rgbHighExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = CellColor.rgb(0xFF_FF_FF),
                )

            assertAll(
                { assertUnderlineColorDecode(defaultExtended, CellColor.DEFAULT) },
                { assertUnderlineColorDecode(indexedLowExtended, CellColor.indexed(0)) },
                { assertUnderlineColorDecode(indexedHighExtended, CellColor.indexed(255)) },
                { assertUnderlineColorDecode(rgbLowExtended, CellColor.rgb(0x00_00_00)) },
                { assertUnderlineColorDecode(rgbHighExtended, CellColor.rgb(0xFF_FF_FF)) },
            )
        }

        @Test
        fun `primitive underline color decode preserves reserved kind fallback`() {
            val extended = 3L shl 24

            assertUnderlineColorDecode(extended, CellColor.DEFAULT)
        }

        @Test
        fun `SGR reset preserves protection and active hyperlink only`() {
            val primary = AttributeCodec.pack(1, 2, bold = true, protected = true)
            val extended =
                AttributeCodec.packExtended(
                    underlineStyle = UnderlineStyle.DOUBLE,
                    strikethrough = true,
                    hyperlinkId = 7,
                )

            val resetPrimary = AttributeCodec.sgrResetPrimary(primary)
            val resetExtended = AttributeCodec.sgrResetExtended(extended)
            val unpacked = AttributeCodec.unpack(resetPrimary, resetExtended)

            assertAll(
                { assertTrue(unpacked.selectiveEraseProtected) },
                { assertEquals(7, unpacked.hyperlinkId) },
                { assertEquals(CellColor.DEFAULT, unpacked.foreground) },
                { assertEquals(UnderlineStyle.NONE, unpacked.underlineStyle) },
                { assertFalse(unpacked.bold) },
                { assertFalse(unpacked.strikethrough) },
            )
        }
    }

    private fun assertPrimaryColorDecode(
        packed: Long,
        expectedForeground: CellColor,
        expectedBackground: CellColor,
    ) {
        assertAll(
            { assertEquals(expectedForeground, AttributeCodec.foregroundColor(packed)) },
            { assertEquals(expectedBackground, AttributeCodec.backgroundColor(packed)) },
            { assertEquals(expectedForeground.kind.toCodecKind(), AttributeCodec.foregroundColorKind(packed)) },
            { assertEquals(expectedForeground.value, AttributeCodec.foregroundColorValue(packed)) },
            { assertEquals(expectedBackground.kind.toCodecKind(), AttributeCodec.backgroundColorKind(packed)) },
            { assertEquals(expectedBackground.value, AttributeCodec.backgroundColorValue(packed)) },
        )
    }

    private fun assertUnderlineColorDecode(
        extended: Long,
        expected: CellColor,
    ) {
        assertAll(
            { assertEquals(expected, AttributeCodec.underlineColor(extended)) },
            { assertEquals(expected.kind.toCodecKind(), AttributeCodec.underlineColorKind(extended)) },
            { assertEquals(expected.value, AttributeCodec.underlineColorValue(extended)) },
        )
    }

    private fun CellColorKind.toCodecKind(): Int =
        when (this) {
            CellColorKind.DEFAULT -> AttributeCodec.COLOR_KIND_DEFAULT
            CellColorKind.INDEXED -> AttributeCodec.COLOR_KIND_INDEXED
            CellColorKind.RGB -> AttributeCodec.COLOR_KIND_RGB
        }
}
