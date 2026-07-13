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
package io.github.ketraterm.parser

import io.github.ketraterm.parser.ansi.AnsiState
import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.fixture.ParserEvents.appendToPreviousCluster
import io.github.ketraterm.parser.fixture.ParserEvents.writeCluster
import io.github.ketraterm.parser.fixture.ParserEvents.writeCodepoint
import io.github.ketraterm.parser.fixture.TerminalParserFixture
import io.github.ketraterm.parser.runtime.ParserState
import io.github.ketraterm.parser.utf8.Utf8Decoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TerminalParser")
class TerminalParserTest {
    // ----- API validation ---------------------------------------------------

    @Nested
    @DisplayName("API validation")
    inner class ApiValidation {
        @Test
        fun `accept rejects invalid offset and length ranges`() {
            val f = TerminalParserFixture()
            val bytes = byteArrayOf('a'.code.toByte())

            val negativeOffset =
                assertThrows(IllegalArgumentException::class.java) {
                    f.parser.accept(bytes, offset = -1, length = 1)
                }
            val negativeLength =
                assertThrows(IllegalArgumentException::class.java) {
                    f.parser.accept(bytes, offset = 0, length = -1)
                }
            val offsetOutOfRange =
                assertThrows(IllegalArgumentException::class.java) {
                    f.parser.accept(bytes, offset = 2, length = 0)
                }
            val sumOutOfRange =
                assertThrows(IllegalArgumentException::class.java) {
                    f.parser.accept(bytes, offset = 1, length = 1)
                }

            assertAll(
                { assertEquals("offset must be non-negative: -1", negativeOffset.message) },
                { assertEquals("length must be non-negative: -1", negativeLength.message) },
                { assertEquals("offset out of range: 2", offsetOutOfRange.message) },
                { assertEquals("offset + length out of range: offset=1 length=1 size=1", sumOutOfRange.message) },
            )
        }

        @Test
        fun `acceptByte rejects values outside unsigned byte range`() {
            val f = TerminalParserFixture()

            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    f.parser.acceptByte(-1)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    f.parser.acceptByte(256)
                }

            assertAll(
                { assertEquals("byteValue out of range: -1", below.message) },
                { assertEquals("byteValue out of range: 256", above.message) },
            )
        }

        @Test
        fun `empty chunks are accepted without side effects`() {
            val f = TerminalParserFixture()

            f.parser.accept(ByteArray(0))
            f.parser.accept(byteArrayOf('a'.code.toByte()), offset = 0, length = 0)

            assertAll(
                { assertTrue(f.sink.events.isEmpty()) },
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
            )
        }

        @Test
        fun `factory creates parser behind TerminalOutputParser contract`() {
            val sink = RecordingTerminalCommandSink()
            val parser: TerminalOutputParser = TerminalParsers.create(sink)

            parser.accept("A".encodeToByteArray())
            parser.endOfInput()

            assertEquals(listOf(writeCodepoint('A'.code)), sink.events)
        }

