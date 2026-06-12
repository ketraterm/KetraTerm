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
package io.github.jvterm.parser.utf8

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Utf8Decoder")
class Utf8DecoderTest {
    // ----- Helpers ----------------------------------------------------------

    private fun decodeAll(
        vararg byteValues: Int,
        decoder: Utf8Decoder = Utf8Decoder(),
        flushEndOfInput: Boolean = false,
    ): List<Int> {
        val output = ArrayList<Int>()
        var index = 0
        var guard = 0

        while (index < byteValues.size) {
            guard++
            check(guard < 10_000) { "decoder reprocess loop did not converge" }

            val result = decoder.accept(byteValues[index])
            if (Utf8DecodeResult.hasOutput(result)) {
                output += Utf8DecodeResult.codepoint(result)
            }

            if (!Utf8DecodeResult.shouldReprocessCurrentByte(result)) {
                index++
            }
        }

        if (flushEndOfInput) {
            val result = decoder.flushEndOfInput()
            if (Utf8DecodeResult.hasOutput(result)) {
                output += Utf8DecodeResult.codepoint(result)
            }
        }

        return output
    }

    private fun assertNoOutput(result: Int) {
        assertAll(
            { assertEquals(Utf8DecodeResult.NONE, result) },
            { assertFalse(Utf8DecodeResult.hasOutput(result)) },
            { assertFalse(Utf8DecodeResult.shouldReprocessCurrentByte(result)) },
        )
    }

    private fun assertEmit(
        result: Int,
        expectedCodepoint: Int,
        expectedReprocess: Boolean = false,
    ) {
        assertAll(
            { assertTrue(Utf8DecodeResult.hasOutput(result), "has output") },
            { assertEquals(expectedCodepoint, Utf8DecodeResult.codepoint(result), "codepoint") },
            { assertEquals(expectedReprocess, Utf8DecodeResult.shouldReprocessCurrentByte(result), "reprocess") },
        )
    }

    private fun replacement(): Int = Utf8Decoder.REPLACEMENT_CODEPOINT

    // ----- Packed result ----------------------------------------------------

