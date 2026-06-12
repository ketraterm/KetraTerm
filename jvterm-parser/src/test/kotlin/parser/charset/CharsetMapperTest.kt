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
package com.gagik.parser.charset

import com.gagik.parser.ansi.RecordingTerminalCommandSink
import com.gagik.parser.fixture.ParserEvents.writeCodepoint
import com.gagik.parser.runtime.ParserState
import com.gagik.parser.text.PrintableProcessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CharsetMapper")
class CharsetMapperTest {
    // ----- Helpers ----------------------------------------------------------

    private fun map(
        state: ParserState,
        char: Char,
    ): Int = CharsetMapper.map(state, char.code)

    private fun assertMaps(
        state: ParserState,
        input: Int,
        expected: Int,
    ) {
        assertEquals(expected, CharsetMapper.map(state, input), "input=${input.toString(16)}")
    }

    private fun assertAllPrintableAsciiMapToIdentity(state: ParserState) {
        for (codepoint in 0x20..0x7e) {
            assertMaps(state, codepoint, codepoint)
        }
    }

    // ----- Default mapping --------------------------------------------------

    @Nested
    @DisplayName("default ASCII mapping")
    inner class DefaultAsciiMapping {
        @Test
        fun `new parser state maps every GL printable byte to itself`() {
            val state = ParserState()

            assertAllPrintableAsciiMapToIdentity(state)
        }

        @Test
        fun `new parser state starts with ASCII in all charset slots and GL on G0`() {
            val state = ParserState()

            assertAll(
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), state.charsets) },
                { assertEquals(0, state.glSlot) },
                { assertEquals(2, state.grSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }

        @Test
        fun `non GL codepoints pass through unchanged before charset lookup`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 0)

            val nonGlCodepoints =
                intArrayOf(
                    0x00,
                    0x1f,
                    0x7f,
                    0x80,
                    0x9f,
                    0x00a0,
                    0x0301,
                    0x20ac,
                    0x1f600,
                )