        @Test
        fun `offset and length process only the selected slice`() {
            val f = TerminalParserFixture()
            val bytes = "zabcx".encodeToByteArray()

            f.parser.accept(bytes, offset = 1, length = 3)
            f.endOfInput()

            assertEquals(
                listOf(writeCodepoint('a'.code), writeCodepoint('b'.code), writeCodepoint('c'.code)),
                f.sink.events,
            )
        }
    }

    // ----- Printable and UTF-8 ---------------------------------------------

    @Nested
    @DisplayName("printable and UTF-8")
    inner class PrintableAndUtf8 {
        @Test
        fun `plain ASCII publishes final scalar at host chunk boundary`() {
            val f = TerminalParserFixture()

            f.acceptAscii("abc")
            assertEquals(
                listOf(writeCodepoint('a'.code), writeCodepoint('b'.code), writeCodepoint('c'.code)),
                f.sink.events,
            )

            f.endOfInput()

            assertEquals(
                listOf(writeCodepoint('a'.code), writeCodepoint('b'.code), writeCodepoint('c'.code)),
                f.sink.events,
            )
        }

        @Test
        fun `UTF-8 scalar may be split across chunks`() {
            val f = TerminalParserFixture()

            f.parser.accept(byteArrayOf(0xC3.toByte()))
            assertTrue(f.sink.events.isEmpty())

            f.parser.accept(byteArrayOf(0xA9.toByte()))

            assertEquals(listOf(writeCodepoint(0x00E9)), f.sink.events)
        }

        @Test
        fun `emoji scalar is decoded through top level UTF-8 path`() {
            val f = TerminalParserFixture()

            f.acceptUtf8("\uD83D\uDE00")

            assertEquals(listOf(writeCodepoint(0x1F600)), f.sink.events)
        }

        @Test
        fun `endOfInput emits replacement for truncated UTF-8 sequence`() {
            val f = TerminalParserFixture()

            f.parser.accept(byteArrayOf(0xE2.toByte(), 0x82.toByte()))
            f.endOfInput()

            assertEquals(listOf(writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT)), f.sink.events)
        }

        @Test
        fun `malformed UTF-8 lead followed by ASCII emits replacement then replays ASCII`() {
            val f = TerminalParserFixture()

            f.acceptBytes(0xC3, 'A'.code)
            f.endOfInput()

            assertEquals(
                listOf(writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT), writeCodepoint('A'.code)),
                f.sink.events,
            )
        }

        @Test
        fun `malformed UTF-8 lead before ESC emits replacement then routes ESC sequence`() {
            val f = TerminalParserFixture()

            f.acceptBytes(0xC3, 0x1B, '['.code, 'A'.code)

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                {
                    assertEquals(
                        listOf(writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT), "cursorUp:1"),
                        f.sink.events,
                    )
                },
                { assertFalse(f.sink.events.contains(writeCodepoint(0x1B))) },
            )
        }

        @Test
        fun `truncated UTF-8 before chunked CSI emits one replacement then dispatches CSI`() {
            val f = TerminalParserFixture()

            f.parser.accept(byteArrayOf(0xF0.toByte(), 0x9F.toByte()))
            f.parser.accept(byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), 'B'.code.toByte()))

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                {
                    assertEquals(
                        listOf(writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT), "cursorDown:2"),
                        f.sink.events,
                    )
                },
                { assertFalse(f.sink.events.contains(writeCodepoint(0x1B))) },
            )
        }

        @Test
        fun `truncated UTF-8 before chunked OSC BEL emits one replacement then dispatches OSC`() {
            val f = TerminalParserFixture()

            f.parser.accept(byteArrayOf(0xE2.toByte()))
            f.parser.accept(byteArrayOf(0x1B, ']'.code.toByte(), '2'.code.toByte(), ';'.code.toByte()))
            f.parser.accept("title\u0007".encodeToByteArray())

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                {
                    assertEquals(
                        listOf(writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT), "setWindowTitle:title"),
                        f.sink.events,
                    )
                },
                { assertFalse(f.sink.events.contains(writeCodepoint(0x1B))) },
            )
        }

        @Test
        fun `truncated UTF-8 before split OSC ST emits one replacement then dispatches OSC`() {
            val f = TerminalParserFixture()

            f.parser.accept(byteArrayOf(0xC3.toByte()))
            f.parser.accept("\u001B]1;name\u001B".encodeToByteArray())
            f.parser.accept(byteArrayOf('\\'.code.toByte()))

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                {
                    assertEquals(
                        listOf(writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT), "setIconTitle:name"),
                        f.sink.events,
                    )
                },
                { assertFalse(f.sink.events.contains(writeCodepoint(0x1B))) },
            )
        }

        @Test
        fun `malformed UTF-8 recovers before every structural terminator across all chunk boundaries`() {
            val stream =
                byteArrayOf(0xC3.toByte()) +
                    "\u001B[A".encodeToByteArray() +
                    byteArrayOf(0xC3.toByte()) +
                    "\u001B]2;bel\u0007".encodeToByteArray() +
                    byteArrayOf(0xC3.toByte()) +
                    "\u001B]2;st\u001B\\".encodeToByteArray() +
                    byteArrayOf(0xC3.toByte()) +
                    "\u001BP\$qm\u001B\\".encodeToByteArray() +
                    byteArrayOf(0xC3.toByte()) +
                    "\u001B[31\u0018A".encodeToByteArray() +
                    byteArrayOf(0xC3.toByte()) +
                    "\u001B[31\u001AB".encodeToByteArray() +
                    byteArrayOf(0xC3.toByte())

            val expected =
                listOf(
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    "cursorUp:1",
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    "setWindowTitle:bel",
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    "setWindowTitle:st",
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    "queryStatusString:m",
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    writeCodepoint('A'.code),
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    writeCodepoint('B'.code),
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                )

            for (chunkSize in 1..stream.size) {
                val f = TerminalParserFixture()
                var offset = 0
                while (offset < stream.size) {
                    val length = minOf(chunkSize, stream.size - offset)
                    f.parser.accept(stream, offset, length)
                    offset += length
                }
                f.endOfInput()

                assertAll(
                    { assertEquals(expected, f.sink.events, "chunkSize=$chunkSize") },
                    { assertEquals(AnsiState.GROUND, f.state.fsmState, "chunkSize=$chunkSize") },
                )
            }
        }

        @Test
        fun `all host chunk boundaries preserve malformed UTF-8 CSI and OSC recovery`() {
            val stream =
                byteArrayOf(
                    'A'.code.toByte(),
                    0xE2.toByte(),
                    0x1B,
                    '['.code.toByte(),
                    '2'.code.toByte(),
                    'B'.code.toByte(),
                    0x1B,
                    ']'.code.toByte(),
                    '2'.code.toByte(),
                    ';'.code.toByte(),
                    't'.code.toByte(),
                    'i'.code.toByte(),
                    't'.code.toByte(),
                    'l'.code.toByte(),
                    'e'.code.toByte(),
                    0x07,
                    'Z'.code.toByte(),
                )
            val expected =
                listOf(
                    writeCodepoint('A'.code),
                    writeCodepoint(Utf8Decoder.REPLACEMENT_CODEPOINT),
                    "cursorDown:2",
                    "setWindowTitle:title",
                    writeCodepoint('Z'.code),
                )

            for (chunkSize in 1..stream.size) {
                val f = TerminalParserFixture()
                var offset = 0
                while (offset < stream.size) {
                    val length = minOf(chunkSize, stream.size - offset)
                    f.parser.accept(stream, offset, length)
                    offset += length
                }
                f.endOfInput()

                assertAll(
                    { assertEquals(AnsiState.GROUND, f.state.fsmState, "chunkSize=$chunkSize") },
                    { assertEquals(expected, f.sink.events, "chunkSize=$chunkSize") },
                    {
                        assertFalse(
                            f.sink.events.contains(writeCodepoint(0x1B)),
                            "ESC must remain structural; chunkSize=$chunkSize",
                        )
                    },
                )
            }
        }

        @Test
        fun `combining mark variation selector ZWJ and regional indicators form clusters`() {
            val f = TerminalParserFixture()

            f.acceptAscii("e")
            f.acceptUtf8("\u0301")
            f.acceptUtf8("\u2764\uFE0F")
            f.acceptUtf8("\uD83D\uDC68\u200D\uD83D\uDC69")
            f.acceptUtf8("\uD83C\uDDFA\uD83C\uDDF8")
            f.endOfInput()

            assertEquals(
                listOf(
                    writeCodepoint('e'.code),
                    appendToPreviousCluster(0x0301),
                    writeCluster(0x2764, 0xFE0F),
                    writeCluster(0x1F468, 0x200D, 0x1F469),
                    writeCluster(0x1F1FA, 0x1F1F8),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `combining mark split across host chunks extends already published cell`() {
            val f = TerminalParserFixture()

            f.acceptAscii("e")
            assertEquals(listOf(writeCodepoint('e'.code)), f.sink.events)

            f.acceptUtf8("\u0301")
            f.endOfInput()

            assertEquals(
                listOf(writeCodepoint('e'.code), appendToPreviousCluster(0x0301)),
                f.sink.events,
            )
        }
    }

    // ----- Structural routing ---------------------------------------------

    @Nested
    @DisplayName("structural routing")
    inner class StructuralRouting {
        @Test
        fun `C0 control flushes pending printable output before dispatch`() {
            val f = TerminalParserFixture()

            f.acceptAscii("A")
            f.acceptByte(0x07)

            assertEquals(listOf(writeCodepoint('A'.code), "bell"), f.sink.events)
        }

        @Test
        fun `ESC command flushes pending printable output before dispatch`() {
            val f = TerminalParserFixture()

            f.acceptAscii("A\u001B7")

            assertEquals(listOf(writeCodepoint('A'.code), "saveCursor"), f.sink.events)
        }

        @Test
        fun `ESC H sets tab stop through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001BH")

            assertEquals(listOf("setTabStop"), f.sink.events)
        }

        @Test
        fun `ESC c dispatches full terminal reset through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001Bc")

            assertEquals(listOf("resetTerminal"), f.sink.events)
        }

        @Test
        fun `CSI sequence dispatches through command dispatcher across chunks`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[12")
            assertEquals(AnsiState.CSI_PARAM, f.state.fsmState)

            f.acceptAscii(";34H")

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                { assertEquals(listOf("setCursorAbsolute:11:33"), f.sink.events) },
            )
        }

        @Test
        fun `common cursor tab and margin CSI commands dispatch through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[10G")
            f.acceptAscii("\u001B[7d")
            f.acceptAscii("\u001B[2E")
            f.acceptAscii("\u001B[3F")
            f.acceptAscii("\u001B[4I")
            f.acceptAscii("\u001B[5Z")
            f.acceptAscii("\u001B[g")
            f.acceptAscii("\u001B[3g")
            f.acceptAscii("\u001B[5;10r")

            assertEquals(
                listOf(
                    "setCursorColumn:9",
                    "setCursorRow:6",
                    "cursorNextLine:2",
                    "cursorPreviousLine:3",
                    "cursorForwardTabs:4",
                    "cursorBackwardTabs:5",
                    "clearTabStop",
                    "clearAllTabStops",
                    "setScrollRegion:4:9",
                ),
                f.sink.events,
            )
        }

        @Test
        fun `DECSTBM omitted margins leave terminal bottom ownership to the sink`() {
            val full = TerminalParserFixture()
            val topOnly = TerminalParserFixture()
            val bottomOnly = TerminalParserFixture()

            full.acceptAscii("\u001B[r")
            topOnly.acceptAscii("\u001B[5;r")
            bottomOnly.acceptAscii("\u001B[;10r")

            assertAll(
                { assertEquals(listOf("setScrollRegion:0:-1"), full.sink.events) },
                { assertEquals(listOf("setScrollRegion:4:-1"), topOnly.sink.events) },
                { assertEquals(listOf("setScrollRegion:0:9"), bottomOnly.sink.events) },
            )
        }

        @Test
        fun `DECSLRM omitted margins leave terminal right edge ownership to the sink`() {
            val full = TerminalParserFixture()
            val leftOnly = TerminalParserFixture()
            val rightOnly = TerminalParserFixture()

            full.acceptAscii("\u001B[s")
            leftOnly.acceptAscii("\u001B[5;s")
            rightOnly.acceptAscii("\u001B[;10s")

            assertAll(
                { assertEquals(listOf("setLeftRightMargins:0:-1"), full.sink.events) },
                { assertEquals(listOf("setLeftRightMargins:4:-1"), leftOnly.sink.events) },
                { assertEquals(listOf("setLeftRightMargins:0:9"), rightOnly.sink.events) },
            )
        }

        @Test
        fun `DEC selective erase and protection commands dispatch through the full parser`() {
            val eraseDisplay = TerminalParserFixture()
            val eraseLine = TerminalParserFixture()
            val protected = TerminalParserFixture()
            val unprotected = TerminalParserFixture()

            eraseDisplay.acceptAscii("\u001B[?2J")
            eraseLine.acceptAscii("\u001B[?1K")
            protected.acceptAscii("\u001B[1\"q")
            unprotected.acceptAscii("\u001B[2\"q")

            assertAll(
                { assertEquals(listOf("eraseInDisplay:2:true"), eraseDisplay.sink.events) },
                { assertEquals(listOf("eraseInLine:1:true"), eraseLine.sink.events) },
                { assertEquals(listOf("setSelectiveEraseProtection:true"), protected.sink.events) },
                { assertEquals(listOf("setSelectiveEraseProtection:false"), unprotected.sink.events) },
            )
        }

        @Test
        fun `DEC rectangular erase and fill commands preserve one based parameters`() {
            val normalErase = TerminalParserFixture()
            val selectiveErase = TerminalParserFixture()
            val fill = TerminalParserFixture()

            normalErase.acceptAscii("\u001B[2;3;4;5\$z")
            selectiveErase.acceptAscii("\u001B[2;3;4;5\${")
            fill.acceptAscii("\u001B[35;2;3;4;5\$x")

            assertAll(
                { assertEquals(listOf("eraseRectangle:2:3:4:5:false"), normalErase.sink.events) },
                { assertEquals(listOf("eraseRectangle:2:3:4:5:true"), selectiveErase.sink.events) },
                { assertEquals(listOf("fillRectangle:35:2:3:4:5"), fill.sink.events) },
            )
        }

        @Test
        fun `DEC private CSI mode dispatch survives chunk boundaries`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[?25")
            f.acceptAscii("h")

            assertEquals(listOf("setDecMode:25:true"), f.sink.events)
        }

        @Test
        fun `DSR CPR and DA requests dispatch through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[5n")
            f.acceptAscii("\u001B[6n")
            f.acceptAscii("\u001B[?6n")
            f.acceptAscii("\u001B[c")
            f.acceptAscii("\u001B[>c")
            f.acceptAscii("\u001B[=c")

            assertEquals(
                listOf(
                    "requestDeviceStatusReport:5:false",
                    "requestDeviceStatusReport:6:false",
                    "requestDeviceStatusReport:6:true",
                    "requestDeviceAttributes:0:0",
                    "requestDeviceAttributes:1:0",
                    "requestDeviceAttributes:2:0",
                ),
                f.sink.events,
            )
        }

        @Test
        fun `safe xterm window reports and title stack operations dispatch through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[11t")
            f.acceptAscii("\u001B[14t")
            f.acceptAscii("\u001B[18t")
            f.acceptAscii("\u001B[19t")
            f.acceptAscii("\u001B[22t")
            f.acceptAscii("\u001B[22;1t")
            f.acceptAscii("\u001B[23;2t")

            assertEquals(
                listOf(
                    "requestWindowReport:11",
                    "requestWindowReport:14",
                    "requestWindowReport:18",
                    "requestWindowReport:19",
                    "pushTitleStack:0",
                    "pushTitleStack:1",
                    "popTitleStack:2",
                ),
                f.sink.events,
            )
        }

        @Test
        fun `OSC titles dispatch on BEL or ST`() {
            val bel = TerminalParserFixture()
            val st = TerminalParserFixture()
            val window = TerminalParserFixture()

            bel.acceptAscii("\u001B]0;title\u0007")
            st.acceptAscii("\u001B]1;name\u001B\\")
            window.acceptAscii("\u001B]2;window\u001B\\")

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:title"), bel.sink.events) },
                { assertEquals(listOf("setIconTitle:name"), st.sink.events) },
                { assertEquals(listOf("setWindowTitle:window"), window.sink.events) },
            )
        }

        @Test
        fun `OSC malformed unsupported and overflowed payloads are ignored while clipboard requests are surfaced`() {
            val malformed = TerminalParserFixture()
            val unsupported = TerminalParserFixture()
            val overflowed = TerminalParserFixture(state = ParserState(maxPayload = 4))
            val clipboard = TerminalParserFixture()

            malformed.acceptAscii("\u001B]x;title\u0007")
            unsupported.acceptAscii("\u001B]99;title\u001B\\")
            overflowed.acceptAscii("\u001B]0;too-long\u0007")
            clipboard.acceptAscii("\u001B]52;c;SGVsbG8=\u001B\\")

            assertAll(
                { assertTrue(malformed.sink.events.isEmpty()) },
                { assertTrue(unsupported.sink.events.isEmpty()) },
                { assertTrue(overflowed.sink.events.isEmpty()) },
                { assertEquals(listOf("requestClipboard:c:SGVsbG8="), clipboard.sink.events) },
            )
        }

        @Test
        fun `OSC payload ceiling is exact and independent of host chunking`() {
            val atLimit = "\u001B]2;abc\u0007".encodeToByteArray()
            val overLimit = "\u001B]2;abcd\u0007".encodeToByteArray()

            for (chunkSize in 1..atLimit.size) {
                val accepted = TerminalParserFixture(state = ParserState(maxPayload = 5))
                var offset = 0
                while (offset < atLimit.size) {
                    val length = minOf(chunkSize, atLimit.size - offset)
                    accepted.parser.accept(atLimit, offset, length)
                    offset += length
                }

                val rejected = TerminalParserFixture(state = ParserState(maxPayload = 5))
                rejected.parser.accept(overLimit)

                assertAll(
                    { assertEquals(listOf("setWindowTitle:abc"), accepted.sink.events, "chunkSize=$chunkSize") },
                    { assertEquals(AnsiState.GROUND, accepted.state.fsmState, "chunkSize=$chunkSize") },
                    { assertTrue(rejected.sink.events.isEmpty()) },
                    { assertEquals(AnsiState.GROUND, rejected.state.fsmState) },
                )
            }
        }

        @Test
        fun `OSC 8 starts and ends hyperlinks through the full parser`() {
            val withoutId = TerminalParserFixture()
            val withId = TerminalParserFixture()
            val end = TerminalParserFixture()

            withoutId.acceptAscii("\u001B]8;;https://example.com\u001B\\")
            withId.acceptAscii("\u001B]8;id=abc;https://example.com\u001B\\")
            end.acceptAscii("\u001B]8;;\u001B\\")

            assertAll(
                {
                    assertEquals(
                        listOf("startHyperlink:https://example.com:null"),
                        withoutId.sink.events,
                    )
                },
                {
                    assertEquals(
                        listOf("startHyperlink:https://example.com:abc"),
                        withId.sink.events,
                    )
                },
                { assertEquals(listOf("endHyperlink"), end.sink.events) },
            )
        }

        @Test
        fun `OSC 7 current working directory dispatches on BEL and survives chunk boundaries`() {
            val bel = TerminalParserFixture()
            val chunked = TerminalParserFixture()

            bel.acceptAscii("\u001B]7;file:///home/user/My%20Project\u0007")
            for (byte in "\u001B]7;file://host/share/project\u001B\\".encodeToByteArray()) {
                chunked.parser.accept(byteArrayOf(byte))
            }

            assertAll(
                {
                    assertEquals(
                        listOf("setCurrentWorkingDirectoryUri:file:///home/user/My%20Project"),
                        bel.sink.events,
                    )
                },
                { assertEquals(AnsiState.GROUND, chunked.state.fsmState) },
                {
                    assertEquals(
                        listOf("setCurrentWorkingDirectoryUri:file://host/share/project"),
                        chunked.sink.events,
                    )
                },
            )
        }

        @Test
        fun `OSC 9 and OSC 777 desktop notifications through the full parser`() {
            val osc9 = TerminalParserFixture()
            val osc777 = TerminalParserFixture()
            val osc777Warning = TerminalParserFixture()

            osc9.acceptAscii("\u001B]9;Hello from full parser\u0007")
            osc777.acceptAscii("\u001B]777;notify;Full Title;Full Body\u001B\\")
            osc777Warning.acceptAscii("\u001B]777;notify;Attention;Low Disk;warning\u0007")

            assertAll(
                {
                    assertEquals(
                        listOf("showNotification::Hello from full parser:INFO"),
                        osc9.sink.events,
                    )
                },
                {
                    assertEquals(
                        listOf("showNotification:Full Title:Full Body:INFO"),
                        osc777.sink.events,
                    )
                },
                {
                    assertEquals(
                        listOf("showNotification:Attention:Low Disk:WARNING"),
                        osc777Warning.sink.events,
                    )
                },
            )
        }

        @Test
        fun `OSC 133 shell integration markers dispatch on BEL or ST`() {
            val promptStart = TerminalParserFixture()
            val promptEnd = TerminalParserFixture()
            val commandStart = TerminalParserFixture()
            val commandFinished = TerminalParserFixture()

            promptStart.acceptAscii("\u001B]133;A\u0007")
            promptEnd.acceptAscii("\u001B]133;B\u001B\\")
            commandStart.acceptAscii("\u001B]133;C\u0007")
            commandFinished.acceptAscii("\u001B]133;D;127\u001B\\")

            assertAll(
                { assertEquals(listOf("shellIntegrationMarker:PROMPT_START:null"), promptStart.sink.events) },
                { assertEquals(listOf("shellIntegrationMarker:PROMPT_END:null"), promptEnd.sink.events) },
                { assertEquals(listOf("shellIntegrationMarker:COMMAND_START:null"), commandStart.sink.events) },
                {
                    assertEquals(
                        listOf("shellIntegrationMarker:COMMAND_FINISHED:127"),
                        commandFinished.sink.events,
                    )
                },
            )
        }

        @Test
        fun `OSC 133 survives byte chunk boundaries around structural bytes`() {
            val f = TerminalParserFixture()
            val bytes = "\u001B]133;D;42\u001B\\".encodeToByteArray()

            for (byte in bytes) {
                f.parser.accept(byteArrayOf(byte))
            }

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                { assertEquals(listOf("shellIntegrationMarker:COMMAND_FINISHED:42"), f.sink.events) },
            )
        }

        @Test
        fun `DCS ST drops payload without dispatching plain ESC backslash`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001BPabc\u001B\\")

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                { assertTrue(f.sink.events.isEmpty()) },
            )
        }

        @Test
        fun `DCS payload ceiling rejects overflow and recovers at every chunk boundary`() {
            val acceptedBytes = "\u001BP\$qm\u001B\\".encodeToByteArray()
            val overflowedBytes = "\u001BP\$qmx\u001B\\Y".encodeToByteArray()

            for (chunkSize in 1..overflowedBytes.size) {
                val accepted = TerminalParserFixture(state = ParserState(maxPayload = 3))
                accepted.parser.accept(acceptedBytes)

                val overflowed = TerminalParserFixture(state = ParserState(maxPayload = 3))
                var offset = 0
                while (offset < overflowedBytes.size) {
                    val length = minOf(chunkSize, overflowedBytes.size - offset)
                    overflowed.parser.accept(overflowedBytes, offset, length)
                    offset += length
                }
                overflowed.endOfInput()

                assertAll(
                    { assertEquals(listOf("queryStatusString:m"), accepted.sink.events, "chunkSize=$chunkSize") },
                    { assertEquals(AnsiState.GROUND, accepted.state.fsmState, "chunkSize=$chunkSize") },
                    { assertEquals(listOf("writeCodepoint:89"), overflowed.sink.events, "chunkSize=$chunkSize") },
                    { assertEquals(AnsiState.GROUND, overflowed.state.fsmState, "chunkSize=$chunkSize") },
                )
            }
        }

        @Test
        fun `DECSCUSR cursor style sequence parses successfully through the full parser`() {
            val f = TerminalParserFixture()

            // CSI 2 SP q (Underline)
            f.acceptAscii("\u001B[2 q")

            // CSI SP q (Default/Omitted)
            f.acceptAscii("\u001B[ q")

            assertEquals(
                listOf(
                    "setCursorStyle:2",
                    "setCursorStyle:0",
                ),
                f.sink.events,
            )
        }
    }

    // ----- SGR --------------------------------------------------------------

    @Nested
    @DisplayName("SGR")
    inner class Sgr {
        @Test
        fun `CSI m and CSI 0 m reset attributes through the full parser`() {
            val empty = TerminalParserFixture()
            val zero = TerminalParserFixture()

            empty.acceptAscii("\u001B[m")
            zero.acceptAscii("\u001B[0m")

            assertAll(
                { assertEquals(listOf("resetAttributes"), empty.sink.events) },
                { assertEquals(listOf("resetAttributes"), zero.sink.events) },
            )
        }

        @Test
        fun `CSI 1 31 m applies bold and indexed foreground through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[1;31m")

            assertEquals(listOf("setBold:true", "setForegroundIndexed:1"), f.sink.events)
        }

        @Test
        fun `CSI omitted and trailing omitted SGR params reset in sequence through the full parser`() {
            val omittedFirst = TerminalParserFixture()
            val omittedLast = TerminalParserFixture()

            omittedFirst.acceptAscii("\u001B[;31m")
            omittedLast.acceptAscii("\u001B[31;m")

            assertAll(
                {
                    assertEquals(
                        listOf("resetAttributes", "setForegroundIndexed:1"),
                        omittedFirst.sink.events,
                    )
                },
                {
                    assertEquals(
                        listOf("setForegroundIndexed:1", "resetAttributes"),
                        omittedLast.sink.events,
                    )
                },
            )
        }

        @Test
        fun `CSI 38 5 indexed foreground applies through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[38;5;196m")

            assertEquals(listOf("setForegroundIndexed:196"), f.sink.events)
        }

        @Test
        fun `CSI 48 2 RGB background applies through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[48;2;10;20;30m")

            assertEquals(listOf("setBackgroundRgb:10:20:30"), f.sink.events)
        }

        @Test
        fun `CSI colon RGB foreground with omitted color-space id applies through the full parser`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B[38:2::10:20:30m")

            assertEquals(listOf("setForegroundRgb:10:20:30"), f.sink.events)
        }

        @Test
        fun `CSI colon color and underline SGR groups apply through the full parser`() {
            val indexedForeground = TerminalParserFixture()
            val indexedBackground = TerminalParserFixture()
            val rgbForeground = TerminalParserFixture()
            val underline = TerminalParserFixture()
            val underlineColor = TerminalParserFixture()

            indexedForeground.acceptAscii("\u001B[38:5:196m")
            indexedBackground.acceptAscii("\u001B[48:5:17m")
            rgbForeground.acceptAscii("\u001B[38:2:10:20:30m")
            underline.acceptAscii("\u001B[4:5m")
            underlineColor.acceptAscii("\u001B[58:2:10:20:30m")

            assertAll(
                { assertEquals(listOf("setForegroundIndexed:196"), indexedForeground.sink.events) },
                { assertEquals(listOf("setBackgroundIndexed:17"), indexedBackground.sink.events) },
                { assertEquals(listOf("setForegroundRgb:10:20:30"), rgbForeground.sink.events) },
                { assertEquals(listOf("setUnderlineStyle:5"), underline.sink.events) },
                { assertEquals(listOf("setUnderlineColorRgb:10:20:30"), underlineColor.sink.events) },
            )
        }

        @Test
        fun `malformed SGR extended colors are ignored while later normal SGR survives through the full parser`() {
            val malformedIndexed = TerminalParserFixture()
            val invalidIndexed = TerminalParserFixture()
            val incompleteRgb = TerminalParserFixture()
            val rgbThenBold = TerminalParserFixture()

            malformedIndexed.acceptAscii("\u001B[38;5m")
            invalidIndexed.acceptAscii("\u001B[38;5;300m")
            incompleteRgb.acceptAscii("\u001B[38;2;10;20m")
            rgbThenBold.acceptAscii("\u001B[38;2;10;20;30;1m")

            assertAll(
                { assertTrue(malformedIndexed.sink.events.isEmpty()) },
                { assertTrue(invalidIndexed.sink.events.isEmpty()) },
                { assertTrue(incompleteRgb.sink.events.isEmpty()) },
                {
                    assertEquals(
                        listOf("setForegroundRgb:10:20:30", "setBold:true"),
                        rgbThenBold.sink.events,
                    )
                },
            )
        }
    }

    // ----- Charset and shifts ---------------------------------------------

    @Nested
    @DisplayName("charset and shifts")
    inner class CharsetAndShifts {
        @Test
        fun `ESC charset designation maps active DEC Special Graphics through printable path`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B(0q")
            f.endOfInput()

            assertEquals(listOf(writeCodepoint(0x2500)), f.sink.events)
        }

        @Test
        fun `inactive G1 DEC Special does not map until SO selects G1 and SI restores G0`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B)0q")
            f.acceptByte(0x0E)
            f.acceptAscii("q")
            f.acceptByte(0x0F)
            f.acceptAscii("q")
            f.endOfInput()

            assertEquals(
                listOf(
                    writeCodepoint('q'.code),
                    writeCodepoint(0x2500),
                    writeCodepoint('q'.code),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `ESC N and ESC O single shift G2 and G3 for one printable character each`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B*0\u001B+0\u001BNqq\u001BOxx")
            f.endOfInput()

            assertEquals(
                listOf(
                    writeCodepoint(0x2500),
                    writeCodepoint('q'.code),
                    writeCodepoint(0x2502),
                    writeCodepoint('x'.code),
                ),
                f.sink.events,
            )
        }

        @Test
        fun `unsupported charset designation shape is swallowed without plain ESC dispatch`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B(D\u001B#D")

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                { assertTrue(f.sink.events.isEmpty()) },
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), f.state.charsets) },
            )
        }

        @Test
        fun `ESC 7 and ESC 8 save and restore designated charsets and shift state`() {
            val f = TerminalParserFixture()

            // Designate G1 as DEC Special Graphics and shift out to select G1
            f.acceptAscii("\u001B)0")
            f.acceptByte(0x0E) // SO (shift out to G1)

            // Verify characters are mapped to DEC special graphics
            f.acceptAscii("q")
            assertEquals(listOf(writeCodepoint(0x2500)), f.sink.events)
            f.sink.events.clear()

            // Save cursor (which should save active charsets and glSlot)
            f.acceptAscii("\u001B7")

            // Shift in (SI) to restore G0 (which is ASCII)
            f.acceptByte(0x0F)
            f.acceptAscii("q")
            assertEquals(listOf("saveCursor", writeCodepoint('q'.code)), f.sink.events)
            f.sink.events.clear()

            // Restore cursor (which should restore G1 as the active GL slot)
            f.acceptAscii("\u001B8")
            f.acceptAscii("q")
            assertEquals(listOf("restoreCursor", writeCodepoint(0x2500)), f.sink.events)
        }
    }

    // ----- Reset ------------------------------------------------------------

    @Nested
    @DisplayName("reset")
    inner class Reset {
        @Test
        fun `reset drops pending printable cluster CSI state and charset shifts`() {
            val f = TerminalParserFixture()

            f.acceptAscii("\u001B(0")
            f.acceptByte(0x0E)
            f.acceptAscii("\u001B[12")

            f.reset()
            f.acceptAscii("q")
            f.endOfInput()

            assertAll(
                { assertEquals(AnsiState.GROUND, f.state.fsmState) },
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), f.state.charsets) },
                { assertEquals(0, f.state.glSlot) },
                { assertEquals(-1, f.state.singleShiftSlot) },
                { assertEquals(listOf(writeCodepoint('q'.code)), f.sink.events) },
            )
        }

        @Test
        fun `reset does not emit replacement for pending UTF-8 or duplicate published printable cluster`() {
            val f = TerminalParserFixture()

            f.acceptAscii("A")
            f.acceptByte(0xC3)
            f.reset()

            assertEquals(listOf(writeCodepoint('A'.code)), f.sink.events)
        }
    }
}
