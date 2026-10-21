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
package io.github.ketraterm.parser.ansi.osc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class KittyColorParserTest {
    @Test
    fun `hash colors retain the most significant bits instead of scaling short channels`() {
        assertColor(0xff30a070, "#3a7")
        assertColor(0xff30a070, "#3000a0007000")
        assertColor(0xff123456, "#123456")
        assertColor(0xff124578, "#123456789")
        assertColor(0xff12569a, "#123456789aBc")
        assertColor(0xfff0f0f0, "#fff")
        assertColor(0xffffffff, "#ffffff")
        assertColor(0xff000000, "#000")
    }

    @Test
    fun `rgb channels independently scale one to four hex digits to the nearest byte`() {
        assertColor(0xff33aa77, "rgb:3/a/7")
        assertColor(0xff123456, "rgb:12/34/56")
        assertColor(0xff0080ff, "rgb:001/801/ffe")
        assertColor(0xff0080ff, "rgb:0001/8080/fffe")
        assertColor(0xff11aaff, "RGB:1/Aa/fFfF")
        assertColor(0xffffffff, "rgb:f/fff/ffff")
        assertColor(0xff000000, "rgb:0/00/000")
    }

    @Test
    fun `rgbi decimals support signs fractional forms exponents and clipping`() {
        assertColor(0xff0080ff, "rgbi:0/.5/1")
        assertColor(0xff1a80e6, "rgbi:1e-1/+5E-1/9e-1")
        assertColor(0xff00ff00, "rgbi:-0.5/1.5/-1e99")
        assertColor(0xffff0000, "rgbi:1./.0/+0.")
        assertColor(0xff1a80e6, "rgbi:000.1000/00050e-2/+.90")
        assertColor(0xff00ff00, "rgbi:0e99999999999999999999/1e99999999999999999999/1e-99999999999999999999")
    }

    @Test
    fun `long valid decimals cannot overflow and exponents can cancel long mantissas`() {
        assertColor(0xffff8000, "rgbi:1${"0".repeat(1000)}e-1000/0.${"0".repeat(1000)}5e1000/0")
        assertColor(0xffff0000, "rgbi:${"9".repeat(1000)}/0.${"0".repeat(1000)}1/0")
    }

    @Test
    fun `equivalent decimal and exponent spellings produce identical RGB and alpha channels`() {
        for (value in listOf(".1", ".100000", "000.1000000000000000", "1e-1", "100000e-6", "+100000E-6", "0.0001e3")) {
            assertDecimalChannel(26, value)
        }
        for (value in listOf(".5", ".50000000000", "0.500000000000000000000", "5e-1", "50000000000e-11", "0.0005e3")) {
            assertDecimalChannel(128, value)
        }
        for (value in listOf(".9", ".900000", "9e-1", "900000e-6")) {
            assertDecimalChannel(230, value)
        }
    }

    @Test
    fun `every channel rounding boundary preserves values below above and exactly halfway`() {
        // Independent high-precision oracle: channel k rounds up at (2k + 1) / 510.
        val denominator = BigDecimal(510)
        val unit = BigDecimal.ONE.scaleByPowerOfTen(-80)
        for (lowerChannel in 0 until 255) {
            val numerator = BigDecimal(2 * lowerChannel + 1)
            val truncated = numerator.divide(denominator, 80, RoundingMode.DOWN)
            val exact = truncated.multiply(denominator).compareTo(numerator) == 0
            val below = if (exact) truncated.subtract(unit) else truncated
            val above = truncated.add(unit)

            assertDecimalChannel(lowerChannel, below.toPlainString())
            assertDecimalChannel(lowerChannel, "${below.unscaledValue()}e-${below.scale()}")
            assertDecimalChannel(lowerChannel + 1, above.toPlainString())
            assertDecimalChannel(lowerChannel + 1, "${above.unscaledValue()}e-${above.scale()}")
            if (exact) assertDecimalChannel(lowerChannel + 1, truncated.toPlainString())
        }
    }

    @Test
    fun `named colors follow the protocol X11 table and are case insensitive`() {
        assertColor(0xff000000, "black")
        assertColor(0xffffffff, "WHITE")
        assertColor(0xff00ff00, "green")
        assertColor(0xffbebebe, "GrAy")
        assertColor(0xffbebebe, "grey")
        assertColor(0xffa020f0, "purple")
        assertColor(0xffb03060, "maroon")
        assertColor(0xffd70751, "debianred")
        assertColor(0xfff0f8ff, "ALICE BLUE")
        assertColor(0xfff0f8ff, "aliceblue")
        assertColor(0xff87ceeb, "sky blue")
        assertColor(0xff9acd32, "yellowgreen")
        assertColor(0xff7f7f7f, "gray50")
        assertColor(0xffffffff, "grey100")
        assertColor(0xff8b7500, "gold4")
    }

    @Test
    fun `alpha suffix is independently parsed rounded and clipped for every color form`() {
        assertColor(0x8030a070, "#3a7@0.5")
        assertColor(0x8033aa77, "rgb:3/a/7@5e-1")
        assertColor(0x1a0080ff, "rgbi:0/.5/1@.1")
        assertColor(0xffff0000, "red@1.5")
        assertColor(0x00ff0000, "red@-0.5")
        assertColor(0xffff0000, "red@1e99999999999999999999")
        assertColor(0x00ff0000, "red@0e99999999999999999999")
        assertColor(0x00ff0000, "red@1e-99999999999999999999")
    }

    @Test
    fun `outer ASCII whitespace is accepted without discarding internal name characters`() {
        assertColor(0xfff0f8ff, " \tAlice Blue\r\n")
        assertColor(0x8030a070, "\t#3a7 \t@ +.5 \r\n")
        assertColor(0xff0080ff, "rgbi: 0 / .5 / 1 ")
        assertInvalid("alice  blue")
        assertInvalid("alice\tblue")
        assertInvalid("al iceblue")
        assertInvalid("\u00a0red")
    }

    @Test
    fun `empty unknown malformed and non ASCII values are rejected`() {
        val values =
            listOf(
                "",
                " ",
                "?",
                "dynamic",
                "none",
                "transparent",
                "rebeccapurple",
                "grey101",
                "red extra",
                "#",
                "#12",
                "#1234",
                "#12345",
                "#1234567",
                "#12345678",
                "#1234567890",
                "#12345678901",
                "#1234567890123",
                "#12g",
                "#12345g",
                "#12345678g",
                "#123456789abg",
                "＃123456",
                "rgb:",
                "rgb:1/2",
                "rgb:1/2/",
                "rgb:/2/3",
                "rgb:1//3",
                "rgb:1/2/3/4",
                "rgb:12345/2/3",
                "rgb:g/2/3",
                "rgb: 1/2/3",
                "rgb:1/2/3suffix",
                "rgbi:",
                "rgbi:0/0",
                "rgbi:0/0/0/0",
                "rgbi:./0/0",
                "rgbi:+/0/0",
                "rgbi:1.2.3/0/0",
                "rgbi:1e/0/0",
                "rgbi:1e+/0/0",
                "rgbi:1ee2/0/0",
                "rgbi:1e-2x/0/0",
                "rgbi:--1/0/0",
                "rgbi:1e1.5/0/0",
                "rgbi:NaN/0/0",
                "rgbi:Infinity/0/0",
                "rgbi:0x1p-1/0/0",
                "rgbi:1,5/0/0",
                "rgbi:1 0/0/0",
                "@.5",
                "red@",
                "red@.5@.5",
                "red@NaN",
                "red@inf",
                "red@1e",
                "red@1e+",
                "red@1e99999999999999999999x",
                "red@1/2",
                "red@0x1p-1",
                "red\u0000",
                "réd",
            )
        for (value in values) assertInvalid(value)
    }

    @Test
    fun `parsing is confined to the supplied byte range`() {
        val payload = "prefixred@.5suffix".encodeToByteArray()
        assertEquals(0x80ff0000L, KittyColorParser.parse(payload, 6, 12))
        assertEquals(0xffff0000L, KittyColorParser.parse(payload, 6, 9))
        assertEquals(-1L, KittyColorParser.parse(payload, -1, 9))
        assertEquals(-1L, KittyColorParser.parse(payload, 6, payload.size + 1))
        assertEquals(-1L, KittyColorParser.parse(payload, 9, 6))
        assertEquals(-1L, KittyColorParser.parse(payload, 6, 6))
        assertEquals(-1L, KittyColorParser.parse(byteArrayOf(), 0, 0))
        assertEquals(-1L, KittyColorParser.parse(byteArrayOf(0xff.toByte()), 0, 1))
    }

    private fun assertColor(
        expected: Long,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        assertEquals(expected, KittyColorParser.parse(bytes, 0, bytes.size), value)
    }

    private fun assertInvalid(value: String) = assertColor(-1L, value)

    private fun assertDecimalChannel(
        expected: Int,
        value: String,
    ) {
        assertColor(expected * 0x01010101L, "rgbi:$value/$value/$value@$value")
    }
}
