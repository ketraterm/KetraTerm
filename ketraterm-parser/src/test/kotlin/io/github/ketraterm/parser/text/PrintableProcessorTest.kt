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
package io.github.ketraterm.parser.text

import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import io.github.ketraterm.parser.fixture.ParserEvents.writeCluster
import io.github.ketraterm.parser.fixture.ParserEvents.writeCodepoint
import io.github.ketraterm.parser.runtime.ParserState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PrintableProcessor")
class PrintableProcessorTest {
    // ----- Helpers ----------------------------------------------------------

    private data class Fixture(
        val state: ParserState = ParserState(),
        val sink: RecordingTerminalCommandSink = RecordingTerminalCommandSink(),
    ) {
        val processor = PrintableProcessor(sink)

        fun acceptAscii(text: String) {
            for (ch in text) {
                processor.acceptAsciiByte(state, ch.code)
            }
        }

        fun acceptUtf8Codepoints(vararg codepoints: Int) {
            for (codepoint in codepoints) {
                processor.acceptDecodedCodepoint(state, codepoint)
            }
        }

        fun flush() {
            processor.flush(state)
        }

        fun reset() {
            processor.reset(state)
        }
    }

    // ----- Input validation -------------------------------------------------

    @Nested
    @DisplayName("input validation")
    inner class InputValidation {
        @Test
        fun `acceptAsciiByte accepts printable 7-bit ASCII only`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 0x20)
            f.processor.acceptAsciiByte(f.state, 0x7E)

            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    f.processor.acceptAsciiByte(f.state, 0x1F)
                }
            val del =
                assertThrows(IllegalArgumentException::class.java) {
                    f.processor.acceptAsciiByte(f.state, 0x7F)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    f.processor.acceptAsciiByte(f.state, 0x80)
                }

            assertAll(
                { assertEquals("byteValue is not printable ASCII: 31", below.message) },
                { assertEquals("byteValue is not printable ASCII: 127", del.message) },
                { assertEquals("byteValue is not printable ASCII: 128", above.message) },
            )
        }

        @Test
        fun `acceptDecodedCodepoint accepts Unicode scalar range only`() {
            val f = Fixture()

            f.processor.acceptDecodedCodepoint(f.state, 0)
            f.processor.acceptDecodedCodepoint(f.state, 0x10FFFF)

            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    f.processor.acceptDecodedCodepoint(f.state, -1)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    f.processor.acceptDecodedCodepoint(f.state, 0x11_0000)
                }

            assertAll(
                { assertEquals("invalid codepoint: -1", below.message) },
                { assertEquals("invalid codepoint: 1114112", above.message) },
            )
        }
    }

    // ----- ASCII printable flow --------------------------------------------

    @Nested
    @DisplayName("ASCII printable flow")
    inner class AsciiPrintableFlow {
        @Test
        fun `plain ASCII is emitted in order once cluster boundaries are known`() {
            val f = Fixture()

            f.acceptAscii("abc")
            f.flush()

            assertAll(
                { assertEquals(listOf(writeCodepoint('a'.code), writeCodepoint('b'.code), writeCodepoint('c'.code)), f.sink.events) },
                { assertEquals(0, f.state.clusterLength) },
            )
        }

        @Test
        fun `ASCII may be buffered until flush`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 'a'.code)

            assertAll(
                { assertTrue(f.sink.events.isEmpty()) },
                { assertEquals(1, f.state.clusterLength) },
            )
        }

        @Test
        fun `flush emits the active ASCII scalar once and clears cluster state`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 'x'.code)
            f.flush()
            f.flush()

            assertAll(
                { assertEquals(listOf(writeCodepoint('x'.code)), f.sink.events) },
                { assertEquals(0, f.state.clusterLength) },
                { assertFalse(f.state.prevWasZwj) },
                { assertEquals(0, f.state.regionalIndicatorParity) },
            )
        }

        @Test
        fun `flush emits pending ASCII scalar`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 'z'.code)
            f.flush()

            assertEquals(listOf(writeCodepoint('z'.code)), f.sink.events)
        }
    }

    // ----- Decoded printable flow ------------------------------------------

    @Nested
    @DisplayName("decoded printable flow")
    inner class DecodedPrintableFlow {
        @Test
        fun `decoded non-ASCII scalars print in order`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x00A2, 0x20AC, 0x1F600)
            f.flush()

            assertEquals(
                listOf(
                    writeCodepoint(0x00A2),
                    writeCodepoint(0x20AC),
                    writeCodepoint(0x1F600),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `decoded replacement is treated as ordinary printable input`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0xFFFD)
            f.flush()

            assertEquals(listOf(writeCodepoint(0xFFFD)), f.sink.events)
        }
    }

    // ----- Grapheme assembly ------------------------------------------------

    @Nested
    @DisplayName("grapheme assembly")
    inner class GraphemeAssembly {
        @Test
        fun `ASCII base followed by combining mark is emitted as one cluster`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 'e'.code)
            f.acceptUtf8Codepoints(0x0301)
            f.flush()

            assertEquals(listOf(writeCluster('e'.code, 0x0301)), f.sink.events)
        }

        @Test
        fun `non-ASCII base followed by combining mark is emitted as one cluster`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x03B1, 0x0301)
            f.flush()

            assertEquals(listOf(writeCluster(0x03B1, 0x0301)), f.sink.events)
        }

        @Test
        fun `Thai base followed by combining marks is emitted as one cluster`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x0E01, 0x0E34, 0x0E48)
            f.flush()

            assertEquals(listOf(writeCluster(0x0E01, 0x0E34, 0x0E48)), f.sink.events)
        }

        @Test
        fun `combining mark at start is emitted as its own scalar`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x0301)
            f.flush()

            assertEquals(listOf(writeCodepoint(0x0301)), f.sink.events)
        }

        @Test
        fun `variation selector stays with its base codepoint`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x2764, 0xFE0F)
            f.flush()

            assertEquals(listOf(writeCluster(0x2764, 0xFE0F)), f.sink.events)
        }

        @Test
        fun `supplement variation selector stays with its base codepoint`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x4E00, 0xE0100)
            f.flush()

            assertEquals(listOf(writeCluster(0x4E00, 0xE0100)), f.sink.events)
        }

        @Test
        fun `emoji ZWJ sequence is emitted as one cluster`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466)
            f.flush()

            assertEquals(
                listOf(writeCluster(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466)),
                f.sink.events,
            )
        }

        @Test
        fun `regional indicators are paired into flag clusters`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x1F1FA, 0x1F1F8, 0x1F1E8, 0x1F1E6, 0x1F1EF)
            f.flush()

            assertEquals(
                listOf(
                    writeCluster(0x1F1FA, 0x1F1F8),
                    writeCluster(0x1F1E8, 0x1F1E6),
                    writeCodepoint(0x1F1EF),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `Hangul Jamo L V T sequence is emitted as one cluster`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x1100, 0x1161, 0x11A8)
            f.flush()

            assertEquals(listOf(writeCluster(0x1100, 0x1161, 0x11A8)), f.sink.events)
        }

        @Test
        fun `ZWJ does not attach unrelated non emoji letters`() {
            val f = Fixture()

            f.acceptUtf8Codepoints('A'.code, 0x200D, 'B'.code)
            f.flush()

            assertEquals(
                listOf(
                    writeCluster('A'.code, 0x200D),
                    writeCodepoint('B'.code),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `CJK base with combining mark is emitted as one cluster`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x4E00, 0x0301)
            f.flush()

            assertEquals(listOf(writeCluster(0x4E00, 0x0301)), f.sink.events)
        }

        @Test
        fun `new base codepoint flushes prior cluster before starting the next one`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 'e'.code)
            f.acceptUtf8Codepoints(0x0301)
            f.processor.acceptAsciiByte(f.state, 'X'.code)
            f.flush()

            assertEquals(
                listOf(writeCluster('e'.code, 0x0301), writeCodepoint('X'.code)),
                f.sink.events,
            )
        }
    }

    // ----- Cluster capacity and state --------------------------------------

    @Nested
    @DisplayName("cluster capacity and state")
    inner class ClusterCapacityAndState {
        @Test
        fun `cluster buffer capacity flushes safely instead of writing out of bounds`() {
            val f = Fixture(state = ParserState(maxCluster = 2))

            f.acceptUtf8Codepoints(0x1F468, 0x200D, 0x1F469)
            f.flush()

            assertEquals(
                listOf(
                    writeCluster(0x1F468, 0x200D),
                    writeCodepoint(0x1F469),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `flush clears grapheme context after writing a cluster`() {
            val f = Fixture()

            f.acceptUtf8Codepoints(0x1F1FA, 0x1F1F8)
            f.flush()

            assertAll(
                { assertEquals(0, f.state.clusterLength) },
                { assertEquals(0, f.state.prevGraphemeBreakClass) },
                { assertFalse(f.state.prevWasZwj) },
                { assertFalse(f.state.zwjBeforeExtendedPictographic) },
                { assertFalse(f.state.lastNonExtendWasExtendedPictographic) },
                { assertEquals(0, f.state.regionalIndicatorParity) },
            )
        }

        @Test
        fun `reset drops pending cluster without writing it`() {
            val f = Fixture()

            f.processor.acceptAsciiByte(f.state, 'A'.code)
            f.reset()
            f.processor.acceptAsciiByte(f.state, 'B'.code)
            f.flush()

            assertAll(
                { assertEquals(listOf(writeCodepoint('B'.code)), f.sink.events) },
                { assertEquals(0, f.state.clusterLength) },
            )
        }
    }

    // ----- PrintableActionSink adapter -------------------------------------

    @Nested
    @DisplayName("PrintableProcessorActionSink")
    inner class PrintableProcessorActionSinkTest {
        @Test
        fun `adapter forwards ASCII and flush callbacks to processor`() {
            val f = Fixture()
            val actionSink = PrintableProcessorActionSink(f.processor)

            actionSink.onAsciiByte(f.state, 'A'.code)
            actionSink.flush(f.state)

            assertEquals(listOf(writeCodepoint('A'.code)), f.sink.events)
        }

        @Test
        fun `adapter rejects raw UTF-8 payload because TerminalParser owns decoding`() {
            val f = Fixture()
            val actionSink = PrintableProcessorActionSink(f.processor)

            val error =
                assertThrows(IllegalStateException::class.java) {
                    actionSink.onUtf8Byte(f.state, 0xE2)
                }

            assertEquals(
                "UTF-8 payload must be decoded by TerminalParser before printable processing",
                error.message,
            )
        }
    }
}