            for (codepoint in nonGlCodepoints) {
                assertMaps(state, codepoint, codepoint)
            }
        }

        @Test
        fun `mapping printable ASCII does not mutate locking shift or charset designations`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 1)
            CharsetMapper.lockingShiftG1(state)

            assertEquals(DEC_SPECIAL_GRAPHICS['q'.code], CharsetMapper.map(state, 'q'.code))

            assertAll(
                { assertEquals(1, state.glSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertEquals(ParserState.CHARSET_ASCII, state.charsets[0]) },
                { assertEquals(ParserState.CHARSET_DEC_SPECIAL_GRAPHICS, state.charsets[1]) },
                { assertEquals(ParserState.CHARSET_ASCII, state.charsets[2]) },
                { assertEquals(ParserState.CHARSET_ASCII, state.charsets[3]) },
            )
        }
    }

    // ----- DEC Special Graphics -------------------------------------------

    @Nested
    @DisplayName("DEC Special Graphics")
    inner class DecSpecialGraphicsMapping {
        @Test
        fun `DEC Special Graphics maps every documented replacement character`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 0)

            for ((input, expected) in DEC_SPECIAL_GRAPHICS) {
                assertMaps(state, input, expected)
            }
        }

        @Test
        fun `direct DEC Special Graphics table maps every documented replacement character`() {
            for ((input, expected) in DEC_SPECIAL_GRAPHICS) {
                assertEquals(expected, DecSpecialGraphics.map(input), "input=${input.toChar()}")
            }
        }

        @Test
        fun `DEC Special Graphics leaves every other GL printable byte unchanged`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 0)

            for (codepoint in 0x20..0x7e) {
                val expected = DEC_SPECIAL_GRAPHICS[codepoint] ?: codepoint
                assertMaps(state, codepoint, expected)
            }
        }

        @Test
        fun `DEC Special Graphics line drawing aliases match VT compatible meanings`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 0)

            assertAll(
                { assertEquals(0x2500, map(state, 'q')) },
                { assertEquals(0x2502, map(state, 'x')) },
                { assertEquals(0x250c, map(state, 'l')) },
                { assertEquals(0x2510, map(state, 'k')) },
                { assertEquals(0x2514, map(state, 'm')) },
                { assertEquals(0x2518, map(state, 'j')) },
                { assertEquals(0x253c, map(state, 'n')) },
            )
        }

        @Test
        fun `ASCII designation restores identity mapping after DEC Special Graphics`() {
            val state = ParserState()

            CharsetMapper.designateDecSpecialGraphics(state, 0)
            assertEquals(0x2500, map(state, 'q'))

            CharsetMapper.designateAscii(state, 0)

            assertAllPrintableAsciiMapToIdentity(state)
        }
    }

    // ----- Designation ------------------------------------------------------

    @Nested
    @DisplayName("designation")
    inner class Designation {
        @Test
        fun `designate writes only the selected G slot`() {
            val state = ParserState()

            CharsetMapper.designate(state, slot = 2, charset = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS)

            assertArrayEquals(
                intArrayOf(
                    ParserState.CHARSET_ASCII,
                    ParserState.CHARSET_ASCII,
                    ParserState.CHARSET_DEC_SPECIAL_GRAPHICS,
                    ParserState.CHARSET_ASCII,
                ),
                state.charsets,
            )
        }

        @Test
        fun `designateAscii and designateDecSpecialGraphics validate all G slots`() {
            val state = ParserState()

            for (slot in 0..3) {
                CharsetMapper.designateDecSpecialGraphics(state, slot)
                assertEquals(ParserState.CHARSET_DEC_SPECIAL_GRAPHICS, state.charsets[slot])

                CharsetMapper.designateAscii(state, slot)
                assertEquals(ParserState.CHARSET_ASCII, state.charsets[slot])
            }
        }

        @Test
        fun `unknown charset id is treated as identity mapping`() {
            val state = ParserState()
            CharsetMapper.designate(state, slot = 0, charset = 999)

            assertAllPrintableAsciiMapToIdentity(state)
        }

        @Test
        fun `designation rejects slots below G0 and above G3`() {
            val state = ParserState()

            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    CharsetMapper.designate(state, slot = -1, charset = ParserState.CHARSET_ASCII)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    CharsetMapper.designate(state, slot = 4, charset = ParserState.CHARSET_ASCII)
                }

            assertAll(
                { assertEquals("charset slot out of range: -1", below.message) },
                { assertEquals("charset slot out of range: 4", above.message) },
            )
        }

        @Test
        fun `high level designation helpers reject invalid slots through the same invariant`() {
            val state = ParserState()

            val ascii =
                assertThrows(IllegalArgumentException::class.java) {
                    CharsetMapper.designateAscii(state, slot = Int.MIN_VALUE)
                }
            val dec =
                assertThrows(IllegalArgumentException::class.java) {
                    CharsetMapper.designateDecSpecialGraphics(state, slot = Int.MAX_VALUE)
                }

            assertAll(
                { assertEquals("charset slot out of range: ${Int.MIN_VALUE}", ascii.message) },
                { assertEquals("charset slot out of range: ${Int.MAX_VALUE}", dec.message) },
            )
        }
    }

    // ----- Locking shifts ---------------------------------------------------

    @Nested
    @DisplayName("locking shifts")
    inner class LockingShifts {
        @Test
        fun `lockingShiftG0 selects G0 for GL and clears pending single shift`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 1)
            CharsetMapper.lockingShiftG1(state)
            CharsetMapper.singleShiftG2(state)

            CharsetMapper.lockingShiftG0(state)

            assertAll(
                { assertEquals(0, state.glSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertEquals('q'.code, map(state, 'q')) },
            )
        }

        @Test
        fun `lockingShiftG1 selects G1 for GL and clears pending single shift`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 1)
            CharsetMapper.singleShiftG2(state)

            CharsetMapper.lockingShiftG1(state)

            assertAll(
                { assertEquals(1, state.glSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertEquals(0x2500, map(state, 'q')) },
            )
        }

        @Test
        fun `locking shifts do not change charset designations or GR slot`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 1)
            CharsetMapper.designateDecSpecialGraphics(state, 2)
            state.grSlot = 3

            CharsetMapper.lockingShiftG1(state)
            CharsetMapper.lockingShiftG0(state)

            assertAll(
                { assertArrayEquals(intArrayOf(0, 1, 1, 0), state.charsets) },
                { assertEquals(3, state.grSlot) },
            )
        }

        @Test
        fun `G1 designation is dormant until locking shift selects it`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 1)

            assertEquals('q'.code, map(state, 'q'))

            CharsetMapper.lockingShiftG1(state)

            assertEquals(0x2500, map(state, 'q'))
        }
    }

    // ----- Single shifts ----------------------------------------------------

    @Nested
    @DisplayName("single shifts")
    inner class SingleShifts {
        @Test
        fun `singleShiftG2 applies G2 to exactly one GL printable codepoint`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 2)

            CharsetMapper.singleShiftG2(state)

            assertAll(
                { assertEquals(2, state.singleShiftSlot) },
                { assertEquals(0x2500, map(state, 'q')) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertEquals('q'.code, map(state, 'q')) },
            )
        }

        @Test
        fun `singleShiftG3 applies G3 to exactly one GL printable codepoint`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 3)

            CharsetMapper.singleShiftG3(state)

            assertAll(
                { assertEquals(3, state.singleShiftSlot) },
                { assertEquals(0x2502, map(state, 'x')) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertEquals('x'.code, map(state, 'x')) },
            )
        }

        @Test
        fun `single shift uses designated ASCII slot as identity and still consumes the shift`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 0)
            CharsetMapper.designateAscii(state, 2)

            CharsetMapper.singleShiftG2(state)

            assertAll(
                { assertEquals('q'.code, map(state, 'q')) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertEquals(0x2500, map(state, 'q')) },
            )
        }

        @Test
        fun `single shift is not consumed by control DEL C1 combining or Unicode codepoints`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 2)

            CharsetMapper.singleShiftG2(state)

            val nonGlCodepoints = intArrayOf(0x00, 0x1f, 0x7f, 0x80, 0x0301, 0x20ac, 0x1f600)
            for (codepoint in nonGlCodepoints) {
                assertMaps(state, codepoint, codepoint)
                assertEquals(2, state.singleShiftSlot, "single shift consumed by ${codepoint.toString(16)}")
            }

            assertAll(
                { assertEquals(0x2500, map(state, 'q')) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }

        @Test
        fun `later single shift replaces earlier pending single shift`() {
            val state = ParserState()
            CharsetMapper.designateAscii(state, 2)
            CharsetMapper.designateDecSpecialGraphics(state, 3)

            CharsetMapper.singleShiftG2(state)
            CharsetMapper.singleShiftG3(state)

            assertAll(
                { assertEquals(3, state.singleShiftSlot) },
                { assertEquals(0x2500, map(state, 'q')) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }

        @Test
        fun `single shift temporarily overrides active locking shift without changing it`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 1)
            CharsetMapper.designateAscii(state, 2)
            CharsetMapper.lockingShiftG1(state)

            CharsetMapper.singleShiftG2(state)

            assertAll(
                { assertEquals('q'.code, map(state, 'q')) },
                { assertEquals(1, state.glSlot) },
                { assertEquals(0x2500, map(state, 'q')) },
            )
        }
    }

    // ----- Printable processor integration --------------------------------

    @Nested
    @DisplayName("PrintableProcessor integration")
    inner class PrintableProcessorIntegration {
        @Test
        fun `ASCII printable ingress is charset mapped before terminal sink output`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)
            CharsetMapper.designateDecSpecialGraphics(state, 0)

            processor.acceptAsciiByte(state, 'q'.code)
            processor.flush(state)

            assertEquals(listOf(writeCodepoint(0x2500)), sink.events)
        }

        @Test
        fun `single shift is honored by printable processor for one ASCII printable byte`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)
            CharsetMapper.designateDecSpecialGraphics(state, 2)

            CharsetMapper.singleShiftG2(state)
            processor.acceptAsciiByte(state, 'x'.code)
            processor.acceptAsciiByte(state, 'x'.code)
            processor.flush(state)

            assertAll(
                { assertEquals(listOf(writeCodepoint(0x2502), writeCodepoint('x'.code)), sink.events) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }

        @Test
        fun `UTF-8 Unicode scalars are not remapped by DEC Special Graphics designation`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)
            CharsetMapper.designateDecSpecialGraphics(state, 0)

            processor.acceptDecodedCodepoint(state, 0x20ac)
            processor.flush(state)

            assertEquals(listOf(writeCodepoint(0x20ac)), sink.events)
        }

        @Test
        fun `resetCharsetState returns printable processor mapping to ASCII identity`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)
            CharsetMapper.designateDecSpecialGraphics(state, 0)
            state.resetCharsetState()

            processor.acceptAsciiByte(state, 'q'.code)
            processor.flush(state)

            assertAll(
                { assertEquals(listOf(writeCodepoint('q'.code)), sink.events) },
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), state.charsets) },
                { assertEquals(0, state.glSlot) },
                { assertEquals(2, state.grSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }

        @Test
        fun `GR designation does not affect GL printable mapping`() {
            val state = ParserState()
            CharsetMapper.designateDecSpecialGraphics(state, 2)
            state.grSlot = 2

            assertAll(
                { assertEquals(2, state.grSlot) },
                { assertFalse(state.glSlot == state.grSlot) },
                { assertEquals('q'.code, map(state, 'q')) },
            )
        }
    }

    companion object {
        private val DEC_SPECIAL_GRAPHICS: Map<Int, Int> =
            linkedMapOf(
                '`'.code to 0x25c6,
                'a'.code to 0x2592,
                'b'.code to 0x2409,
                'c'.code to 0x240c,
                'd'.code to 0x240d,
                'e'.code to 0x240a,
                'f'.code to 0x00b0,
                'g'.code to 0x00b1,
                'h'.code to 0x2424,
                'i'.code to 0x240b,
                'j'.code to 0x2518,
                'k'.code to 0x2510,
                'l'.code to 0x250c,
                'm'.code to 0x2514,
                'n'.code to 0x253c,
                'o'.code to 0x23ba,
                'p'.code to 0x23bb,
                'q'.code to 0x2500,
                'r'.code to 0x23bc,
                's'.code to 0x23bd,
                't'.code to 0x251c,
                'u'.code to 0x2524,
                'v'.code to 0x2534,
                'w'.code to 0x252c,
                'x'.code to 0x2502,
                'y'.code to 0x2264,
                'z'.code to 0x2265,
                '{'.code to 0x03c0,
                '|'.code to 0x2260,
                '}'.code to 0x00a3,
                '~'.code to 0x00b7,
            )
    }
}
