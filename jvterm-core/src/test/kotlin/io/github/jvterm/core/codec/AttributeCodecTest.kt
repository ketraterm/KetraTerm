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

import io.github.jvterm.core.model.AttributeColor
import io.github.jvterm.core.model.AttributeColorKind
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
                    foreground = AttributeColor.indexed(244),
                    background = AttributeColor.rgb(0x12, 0x34, 0x56),
                    bold = true,
                    faint = true,
                    italic = false,
                    blink = true,
                    inverse = true,
                )
            val unpacked = AttributeCodec.unpack(packed)

            assertAll(
                { assertEquals(AttributeColorKind.INDEXED, unpacked.foreground.kind) },
                { assertEquals(244, unpacked.foreground.value) },
                { assertEquals(AttributeColorKind.RGB, unpacked.background.kind) },
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
                    foreground = AttributeColor.DEFAULT,
                    background = AttributeColor.DEFAULT,
                )
            val indexedLowPacked =
                AttributeCodec.packColors(
                    foreground = AttributeColor.indexed(0),
                    background = AttributeColor.indexed(0),
                )
            val indexedHighPacked =
                AttributeCodec.packColors(
                    foreground = AttributeColor.indexed(255),
                    background = AttributeColor.indexed(255),
                )
            val rgbLowPacked =
                AttributeCodec.packColors(
                    foreground = AttributeColor.rgb(0x00_00_00),
                    background = AttributeColor.rgb(0x00_00_00),
                )
            val rgbHighPacked =
                AttributeCodec.packColors(
                    foreground = AttributeColor.rgb(0xFF_FF_FF),
                    background = AttributeColor.rgb(0xFF_FF_FF),
                )

            assertAll(
                { assertPrimaryColorDecode(defaultPacked, AttributeColor.DEFAULT, AttributeColor.DEFAULT) },
                { assertPrimaryColorDecode(indexedLowPacked, AttributeColor.indexed(0), AttributeColor.indexed(0)) },
                { assertPrimaryColorDecode(indexedHighPacked, AttributeColor.indexed(255), AttributeColor.indexed(255)) },
                { assertPrimaryColorDecode(rgbLowPacked, AttributeColor.rgb(0x00_00_00), AttributeColor.rgb(0x00_00_00)) },
                { assertPrimaryColorDecode(rgbHighPacked, AttributeColor.rgb(0xFF_FF_FF), AttributeColor.rgb(0xFF_FF_FF)) },
            )
        }

        @Test
        fun `primitive primary color decode preserves reserved kind fallback`() {
            val reservedForeground = 3L shl 24
            val reservedBackground = (3L shl 24) shl 26
            val packed = reservedForeground or reservedBackground

            assertPrimaryColorDecode(packed, AttributeColor.DEFAULT, AttributeColor.DEFAULT)
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
                { assertEquals(200, AttributeCodec.underlineColor(extended)) },
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
                    underlineColor = AttributeColor.rgb(1, 2, 3),
                    underlineStyle = UnderlineStyle.CURLY,
                )
            val unpacked = AttributeCodec.unpack(0, extended)

            assertAll(
                { assertEquals(AttributeColor.rgb(1, 2, 3), unpacked.underlineColor) },
                { assertEquals(UnderlineStyle.CURLY, unpacked.underlineStyle) },
            )
        }

        @Test
        fun `primitive underline color decode matches object color decode`() {
            val defaultExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = AttributeColor.DEFAULT,
                )
            val indexedLowExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = AttributeColor.indexed(0),
                )
            val indexedHighExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = AttributeColor.indexed(255),
                )
            val rgbLowExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = AttributeColor.rgb(0x00_00_00),
                )
            val rgbHighExtended =
                AttributeCodec.packExtendedColors(
                    underlineColor = AttributeColor.rgb(0xFF_FF_FF),
                )

            assertAll(
                { assertUnderlineColorDecode(defaultExtended, AttributeColor.DEFAULT) },
                { assertUnderlineColorDecode(indexedLowExtended, AttributeColor.indexed(0)) },
                { assertUnderlineColorDecode(indexedHighExtended, AttributeColor.indexed(255)) },
                { assertUnderlineColorDecode(rgbLowExtended, AttributeColor.rgb(0x00_00_00)) },
                { assertUnderlineColorDecode(rgbHighExtended, AttributeColor.rgb(0xFF_FF_FF)) },
            )
        }

        @Test
        fun `primitive underline color decode preserves reserved kind fallback`() {
            val extended = 3L shl 24

            assertUnderlineColorDecode(extended, AttributeColor.DEFAULT)
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
                { assertEquals(AttributeColor.DEFAULT, unpacked.foreground) },
                { assertEquals(UnderlineStyle.NONE, unpacked.underlineStyle) },
                { assertFalse(unpacked.bold) },
                { assertFalse(unpacked.strikethrough) },
            )
        }
    }

    private fun assertPrimaryColorDecode(
        packed: Long,
        expectedForeground: AttributeColor,
        expectedBackground: AttributeColor,
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
        expected: AttributeColor,
    ) {
        assertAll(
            { assertEquals(expected, AttributeCodec.underlineAttributeColor(extended)) },
            { assertEquals(expected.kind.toCodecKind(), AttributeCodec.underlineAttributeColorKind(extended)) },
            { assertEquals(expected.value, AttributeCodec.underlineAttributeColorValue(extended)) },
        )
    }

    private fun AttributeColorKind.toCodecKind(): Int =
        when (this) {
            AttributeColorKind.DEFAULT -> AttributeCodec.COLOR_KIND_DEFAULT
            AttributeColorKind.INDEXED -> AttributeCodec.COLOR_KIND_INDEXED
            AttributeColorKind.RGB -> AttributeCodec.COLOR_KIND_RGB
        }
}