    @Nested
    @DisplayName("packed result")
    inner class PackedResult {
        @Test
        fun `NONE means no output and no reprocess`() {
            assertNoOutput(Utf8DecodeResult.NONE)
        }

        @Test
        fun `U+0000 is a valid emitted codepoint distinct from NONE`() {
            val result = Utf8DecodeResult.emit(0)

            assertEmit(result, 0)
        }

        @Test
        fun `emitAndReprocess sets both output and reprocess flags`() {
            val result = Utf8DecodeResult.emitAndReprocess(replacement())

            assertEmit(result, replacement(), expectedReprocess = true)
        }

        @Test
        fun `maximum Unicode scalar is preserved by result packing`() {
            val result = Utf8DecodeResult.emit(0x10FFFF)

            assertEmit(result, 0x10FFFF)
        }

        @Test
        fun `packed result rejects codepoints outside Unicode range`() {
            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    Utf8DecodeResult.emit(-1)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    Utf8DecodeResult.emitAndReprocess(0x11_0000)
                }

            assertAll(
                { assertEquals("invalid codepoint: -1", below.message) },
                { assertEquals("invalid codepoint: 1114112", above.message) },
            )
        }
    }

    // ----- Input validation -------------------------------------------------

    @Nested
    @DisplayName("input validation")
    inner class InputValidation {
        @Test
        fun `accept rejects byte values outside unsigned byte range`() {
            val decoder = Utf8Decoder()

            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    decoder.accept(-1)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    decoder.accept(256)
                }

            assertAll(
                { assertEquals("byteValue out of range: -1", below.message) },
                { assertEquals("byteValue out of range: 256", above.message) },
            )
        }
    }

    // ----- Valid UTF-8 ------------------------------------------------------

    @Nested
    @DisplayName("valid UTF-8")
    inner class ValidUtf8 {
        @Test
        fun `all ASCII bytes emit immediately including NUL and DEL`() {
            val decoder = Utf8Decoder()

            for (byteValue in 0x00..0x7F) {
                assertEmit(decoder.accept(byteValue), byteValue)
            }
        }

        @Test
        fun `two-byte boundary scalars decode`() {
            assertEquals(listOf(0x0080), decodeAll(0xC2, 0x80))
            assertEquals(listOf(0x07FF), decodeAll(0xDF, 0xBF))
        }

        @Test
        fun `three-byte boundary scalars decode`() {
            assertEquals(listOf(0x0800), decodeAll(0xE0, 0xA0, 0x80))
            assertEquals(listOf(0xD7FF), decodeAll(0xED, 0x9F, 0xBF))
            assertEquals(listOf(0xE000), decodeAll(0xEE, 0x80, 0x80))
            assertEquals(listOf(0xFFFF), decodeAll(0xEF, 0xBF, 0xBF))
        }

        @Test
        fun `four-byte boundary scalars decode`() {
            assertEquals(listOf(0x010000), decodeAll(0xF0, 0x90, 0x80, 0x80))
            assertEquals(listOf(0x10FFFF), decodeAll(0xF4, 0x8F, 0xBF, 0xBF))
        }

        @Test
        fun `representative multilingual scalars decode`() {
            assertEquals(
                listOf('A'.code, 0x00A2, 0x20AC, 0x1F600),
                decodeAll(
                    'A'.code,
                    0xC2,
                    0xA2,
                    0xE2,
                    0x82,
                    0xAC,
                    0xF0,
                    0x9F,
                    0x98,
                    0x80,
                ),
            )
        }

        @Test
        fun `valid multibyte sequences produce NONE until the final byte`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xF0))
            assertNoOutput(decoder.accept(0x9F))
            assertNoOutput(decoder.accept(0x98))
            assertEmit(decoder.accept(0x80), 0x1F600)
        }

        @Test
        fun `chunked valid stream keeps state across accept calls`() {
            val decoder = Utf8Decoder()

            assertEquals(listOf('a'.code), decodeAll('a'.code, 0xE2, decoder = decoder))
            assertEquals(emptyList<Int>(), decodeAll(0x82, decoder = decoder))
            assertEquals(listOf(0x20AC, 'b'.code), decodeAll(0xAC, 'b'.code, decoder = decoder))
        }
    }

    // ----- Invalid leading bytes -------------------------------------------

    @Nested
    @DisplayName("invalid leading bytes")
    inner class InvalidLeadingBytes {
        @Test
        fun `lone continuation bytes are consumed and emit replacement`() {
            for (byteValue in 0x80..0xBF) {
                val decoder = Utf8Decoder()
                assertEmit(decoder.accept(byteValue), replacement())
            }
        }

        @Test
        fun `C0 and C1 are rejected as overlong two-byte leads`() {
            assertEquals(listOf(replacement()), decodeAll(0xC0))
            assertEquals(listOf(replacement()), decodeAll(0xC1))
        }

        @Test
        fun `F5 through FF are rejected as leading bytes above Unicode range`() {
            for (byteValue in 0xF5..0xFF) {
                val decoder = Utf8Decoder()
                assertEmit(decoder.accept(byteValue), replacement())
            }
        }

        @Test
        fun `invalid leading byte followed by ASCII does not hide the ASCII`() {
            assertEquals(listOf(replacement(), 'A'.code), decodeAll(0xFF, 'A'.code))
        }
    }

    // ----- Overlong sequences ----------------------------------------------

    @Nested
    @DisplayName("overlong sequences")
    inner class OverlongSequences {
        @Test
        fun `two-byte overlong forms emit replacement for each invalid byte`() {
            assertEquals(listOf(replacement(), replacement()), decodeAll(0xC0, 0x80))
            assertEquals(listOf(replacement(), replacement()), decodeAll(0xC1, 0xBF))
        }

        @Test
        fun `three-byte overlong lower-bound violation consumes the bad continuation byte`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xE0))
            val result = decoder.accept(0x80)

            assertEmit(result, replacement(), expectedReprocess = false)
            assertEquals(listOf('A'.code), decodeAll('A'.code, decoder = decoder))
        }

        @Test
        fun `three-byte overlong form emits replacements for consumed invalid sequence and trailing continuation`() {
            assertEquals(listOf(replacement(), replacement()), decodeAll(0xE0, 0x80, 0x80))
        }

        @Test
        fun `four-byte overlong form emits replacements for consumed invalid sequence and trailing continuations`() {
            assertEquals(listOf(replacement(), replacement(), replacement()), decodeAll(0xF0, 0x80, 0x80, 0x80))
        }
    }

    // ----- Surrogates and upper bound --------------------------------------

    @Nested
    @DisplayName("surrogates and upper bound")
    inner class SurrogatesAndUpperBound {
        @Test
        fun `UTF-8 encoded surrogate range is rejected`() {
            assertEquals(listOf(replacement(), replacement()), decodeAll(0xED, 0xA0, 0x80))
            assertEquals(listOf(replacement(), replacement()), decodeAll(0xED, 0xBF, 0xBF))
        }

        @Test
        fun `first scalar after surrogate range is accepted`() {
            assertEquals(listOf(0xE000), decodeAll(0xEE, 0x80, 0x80))
        }

        @Test
        fun `values above U+10FFFF are rejected`() {
            assertEquals(listOf(replacement(), replacement(), replacement()), decodeAll(0xF4, 0x90, 0x80, 0x80))
            assertEquals(
                listOf(replacement(), replacement(), replacement(), replacement()),
                decodeAll(0xF5, 0x80, 0x80, 0x80),
            )
        }
    }

    // ----- Reprocess behavior ----------------------------------------------

    @Nested
    @DisplayName("malformed sequence reprocess behavior")
    inner class MalformedSequenceReprocessBehavior {
        @Test
        fun `non-continuation after two-byte lead emits replacement and asks to reprocess current byte`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xC2))
            val malformed = decoder.accept('A'.code)

            assertEmit(malformed, replacement(), expectedReprocess = true)
            assertEmit(decoder.accept('A'.code), 'A'.code)
        }

        @Test
        fun `non-continuation after partial three-byte sequence is preserved by caller reprocessing`() {
            assertEquals(listOf(replacement(), 'A'.code), decodeAll(0xE2, 0x82, 'A'.code))
        }

        @Test
        fun `new UTF-8 lead after malformed pending sequence is reprocessed as a new sequence`() {
            assertEquals(
                listOf(replacement(), 0x20AC),
                decodeAll(0xF0, 0x9F, 0xE2, 0x82, 0xAC),
            )
        }

        @Test
        fun `ASCII control after malformed pending sequence is preserved for terminal control handling`() {
            assertEquals(listOf(replacement(), 0x1B), decodeAll(0xC2, 0x1B))
        }

        @Test
        fun `continuation-range byte that violates current bounds is consumed and not reprocessed`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xF4))
            val malformed = decoder.accept(0x90)

            assertEmit(malformed, replacement(), expectedReprocess = false)
            assertEquals(listOf('X'.code), decodeAll('X'.code, decoder = decoder))
        }
    }

    // ----- End of input and reset ------------------------------------------

    @Nested
    @DisplayName("end of input and reset")
    inner class EndOfInputAndReset {
        @Test
        fun `flush with no pending sequence emits nothing`() {
            assertNoOutput(Utf8Decoder().flushEndOfInput())
        }

        @Test
        fun `flush after incomplete multibyte sequence emits one replacement`() {
            assertEquals(listOf(replacement()), decodeAll(0xC2, flushEndOfInput = true))
            assertEquals(listOf(replacement()), decodeAll(0xE2, 0x82, flushEndOfInput = true))
            assertEquals(listOf(replacement()), decodeAll(0xF0, 0x9F, 0x98, flushEndOfInput = true))
        }

        @Test
        fun `flush resets decoder so later bytes decode normally`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xE2))
            assertEmit(decoder.flushEndOfInput(), replacement())
            assertEmit(decoder.accept('A'.code), 'A'.code)
        }

        @Test
        fun `reset drops pending malformed state without emitting replacement`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xE2))
            decoder.reset()

            assertEquals(listOf('A'.code), decodeAll('A'.code, decoder = decoder))
        }

        @Test
        fun `reset after malformed sequence allows a fresh valid multibyte sequence`() {
            val decoder = Utf8Decoder()

            assertNoOutput(decoder.accept(0xF0))
            decoder.reset()

            assertEquals(listOf(0x20AC), decodeAll(0xE2, 0x82, 0xAC, decoder = decoder))
        }
    }

    // ----- Custom replacement ----------------------------------------------

    @Nested
    @DisplayName("custom replacement")
    inner class CustomReplacement {
        @Test
        fun `custom replacement codepoint is used for malformed bytes`() {
            val decoder = Utf8Decoder(replacementCodepoint = '?'.code)

            assertEquals(listOf('?'.code, 'A'.code), decodeAll(0xC2, 'A'.code, decoder = decoder))
        }

        @Test
        fun `custom replacement codepoint is used for truncated input`() {
            val decoder = Utf8Decoder(replacementCodepoint = '?'.code)

            assertEquals(listOf('?'.code), decodeAll(0xE2, 0x82, decoder = decoder, flushEndOfInput = true))
        }
    }
}
