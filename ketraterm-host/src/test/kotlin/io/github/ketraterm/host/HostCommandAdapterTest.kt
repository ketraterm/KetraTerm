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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.*
import io.github.ketraterm.protocol.keyboard.FormatOtherKeysMode
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.protocol.keyboard.ModifyOtherKeysMode
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("HostCommandAdapter")
class HostCommandAdapterTest {
    private data class Fixture(
        val terminal: TerminalBuffer = TerminalBuffers.create(width = 10, height = 5),
        val hostPolicy: HostPolicy = HostPolicy(),
        val kittyKeyboardSupportedFlags: Int = KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK,
        val events: RecordingHostEventSink = RecordingHostEventSink(),
        val sink: HostCommandAdapter =
            HostCommandAdapter(
                terminal = terminal,
                hostEvents = events,
                hostPolicy = hostPolicy,
                kittyKeyboardSupportedFlags = kittyKeyboardSupportedFlags,
            ),
        val parser: TerminalOutputParser = TerminalParsers.create(sink),
    ) {
        fun acceptAscii(text: String) {
            parser.accept(text.encodeToByteArray())
        }

        fun end() {
            parser.endOfInput()
        }

        fun drainResponses(): String {
            val destination = ByteArray(128)
            val count = terminal.readResponseBytes(destination)
            return destination.decodeToString(0, count)
        }
    }

    @Nested
    @DisplayName("printable and cursor pipeline")
    inner class PrintableAndCursorPipeline {
        @Test
        fun `plain text parsed through adapter writes the core grid`() {
            val f = Fixture()

            f.acceptAscii("abc")
            f.end()

            assertEquals("abc", f.terminal.getLineAsString(0))
        }

        @Test
        fun `plain prompt chunk publishes trailing printable before stream close`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 40, height = 5))

            f.acceptAscii("PS C:\\Users\\gagik> ")

            assertAll(
                { assertEquals("PS C:\\Users\\gagik> ", f.terminal.getLineAsString(0)) },
                { assertEquals(19, f.terminal.cursorCol) },
            )
        }

        @Test
        fun `Codex inline history sequence retains rows scrolled from a top anchored region`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 3, height = 4, maxHistory = 8))
            f.acceptAscii("\u001B[1;1HAAA\u001B[2;1HBBB\u001B[3;1HCCC\u001B[4;1HDDD")

            f.acceptAscii("\u001B[1;3r\u001B[3;1H\r\nNEW\u001B[r")

            assertAll(
                { assertEquals(1, f.terminal.historySize) },
                { assertEquals("AAA\nBBB\nCCC\nNEW\nDDD", f.terminal.getAllAsString()) },
                { assertEquals("BBB", f.terminal.getLineAsString(0)) },
                { assertEquals("CCC", f.terminal.getLineAsString(1)) },
                { assertEquals("NEW", f.terminal.getLineAsString(2)) },
                { assertEquals("DDD", f.terminal.getLineAsString(3)) },
            )
        }

        @Test
        fun `top anchored structural scroll commands and line edits preserve lower rows without history`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 3, height = 4, maxHistory = 8))

            fun seed() {
                f.acceptAscii("\u001B[1;1HAAA\u001B[2;1HBBB\u001B[3;1HCCC\u001B[4;1HDDD\u001B[1;3r")
            }

            seed()
            f.acceptAscii("\u001B[1;1H\u001B[1T")
            assertAll(
                { assertEquals(0, f.terminal.historySize) },
                { assertEquals("", f.terminal.getLineAsString(0)) },
                { assertEquals("AAA", f.terminal.getLineAsString(1)) },
                { assertEquals("BBB", f.terminal.getLineAsString(2)) },
                { assertEquals("DDD", f.terminal.getLineAsString(3)) },
            )

            seed()
            f.acceptAscii("\u001B[1;1H\u001BM")
            assertAll(
                { assertEquals(0, f.terminal.historySize) },
                { assertEquals("", f.terminal.getLineAsString(0)) },
                { assertEquals("AAA", f.terminal.getLineAsString(1)) },
                { assertEquals("BBB", f.terminal.getLineAsString(2)) },
                { assertEquals("DDD", f.terminal.getLineAsString(3)) },
            )

            seed()
            f.acceptAscii("\u001B[2;1H\u001B[1L\u001B[1M")
            assertAll(
                { assertEquals(0, f.terminal.historySize) },
                { assertEquals("AAA", f.terminal.getLineAsString(0)) },
                { assertEquals("BBB", f.terminal.getLineAsString(1)) },
                { assertEquals("", f.terminal.getLineAsString(2)) },
                { assertEquals("DDD", f.terminal.getLineAsString(3)) },
            )
        }

        @Test
        fun `ED 3 clears retained history while preserving the visible viewport`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 3, height = 2, maxHistory = 8))
            f.acceptAscii("\u001B[1;1HAAA\u001B[2;1HBBB\u001B[2;1H\r\n")

            assertEquals(1, f.terminal.historySize)

            f.acceptAscii("\u001B[3J")

            assertAll(
                { assertEquals(0, f.terminal.historySize) },
                { assertEquals("BBB", f.terminal.getLineAsString(0)) },
            )
        }

        @Test
        fun `combining mark in later host chunk extends previous core cell`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("e")
            assertAll(
                { assertEquals("e", f.terminal.getLineAsString(0)) },
                { assertEquals(1, f.terminal.cursorCol) },
            )

            f.parser.accept("\u0301".encodeToByteArray())

            val line = f.terminal.getLine(0)
            val cluster = IntArray(4)
            val length = line.readCluster(0, cluster)
            assertAll(
                { assertTrue(line.isCluster(0)) },
                { assertEquals(2, length) },
                { assertEquals('e'.code, cluster[0]) },
                { assertEquals(0x0301, cluster[1]) },
                { assertEquals(1, f.terminal.cursorCol) },
            )
        }

        @Test
        fun `symbol heavy paste split byte by byte leaves cursor after visual cells`() {
            val text = "∀∂∈ℝ∧∪≡∞ ↑↗↨↻⇣ ┐┼╔╘░►☺♀ ﬁ⑀₂ἠḂӥẄɐː⍎אԱა"
            val bytes = text.encodeToByteArray()
            val expectedCodepoints = text.codePointCount(0, text.length)
            val f = Fixture(terminal = TerminalBuffers.create(width = 120, height = 3))

            for (byte in bytes) {
                f.parser.accept(byteArrayOf(byte))
            }

            assertAll(
                { assertEquals(expectedCodepoints, f.terminal.cursorCol) },
                { assertEquals(0, f.terminal.cursorRow) },
                { assertEquals(text, f.terminal.getLineAsString(0)) },
            )

            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertEquals(expectedCodepoints, frame.cursor.column) },
                    { assertEquals(0, frame.cursor.row) },
                )
            }
        }

        @Test
        fun `CSI absolute cursor position writes at core coordinates`() {
            val f = Fixture()

            f.acceptAscii("\u001B[2;3HX")
            f.end()

            assertAll(
                { assertEquals('X'.code, f.terminal.getCodepointAt(2, 1)) },
                { assertEquals(3, f.terminal.cursorCol) },
                { assertEquals(1, f.terminal.cursorRow) },
            )
        }

        @Test
        fun `origin mode makes CUP relative to the scroll region`() {
            val f = Fixture()

            f.acceptAscii("\u001B[2;4r")
            f.acceptAscii("\u001B[?6h")
            f.acceptAscii("\u001B[1;1HX")
            f.end()

            assertAll(
                { assertEquals('X'.code, f.terminal.getCodepointAt(0, 1)) },
                { assertEquals(1, f.terminal.cursorCol) },
                { assertEquals(1, f.terminal.cursorRow) },
            )
        }

        @Test
        fun `insert mode shifts existing cells through real core mutation`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("ABCD")
            f.acceptAscii("\u001B[1;2H")
            f.acceptAscii("\u001B[4h")
            f.acceptAscii("X")
            f.end()

            assertEquals("AXBCD", f.terminal.getLineAsString(0))
        }

        @Test
        fun `new line mode makes LF also return carriage through adapter policy`() {
            val f = Fixture()

            f.acceptAscii("A")
            f.acceptAscii("\u001B[20h")
            f.acceptAscii("\nB")
            f.end()

            assertAll(
                { assertEquals("A", f.terminal.getLineAsString(0)) },
                { assertEquals("B", f.terminal.getLineAsString(1)) },
                { assertEquals(1, f.terminal.cursorCol) },
                { assertEquals(1, f.terminal.cursorRow) },
            )
        }

        @Test
        fun `auto wrap reset keeps printable output on the right edge`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 3, height = 2))

            f.acceptAscii("\u001B[?7l")
            f.acceptAscii("ABCD")
            f.end()

            assertAll(
                { assertEquals("ABD", f.terminal.getLineAsString(0)) },
                { assertEquals("", f.terminal.getLineAsString(1)) },
                { assertEquals(2, f.terminal.cursorCol) },
            )
        }

        @Test
        fun `RIS hard reset parsed from bytes resets core state`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("ABC")
            f.acceptAscii("\u001B[1;38;5;196m")
            f.acceptAscii("\u001B[?7l")
            f.acceptAscii("\u001B[2;3H")
            f.acceptAscii("\u001Bc")
            f.acceptAscii("X")
            f.end()

            val attr = f.terminal.getAttrAt(0, 0)

            assertAll(
                { assertEquals("X", f.terminal.getLineAsString(0)) },
                { assertEquals("", f.terminal.getLineAsString(1)) },
                { assertEquals(1, f.terminal.cursorCol) },
                { assertEquals(0, f.terminal.cursorRow) },
                { assertTrue(f.terminal.getModeSnapshot().isAutoWrap) },
                { assertEquals(CellColor.DEFAULT, attr?.foreground) },
                { assertEquals(false, attr?.bold) },
            )
        }

        @Test
        fun `DECSTR soft reset parsed from bytes preserves content and resets soft state`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 8, height = 3))

            f.acceptAscii("\u001B[?1;6;7;12;25;66;1004;1006;2004h")
            f.acceptAscii("\u001B[4h")
            f.acceptAscii("AB")
            f.acceptAscii("\u001B[1;38;5;196m")
            f.acceptAscii("\u001B[?7l")
            f.acceptAscii("\u001B[1\"q")
            f.acceptAscii("\u001B[!p")
            f.acceptAscii("C")
            f.end()

            val snapshot = f.terminal.getModeSnapshot()
            val attr = f.terminal.getAttrAt(2, 0)

            assertAll(
                { assertEquals("ABC", f.terminal.getLineAsString(0)) },
                { assertFalse(snapshot.isInsertMode) },
                { assertFalse(snapshot.isApplicationCursorKeys) },
                { assertFalse(snapshot.isApplicationKeypad) },
                { assertFalse(snapshot.isOriginMode) },
                { assertTrue(snapshot.isAutoWrap) },
                { assertTrue(snapshot.isCursorVisible) },
                { assertTrue(snapshot.isCursorBlinking) },
                { assertFalse(snapshot.isFocusReportingEnabled) },
                { assertFalse(snapshot.isBracketedPasteEnabled) },
                { assertEquals(MouseEncodingMode.SGR, snapshot.mouseEncodingMode) },
                { assertEquals(CellColor.DEFAULT, attr?.foreground) },
                { assertFalse(attr?.bold == true) },
                { assertFalse(attr?.selectiveEraseProtected == true) },
            )
        }

        @Test
        fun `CR CR LF only advances cursor by exactly one row`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 80, height = 10))

            // Move to row 5, col 10
            f.acceptAscii("\u001B[6;11H")
            assertEquals(5, f.terminal.cursorRow)
            assertEquals(10, f.terminal.cursorCol)

            // Feed CR CR LF
            f.acceptAscii("\r\r\n")
            assertEquals(6, f.terminal.cursorRow)
            assertEquals(0, f.terminal.cursorCol)
        }

        @Test
        fun `CR CR LF at bottom row scrolls by exactly one line`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 80, height = 10))

            // Move cursor to bottom row 9, col 10
            f.acceptAscii("\u001B[10;11H")
            assertEquals(9, f.terminal.cursorRow)
            assertEquals(10, f.terminal.cursorCol)

            // Feed CR CR LF
            f.acceptAscii("\r\r\n")
            // Since we were at the bottom row, LF scrolls up.
            // The cursor row remains at 9, column becomes 0.
            assertEquals(9, f.terminal.cursorRow)
            assertEquals(0, f.terminal.cursorCol)
        }

        @Test
        fun `redraw cursor positioning sequence is correct`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 100, height = 30))

            // simulate end of first draw
            f.acceptAscii("\r\n\r\n\r\n\r\n") // 4 CR LF pairs
            val rowAfterDraw = f.terminal.cursorRow
            f.acceptAscii("\u001B[99C") // right 99 — should stay on same row
            assertEquals(rowAfterDraw, f.terminal.cursorRow)
            assertEquals(99, f.terminal.cursorCol)

            // simulate redraw cursor positioning
            f.acceptAscii("\r") // CR
            f.acceptAscii("\u001B[4A") // up 4
            assertEquals(rowAfterDraw - 4, f.terminal.cursorRow)
            assertEquals(0, f.terminal.cursorCol)
        }
    }

    @Nested
    @DisplayName("mode policy")
    inner class ModePolicy {
        @Test
        fun `ANSI and DEC modes parsed from bytes update core mode snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[4;20h")
            f.acceptAscii("\u001B[?1;5;6;7;12;25;66;69;1004;2004h")

            val snapshot = f.terminal.getModeSnapshot()

            assertAll(
                { assertTrue(snapshot.isInsertMode) },
                { assertTrue(snapshot.isNewLineMode) },
                { assertTrue(snapshot.isApplicationCursorKeys) },
                { assertTrue(snapshot.isReverseVideo) },
                { assertTrue(snapshot.isOriginMode) },
                { assertTrue(snapshot.isAutoWrap) },
                { assertTrue(snapshot.isCursorBlinking) },
                { assertTrue(snapshot.isCursorVisible) },
                { assertTrue(snapshot.isApplicationKeypad) },
                { assertTrue(snapshot.isLeftRightMarginMode) },
                { assertTrue(snapshot.isFocusReportingEnabled) },
                { assertTrue(snapshot.isBracketedPasteEnabled) },
            )
        }

        @Test
        fun `DEC mode reset parsed from bytes updates core mode snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[4;20h\u001B[?1;5;6;7;12;25;66;69;1004;2004h")
            f.acceptAscii("\u001B[4;20l\u001B[?1;5;6;7;12;25;66;69;1004;2004l")

            val snapshot = f.terminal.getModeSnapshot()

            assertAll(
                { assertFalse(snapshot.isInsertMode) },
                { assertFalse(snapshot.isNewLineMode) },
                { assertFalse(snapshot.isApplicationCursorKeys) },
                { assertFalse(snapshot.isReverseVideo) },
                { assertFalse(snapshot.isOriginMode) },
                { assertFalse(snapshot.isAutoWrap) },
                { assertFalse(snapshot.isCursorBlinking) },
                { assertFalse(snapshot.isCursorVisible) },
                { assertFalse(snapshot.isApplicationKeypad) },
                { assertFalse(snapshot.isLeftRightMarginMode) },
                { assertFalse(snapshot.isFocusReportingEnabled) },
                { assertFalse(snapshot.isBracketedPasteEnabled) },
            )
        }

        @Test
        fun `mouse tracking and SGR mouse encoding modes update core snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[?1002;1006h")

            var snapshot = f.terminal.getModeSnapshot()
            assertAll(
                { assertEquals(MouseTrackingMode.BUTTON_EVENT, snapshot.mouseTrackingMode) },
                { assertEquals(MouseEncodingMode.SGR, snapshot.mouseEncodingMode) },
            )

            f.acceptAscii("\u001B[?1002;1006l")

            snapshot = f.terminal.getModeSnapshot()
            assertAll(
                { assertEquals(MouseTrackingMode.OFF, snapshot.mouseTrackingMode) },
                { assertEquals(MouseEncodingMode.DEFAULT, snapshot.mouseEncodingMode) },
            )
        }

        @Test
        fun `UTF8 and URXVT mouse encoding modes update core snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[?1005h")
            assertEquals(MouseEncodingMode.UTF8, f.terminal.getModeSnapshot().mouseEncodingMode)

            f.acceptAscii("\u001B[?1015h")
            assertEquals(MouseEncodingMode.URXVT, f.terminal.getModeSnapshot().mouseEncodingMode)

            f.acceptAscii("\u001B[?1015l")
            assertEquals(MouseEncodingMode.DEFAULT, f.terminal.getModeSnapshot().mouseEncodingMode)
        }

        @Test
        fun `SGR-Pixels mouse encoding mode updates core snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[?1016h")
            assertEquals(MouseEncodingMode.SGR_PIXELS, f.terminal.getModeSnapshot().mouseEncodingMode)

            f.acceptAscii("\u001B[?1016l")
            assertEquals(MouseEncodingMode.DEFAULT, f.terminal.getModeSnapshot().mouseEncodingMode)
        }

        @Test
        fun `synchronized output mode is parsed and updates mode snapshot`() {
            val f = Fixture()

            assertFalse(f.terminal.getModeSnapshot().isSynchronizedOutput)

            f.acceptAscii("\u001B[?2026h")
            assertTrue(f.terminal.getModeSnapshot().isSynchronizedOutput)

            f.acceptAscii("\u001B[?2026l")
            assertFalse(f.terminal.getModeSnapshot().isSynchronizedOutput)
        }

        @Test
        fun `bell private modes 1042 and 1043 are parsed and update mode snapshot`() {
            val f = Fixture()

            val initialSnapshot = f.terminal.getModeSnapshot()
            assertFalse(initialSnapshot.isBellIsUrgent)
            assertFalse(initialSnapshot.isPopOnBell)

            f.acceptAscii("\u001B[?1042h")
            assertTrue(f.terminal.getModeSnapshot().isBellIsUrgent)

            f.acceptAscii("\u001B[?1042l")
            assertFalse(f.terminal.getModeSnapshot().isBellIsUrgent)

            f.acceptAscii("\u001B[?1043h")
            assertTrue(f.terminal.getModeSnapshot().isPopOnBell)

            f.acceptAscii("\u001B[?1043l")
            assertFalse(f.terminal.getModeSnapshot().isPopOnBell)
        }

        @Test
        fun `xterm key option controls update input-facing core mode snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[>4;3m")
            f.acceptAscii("\u001B[>4;1f")

            var snapshot = f.terminal.getModeSnapshot()
            assertAll(
                { assertEquals(ModifyOtherKeysMode.MODE_3, snapshot.modifyOtherKeysMode) },
                { assertEquals(FormatOtherKeysMode.CSI_U, snapshot.formatOtherKeysMode) },
            )

            f.acceptAscii("\u001B[>4m")
            f.acceptAscii("\u001B[>4f")

            snapshot = f.terminal.getModeSnapshot()
            assertAll(
                { assertEquals(ModifyOtherKeysMode.DISABLED, snapshot.modifyOtherKeysMode) },
                { assertEquals(FormatOtherKeysMode.DEFAULT, snapshot.formatOtherKeysMode) },
            )
        }

        @Test
        fun `xterm key modifier query and disable preserve the distinct disabled state`() {
            val f = Fixture()

            f.acceptAscii("\u001B[>4;2m\u001B[?4m")
            assertEquals("\u001B[>4;2m", f.drainResponses())

            f.acceptAscii("\u001B[>4n\u001B[?4m")
            assertAll(
                { assertEquals(-1, f.terminal.getModeSnapshot().modifyOtherKeysMode) },
                { assertEquals("\u001B[>4;-1m", f.drainResponses()) },
            )
        }

        @Test
        fun `terminal response policy suppresses xterm key modifier query responses`() {
            val f = Fixture(hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))

            f.acceptAscii("\u001B[?4m")

            assertEquals("", f.drainResponses())
        }

        @Test
        fun `unsupported xterm key option controls leave core mode snapshot unchanged`() {
            val f = Fixture()

            val before = f.terminal.getModeSnapshot()
            f.acceptAscii("\u001B[>1;2m")
            f.acceptAscii("\u001B[>4;9m")
            f.acceptAscii("\u001B[>1;1f")
            f.acceptAscii("\u001B[>4;2f")

            assertEquals(before, f.terminal.getModeSnapshot())
        }

        @Test
        fun `Kitty keyboard flag controls update input-facing core mode snapshot`() {
            val f = Fixture()

            f.acceptAscii("\u001B[=9u")
            assertEquals(
                KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
                    KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
                f.terminal.getModeSnapshot().kittyKeyboardFlags,
            )

            f.acceptAscii("\u001B[=2;2u")
            assertEquals(
                KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
                    KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
                f.terminal.getModeSnapshot().kittyKeyboardFlags,
            )

            f.acceptAscii("\u001B[=1;3u")
            assertEquals(
                KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
                f.terminal.getModeSnapshot().kittyKeyboardFlags,
            )
        }

        @Test
        fun `Kitty keyboard controls mask unsupported flags through core storage`() {
            val f = Fixture()

            f.acceptAscii("\u001B[=1023u")

            assertEquals(KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK, f.terminal.getModeSnapshot().kittyKeyboardFlags)
        }

        @Test
        fun `rich host capability enables every encoder-supported Kitty flag through parser host core pipeline`() {
            val f = Fixture(kittyKeyboardSupportedFlags = KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK)

            f.acceptAscii("\u001B[=31u\u001B[?u")

            assertEquals(KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK, f.terminal.getModeSnapshot().kittyKeyboardFlags)
            assertEquals("\u001B[?31u", f.drainResponses())
        }

        @Test
        fun `host capability mask constrains Kitty push state as well as replace set and clear`() {
            val f = Fixture()

            f.acceptAscii("\u001B[=9u\u001B[>31u")

            assertEquals(KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK, f.terminal.getModeSnapshot().kittyKeyboardFlags)
        }

        @Test
        fun `Kitty keyboard query reports active flags through the parser host core pipeline`() {
            val f = Fixture()

            f.acceptAscii("\u001B[=9u\u001B[?u")

            assertEquals("\u001B[?9u", f.drainResponses())
        }

        @Test
        fun `terminal response policy suppresses Kitty keyboard query responses`() {
            val f = Fixture(hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))

            f.acceptAscii("\u001B[?u")

            assertEquals("", f.drainResponses())
        }

        @Test
        fun `unsupported Kitty keyboard controls leave core mode snapshot unchanged`() {
            val f = Fixture()

            f.acceptAscii("\u001B[=3u")
            val before = f.terminal.getModeSnapshot()

            f.acceptAscii("\u001B[=1;9u")
            f.acceptAscii("\u001B[>5;2u")
            f.acceptAscii("\u001B[<2;3u")

            assertEquals(before, f.terminal.getModeSnapshot())
        }

        @Test
        fun `Kitty keyboard push and pop sequences update core mode snapshot`() {
            val f = Fixture()

            // 1. Set initial flags to 9: CSI = 9 u
            f.acceptAscii("\u001B[=9u")
            assertEquals(9, f.terminal.getModeSnapshot().kittyKeyboardFlags)

            // 2. Push flags 8: CSI > 8 u
            f.acceptAscii("\u001B[>8u")
            assertEquals(8, f.terminal.getModeSnapshot().kittyKeyboardFlags)

            // 3. Pop 1 count: CSI < 1 u
            f.acceptAscii("\u001B[<1u")
            assertEquals(9, f.terminal.getModeSnapshot().kittyKeyboardFlags)

            // 4. Push 8, then pop using default count (omitted parameter) which defaults to 1: CSI < u
            f.acceptAscii("\u001B[>8u")
            assertEquals(8, f.terminal.getModeSnapshot().kittyKeyboardFlags)
            f.acceptAscii("\u001B[<u")
            assertEquals(9, f.terminal.getModeSnapshot().kittyKeyboardFlags)
        }

        @Test
        fun `DECCOLM set and reset resize the core width`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 80, height = 3))

            f.acceptAscii("\u001B[?3h")
            assertEquals(132, f.terminal.width)

            f.acceptAscii("\u001B[?3l")
            assertEquals(80, f.terminal.width)
        }

        @Test
        fun `DSR CPR and DA parsed from bytes queue terminal-to-host responses`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 10, height = 5))

            f.acceptAscii("\u001B[5n")
            f.acceptAscii("\u001B[2;3H")
            f.acceptAscii("\u001B[6n")
            f.acceptAscii("\u001B[?6n")
            f.acceptAscii("\u001B[c")
            f.acceptAscii("\u001B[>c")
            f.acceptAscii("\u001B[=c")
            f.end()

            assertEquals(
                "\u001B[0n\u001B[2;3R\u001B[?2;3R\u001B[?1;2c\u001B[>0;0;0c",
                f.drainResponses(),
            )
        }

        @Test
        fun `terminal response policy can deny DSR CPR and DA responses`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 10, height = 5),
                    hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY),
                )

            f.acceptAscii("\u001B[5n")
            f.acceptAscii("\u001B[6n")
            f.acceptAscii("\u001B[c")
            f.acceptAscii("\u001B[>c")
            f.end()

            assertEquals("", f.drainResponses())
        }

        @Test
        fun `safe xterm window reports parsed from bytes queue terminal-to-host responses`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 120, height = 40))

            f.terminal.setWindowSizePixels(width = 800, height = 400)
            f.acceptAscii("\u001B[14t")
            f.acceptAscii("\u001B[18t")
            f.acceptAscii("\u001B[8;10;20t")
            f.end()

            assertEquals("\u001B[4;400;800t\u001B[8;40;120t", f.drainResponses())
        }

        @Test
        fun `terminal response policy can deny safe xterm window reports`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 120, height = 40),
                    hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY),
                )

            f.terminal.setWindowSizePixels(width = 800, height = 400)
            f.acceptAscii("\u001B[14t")
            f.acceptAscii("\u001B[18t")
            f.end()

            assertEquals("", f.drainResponses())
        }

        @Test
        fun `palette policy can deny palette query responses`() {
            val f = Fixture(hostPolicy = HostPolicy(palettePolicy = HostControlPolicy.DENY))

            f.sink.queryPaletteColor(1)
            f.sink.queryDynamicColor(10)

            assertEquals("", f.drainResponses())
        }

        @Test
        fun `DECSLRM parsed from bytes updates core left right margins when mode is enabled`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 8, height = 3))

            f.acceptAscii("\u001B[?69h")
            f.acceptAscii("\u001B[3;6s")
            f.end()

            assertAll(
                { assertEquals(2, f.terminal.cursorCol) },
                { assertEquals(0, f.terminal.cursorRow) },
            )
        }

        @Test
        fun `alternate screen mode 47 switches buffers without clearing alt content`() {
            val f = Fixture()

            f.acceptAscii("P\u001B[?47hA\u001B[?47lQ\u001B[?47h")
            assertEquals("A", f.terminal.getLineAsString(0))

            f.acceptAscii("B")
            f.end()
            assertEquals("AB", f.terminal.getLineAsString(0))
        }

        @Test
        fun `alternate screen mode 1047 switches buffers and clears alt content on entry`() {
            val f = Fixture()

            f.acceptAscii("\u001B[?47hA")
            f.acceptAscii("\u001B[?47l")
            f.acceptAscii("\u001B[?1047h")
            assertEquals("", f.terminal.getLineAsString(0))

            f.acceptAscii("B")
            f.acceptAscii("\u001B[?1047l")
            f.acceptAscii("\u001B[?47h")
            assertEquals("B", f.terminal.getLineAsString(0))
        }

        @Test
        fun `alternate screen mode 1048 saves and restores cursor without switching buffers`() {
            val f = Fixture()

            f.acceptAscii("AB")
            f.acceptAscii("\u001B[?1048h")
            f.acceptAscii("\u001B[2;4H")
            f.acceptAscii("\u001B[?1048l")
            f.acceptAscii("C")
            f.end()

            assertEquals("ABC", f.terminal.getLineAsString(0))
        }

        @Test
        fun `alternate screen mode 1049 switches buffers and restores the primary cursor`() {
            val f = Fixture()

            f.acceptAscii("P\u001B[?1049hA\u001B[?1049lQ\u001B[?47h")
            assertEquals("A", f.terminal.getLineAsString(0))

            f.acceptAscii("\u001B[?47l")
            assertEquals("PQ", f.terminal.getLineAsString(0))
        }

        @Test
        fun `alternate modes preserve primary protected wide content across resize and reset lifecycle`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 4))

            f.acceptAscii("\u001B[?69h\u001B[3;6s\u001B[2;3r\u001B[1\"q\u001B[2;3H🙂")
            f.acceptAscii("\u001B[?1048h\u001B[1;1H\u001B[?1048lR")

            assertAll(
                { assertEquals(0x1F642, f.terminal.getCodepointAt(2, 1)) },
                { assertTrue(f.terminal.getAttrAt(2, 1)?.selectiveEraseProtected == true) },
                { assertEquals('R'.code, f.terminal.getCodepointAt(4, 1)) },
            )

            f.acceptAscii("\u001B[?47hALT")
            f.terminal.resize(newWidth = 6, newHeight = 3)
            f.acceptAscii("\u001B[?47l")

            assertAll(
                { assertEquals(0x1F642, f.terminal.getCodepointAt(2, 1)) },
                { assertTrue(f.terminal.getAttrAt(2, 1)?.selectiveEraseProtected == true) },
                { assertEquals('R'.code, f.terminal.getCodepointAt(4, 1)) },
            )

            f.acceptAscii("\u001B[?47hS\u001B[?47l\u001B[?1047h")
            assertEquals("", f.terminal.getLineAsString(0))
            f.acceptAscii("\u001B[?1047l\u001B[?1049hZ")

            f.terminal.reset()

            assertAll(
                { assertEquals(0, f.terminal.historySize) },
                { assertEquals(6, f.terminal.width) },
                { assertEquals(3, f.terminal.height) },
                { assertEquals("", f.terminal.getLineAsString(0)) },
                { assertFalse(f.terminal.getModeSnapshot().isLeftRightMarginMode) },
            )
        }

        @Test
        fun `horizontal margins and alternate screen are invariant under byte chunking`() {
            val stream = "\u001B[?69h\u001B[3;5sAB\u001B[?1049hZ\u001B[?1049lC".encodeToByteArray()

            for (chunkSize in 1..stream.size) {
                val f = Fixture(terminal = TerminalBuffers.create(width = 8, height = 3))
                var offset = 0
                while (offset < stream.size) {
                    val length = minOf(chunkSize, stream.size - offset)
                    f.parser.accept(stream, offset, length)
                    offset += length
                }
                f.end()

                assertAll(
                    { assertEquals("  ABC", f.terminal.getLineAsString(0), "chunkSize=$chunkSize") },
                    { assertEquals(4, f.terminal.cursorCol, "chunkSize=$chunkSize") },
                    { assertEquals(0, f.terminal.cursorRow, "chunkSize=$chunkSize") },
                )
            }
        }

        @Test
        fun `origin mode addresses the intersection of vertical and horizontal margins under every chunking`() {
            val stream = "\u001B[?69h\u001B[3;6s\u001B[2;4r\u001B[?6h\u001B[1;1HX\u001B[?6l\u001B[1;1HY".encodeToByteArray()

            for (chunkSize in 1..stream.size) {
                val f = Fixture(terminal = TerminalBuffers.create(width = 8, height = 5))
                var offset = 0
                while (offset < stream.size) {
                    val length = minOf(chunkSize, stream.size - offset)
                    f.parser.accept(stream, offset, length)
                    offset += length
                }
                f.end()

                assertAll(
                    { assertEquals('X'.code, f.terminal.getCodepointAt(2, 1), "chunkSize=$chunkSize") },
                    { assertEquals('Y'.code, f.terminal.getCodepointAt(2, 0), "chunkSize=$chunkSize") },
                    { assertEquals(3, f.terminal.cursorCol, "chunkSize=$chunkSize") },
                    { assertEquals(0, f.terminal.cursorRow, "chunkSize=$chunkSize") },
                )
            }
        }

        @Test
        fun `DECSCUSR cursor style parsed from bytes updates core cursor shape and blinking`() {
            val f = Fixture()

            // 0 -> Blinking Block (default/omitted is 0)
            f.acceptAscii("\u001B[ q")
            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertTrue(frame.cursor.blinking) },
                    { assertEquals(TerminalRenderCursorShape.BLOCK, frame.cursor.shape) },
                )
            }

            // 2 -> Steady Block
            f.acceptAscii("\u001B[2 q")
            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertFalse(frame.cursor.blinking) },
                    { assertEquals(TerminalRenderCursorShape.BLOCK, frame.cursor.shape) },
                )
            }

            // 3 -> Blinking Underline
            f.acceptAscii("\u001B[3 q")
            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertTrue(frame.cursor.blinking) },
                    { assertEquals(TerminalRenderCursorShape.UNDERLINE, frame.cursor.shape) },
                )
            }

            // 4 -> Steady Underline
            f.acceptAscii("\u001B[4 q")
            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertFalse(frame.cursor.blinking) },
                    { assertEquals(TerminalRenderCursorShape.UNDERLINE, frame.cursor.shape) },
                )
            }

            // 5 -> Blinking Bar
            f.acceptAscii("\u001B[5 q")
            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertTrue(frame.cursor.blinking) },
                    { assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape) },
                )
            }

            // 6 -> Steady Bar
            f.acceptAscii("\u001B[6 q")
            (f.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                assertAll(
                    { assertFalse(frame.cursor.blinking) },
                    { assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape) },
                )
            }
        }
    }

    @Nested
    @DisplayName("SGR and OSC policy")
    inner class SgrAndOscPolicy {
        @Test
        fun `SGR indexed color and styles update core pen attributes`() {
            val f = Fixture()

            f.acceptAscii("\u001B[1;2;3;4:3;5;7;8;9;53;38;5;196;48;5;17mX")
            f.end()

            val attr = f.terminal.getAttrAt(0, 0)

            assertAll(
                { assertEquals(CellColor.indexed(196), attr?.foreground) },
                { assertEquals(CellColor.indexed(17), attr?.background) },
                { assertEquals(true, attr?.bold) },
                { assertEquals(true, attr?.faint) },
                { assertEquals(true, attr?.italic) },
                { assertEquals(UnderlineStyle.CURLY, attr?.underlineStyle) },
                { assertEquals(true, attr?.blink) },
                { assertEquals(true, attr?.inverse) },
                { assertEquals(true, attr?.conceal) },
                { assertEquals(true, attr?.strikethrough) },
                { assertEquals(true, attr?.overline) },
            )
        }

        @Test
        fun `SGR underline color updates core extended pen attributes`() {
            val f = Fixture()

            f.acceptAscii("\u001B[58;2;1;2;3;4:5mX")
            f.acceptAscii("\u001B[59;24mY")
            f.end()

            val first = f.terminal.getAttrAt(0, 0)
            val second = f.terminal.getAttrAt(1, 0)

            assertAll(
                { assertEquals(CellColor.rgb(1, 2, 3), first?.underlineColor) },
                { assertEquals(UnderlineStyle.DASHED, first?.underlineStyle) },
                { assertEquals(CellColor.DEFAULT, second?.underlineColor) },
                { assertEquals(UnderlineStyle.NONE, second?.underlineStyle) },
            )
        }

        @Test
        fun `SGR reset restores default core pen attributes`() {
            val f = Fixture()

            f.acceptAscii("\u001B[1;31mX")
            f.acceptAscii("\u001B[0mY")
            f.end()

            val first = f.terminal.getAttrAt(0, 0)
            val second = f.terminal.getAttrAt(1, 0)

            assertAll(
                { assertEquals(true, first?.bold) },
                { assertEquals(CellColor.indexed(1), first?.foreground) },
                { assertEquals(false, second?.bold) },
                { assertEquals(CellColor.DEFAULT, second?.foreground) },
                { assertEquals(CellColor.DEFAULT, second?.background) },
            )
        }

        @Test
        fun `RGB SGR updates core pen colors`() {
            val f = Fixture()

            f.acceptAscii("\u001B[38;2;10;20;30;48;2;40;50;60mX")
            f.end()

            val attr = f.terminal.getAttrAt(0, 0)

            assertAll(
                { assertEquals(CellColor.rgb(10, 20, 30), attr?.foreground) },
                { assertEquals(CellColor.rgb(40, 50, 60), attr?.background) },
            )
        }

        @Test
        fun `SGR default colors and inverse reset preserve other pen attributes`() {
            val f = Fixture()

            f.acceptAscii("\u001B[1;7;38;5;196;48;2;40;50;60mX")
            f.acceptAscii("\u001B[27;39;49mY")
            f.end()

            val first = f.terminal.getAttrAt(0, 0)
            val second = f.terminal.getAttrAt(1, 0)

            assertAll(
                { assertEquals(CellColor.indexed(196), first?.foreground) },
                { assertEquals(CellColor.rgb(40, 50, 60), first?.background) },
                { assertEquals(true, first?.bold) },
                { assertEquals(true, first?.inverse) },
                { assertEquals(CellColor.DEFAULT, second?.foreground) },
                { assertEquals(CellColor.DEFAULT, second?.background) },
                { assertEquals(true, second?.bold) },
                { assertEquals(false, second?.inverse) },
            )
        }

        @Test
        fun `DECSCA and DECSEL preserve protected cells through parser and adapter`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("\u001B[1\"qA")
            f.acceptAscii("\u001B[2\"qB")
            f.acceptAscii("\u001B[?2K")
            f.end()

            assertAll(
                { assertEquals("A", f.terminal.getLineAsString(0)) },
                { assertTrue(f.terminal.getAttrAt(0, 0)?.selectiveEraseProtected == true) },
            )
        }

        @Test
        fun `DECSCA and DECSED preserve protected cells through parser and adapter`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("\u001B[1\"qA")
            f.acceptAscii("\u001B[2\"qB")
            f.acceptAscii("\u001B[2;1HC")
            f.acceptAscii("\u001B[?2J")
            f.end()

            assertAll(
                { assertEquals("A", f.terminal.getLineAsString(0)) },
                { assertEquals("", f.terminal.getLineAsString(1)) },
                { assertTrue(f.terminal.getAttrAt(0, 0)?.selectiveEraseProtected == true) },
            )
        }

        @Test
        fun `DECSEL preserves protected wide cluster and clears adjacent unprotected cell`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("\u001B[1\"q\uD83D\uDE00")
            f.acceptAscii("\u001B[2\"qB")
            f.acceptAscii("\u001B[1;1H\u001B[?2K")
            f.end()

            assertAll(
                { assertEquals(0x1F600, f.terminal.getCodepointAt(0, 0)) },
                { assertEquals(-1, f.terminal.getCodepointAt(1, 0)) },
                { assertEquals(0, f.terminal.getCodepointAt(2, 0)) },
                { assertTrue(f.terminal.getAttrAt(0, 0)?.selectiveEraseProtected == true) },
            )
        }

        @Test
        fun `DEC rectangular fill erase and selective erase flow from bytes into core`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 3))

            f.acceptAscii("\u001B[35;3;2;3;3\$x")
            f.acceptAscii("\u001B[1\"q\u001B[1;2H🙂\u001B[0\"qB")
            f.acceptAscii("\u001B[1;3;1;4\${")
            f.acceptAscii("\u001B[2;2;2;3\$z")
            f.end()

            assertAll(
                { assertEquals('#'.code, f.terminal.getCodepointAt(1, 2)) },
                { assertEquals(0x1F642, f.terminal.getCodepointAt(1, 0)) },
                { assertEquals(-1, f.terminal.getCodepointAt(2, 0)) },
                { assertTrue(f.terminal.getAttrAt(1, 0)?.selectiveEraseProtected == true) },
                { assertEquals(0, f.terminal.getCodepointAt(3, 1)) },
            )
        }

        @Test
        fun `DECCRA byte stream performs overlap safe active page copy`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 2))

            f.acceptAscii("ABCDEF")
            f.acceptAscii("\u001B[1;1;1;4;1;1;2;1\$v")
            f.end()

            assertEquals("AABCDF", f.terminal.getLineAsString(0))
        }

        @Test
        fun `DECSACE DECCARA and DECRARA update attributes through byte stream`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 4, height = 2))

            f.acceptAscii("A")
            f.acceptAscii("\u001B[2*x\u001B[1;1;1;2;1;4\$r")
            f.acceptAscii("\u001B[1;1;1;2;1;4\$t")
            f.end()

            assertAll(
                { assertEquals('A'.code, f.terminal.getCodepointAt(0, 0)) },
                { assertEquals(' '.code, f.terminal.getCodepointAt(1, 0)) },
                { assertEquals(false, f.terminal.getAttrAt(0, 0)?.bold) },
                {
                    assertEquals(
                        0,
                        f.terminal
                            .getAttrAt(0, 0)
                            ?.underlineStyle
                            ?.sgrCode,
                    )
                },
                { assertEquals(false, f.terminal.getAttrAt(1, 0)?.bold) },
                {
                    assertEquals(
                        0,
                        f.terminal
                            .getAttrAt(1, 0)
                            ?.underlineStyle
                            ?.sgrCode,
                    )
                },
            )
        }

        @Test
        fun `DECIC and DECDC bytes shift every row in the active scroll region`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 6, height = 3))

            f.acceptAscii("\u001B[1;1HABCDEF\u001B[2;1HABCDEF\u001B[3;1HABCDEF")
            f.acceptAscii("\u001B[2;3r\u001B[2;2H\u001B[2'}")
            f.acceptAscii("\u001B[1'~")
            f.end()

            assertAll(
                { assertEquals("ABCDEF", f.terminal.getLineAsString(0)) },
                { assertEquals("A BCD", f.terminal.getLineAsString(1)) },
                { assertEquals("A BCD", f.terminal.getLineAsString(2)) },
                { assertEquals(1, f.terminal.cursorCol) },
                { assertEquals(1, f.terminal.cursorRow) },
            )
        }

        @Test
        fun `OSC titles and hyperlinks are retained as adapter metadata`() {
            val f = Fixture()

            f.acceptAscii("\u001B]0;both\u0007")
            f.acceptAscii("\u001B]8;id=abc;https://example.com\u001B\\")

            assertAll(
                { assertEquals("both", f.sink.iconTitle) },
                { assertEquals("both", f.sink.windowTitle) },
                { assertEquals("https://example.com", f.sink.activeHyperlinkUri) },
                { assertEquals("abc", f.sink.activeHyperlinkId) },
            )

            f.acceptAscii("L")
            f.acceptAscii("\u001B]8;;\u001B\\")
            f.acceptAscii("N")
            f.end()

            assertAll(
                { assertNull(f.sink.activeHyperlinkUri) },
                { assertNull(f.sink.activeHyperlinkId) },
                { assertEquals(1, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals("https://example.com", f.sink.hyperlinkUri(1)) },
                { assertEquals(0, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
                { assertNull(f.sink.hyperlinkUri(0)) },
            )
        }

        @Test
        fun `title policy can deny OSC title updates and title stack changes`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            titlePolicy = TerminalTitlePolicy(localPermission = TerminalTitlePermission.DENY),
                        ),
                )

            f.acceptAscii("\u001B]0;denied\u0007")
            f.acceptAscii("\u001B[22t")
            f.acceptAscii("\u001B]2;also-denied\u0007")
            f.acceptAscii("\u001B[23t")

            assertAll(
                { assertEquals("", f.sink.iconTitle) },
                { assertEquals("", f.sink.windowTitle) },
                { assertTrue(f.events.iconTitles.isEmpty()) },
                { assertTrue(f.events.windowTitles.isEmpty()) },
            )
        }

        @Test
        fun `overlong OSC titles are clamped by default host policy`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            titlePolicy = TerminalTitlePolicy(maxLength = 5),
                        ),
                )

            f.acceptAscii("\u001B]2;short\u0007")
            f.acceptAscii("\u001B]2;too-long\u0007")

            assertAll(
                { assertEquals("too-l", f.sink.windowTitle) },
                { assertEquals(listOf("short", "too-l"), f.events.windowTitles) },
            )
        }

        @Test
        fun `overlong OSC titles can be rejected by profile policy`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            titlePolicy =
                                TerminalTitlePolicy(
                                    maxLength = 5,
                                    overflowPolicy = TerminalTitleOverflowPolicy.REJECT,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]2;short\u0007")
            f.acceptAscii("\u001B]2;too-long\u0007")

            assertAll(
                { assertEquals("short", f.sink.windowTitle) },
                { assertEquals(listOf("short"), f.events.windowTitles) },
            )
        }

        @Test
        fun `remote OSC titles can be denied while local titles remain allowed by profile policy`() {
            val remote =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            titlePolicy =
                                TerminalTitlePolicy(
                                    origin = TerminalTitleOrigin.REMOTE,
                                    localPermission = TerminalTitlePermission.ALLOW,
                                    remotePermission = TerminalTitlePermission.DENY,
                                ),
                        ),
                )
            val local =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            titlePolicy =
                                TerminalTitlePolicy(
                                    origin = TerminalTitleOrigin.LOCAL,
                                    localPermission = TerminalTitlePermission.ALLOW,
                                    remotePermission = TerminalTitlePermission.DENY,
                                ),
                        ),
                )

            remote.acceptAscii("\u001B]0;ssh-title\u0007")
            local.acceptAscii("\u001B]0;local-title\u0007")

            assertAll(
                { assertEquals("", remote.sink.windowTitle) },
                { assertEquals("", remote.sink.iconTitle) },
                { assertTrue(remote.events.windowTitles.isEmpty()) },
                { assertTrue(remote.events.iconTitles.isEmpty()) },
                { assertEquals("local-title", local.sink.windowTitle) },
                { assertEquals("local-title", local.sink.iconTitle) },
                { assertEquals(listOf("local-title"), local.events.windowTitles) },
                { assertEquals(listOf("local-title"), local.events.iconTitles) },
            )
        }

        @Test
        fun `hyperlink policy can deny OSC hyperlink metadata`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 1, height = 1),
                    hostPolicy = HostPolicy(hyperlinkPolicy = HostControlPolicy.DENY),
                )

            f.sink.startHyperlink(uri = "https://example.com", id = "denied")
            f.sink.writeCodepoint('A'.code)

            assertAll(
                { assertNull(f.sink.activeHyperlinkUri) },
                { assertNull(f.sink.activeHyperlinkId) },
                { assertEquals(0, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertNull(f.sink.hyperlinkUri(1)) },
            )
        }

        @Test
        fun `OSC hyperlink without explicit id receives a fresh numeric id for each open`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 2, height = 1))

            f.sink.startHyperlink(uri = "https://example.com/a", id = null)
            f.sink.writeCodepoint('A'.code)
            f.sink.startHyperlink(uri = "https://example.com/a", id = null)
            f.sink.writeCodepoint('B'.code)

            assertAll(
                { assertEquals(1, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals(2, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
                { assertEquals("https://example.com/a", f.sink.hyperlinkUri(1)) },
                { assertEquals("https://example.com/a", f.sink.hyperlinkUri(2)) },
            )
        }

        @Test
        fun `OSC hyperlink reuses numeric id for same uri and id`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 2, height = 1))

            f.sink.startHyperlink(uri = "https://example.com/a", id = "same")
            f.sink.writeCodepoint('A'.code)
            f.sink.startHyperlink(uri = "https://example.com/a", id = "same")
            f.sink.writeCodepoint('B'.code)

            assertAll(
                { assertEquals(1, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals(1, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
            )
        }

        @Test
        fun `OSC hyperlink id participates in numeric id key`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 2, height = 1))

            f.sink.startHyperlink(uri = "https://example.com/a", id = "first")
            f.sink.writeCodepoint('A'.code)
            f.sink.startHyperlink(uri = "https://example.com/a", id = "second")
            f.sink.writeCodepoint('B'.code)

            assertAll(
                { assertEquals(1, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals(2, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
            )
        }

        @Test
        fun `OSC hyperlink uri participates in numeric id key`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 2, height = 1))

            f.sink.startHyperlink(uri = "https://example.com/a", id = "same")
            f.sink.writeCodepoint('A'.code)
            f.sink.startHyperlink(uri = "https://example.com/b", id = "same")
            f.sink.writeCodepoint('B'.code)

            assertAll(
                { assertEquals(1, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals(2, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
            )
        }

        @Test
        fun `overlong OSC hyperlink uri is ignored`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 2, height = 1),
                    hostPolicy = HostPolicy(maxHyperlinkUriLength = 8),
                )

            f.sink.startHyperlink(uri = "https://too-long.example", id = null)
            f.sink.writeCodepoint('A'.code)

            assertAll(
                { assertNull(f.sink.activeHyperlinkUri) },
                { assertNull(f.sink.activeHyperlinkId) },
                { assertEquals(0, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
            )

            f.sink.startHyperlink(uri = "short", id = null)
            f.sink.writeCodepoint('B'.code)

            assertAll(
                { assertEquals("short", f.sink.activeHyperlinkUri) },
                { assertNull(f.sink.activeHyperlinkId) },
                { assertEquals(1, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
            )
        }

        @Test
        fun `overlong OSC hyperlink id is ignored`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 2, height = 1),
                    hostPolicy = HostPolicy(maxHyperlinkIdLength = 3),
                )

            f.sink.startHyperlink(uri = "https://example.com", id = "toolong")
            f.sink.writeCodepoint('A'.code)

            assertAll(
                { assertNull(f.sink.activeHyperlinkUri) },
                { assertNull(f.sink.activeHyperlinkId) },
                { assertEquals(0, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
            )

            f.sink.startHyperlink(uri = "https://example.com", id = "ok")
            f.sink.writeCodepoint('B'.code)

            assertAll(
                { assertEquals("https://example.com", f.sink.activeHyperlinkUri) },
                { assertEquals("ok", f.sink.activeHyperlinkId) },
                { assertEquals(1, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
            )
        }

        @Test
        fun `OSC hyperlink registry evicts least recently used entry when bounded`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 5, height = 1),
                    hostPolicy = HostPolicy(maxHyperlinkEntries = 2),
                )

            f.sink.startHyperlink(uri = "https://example.com/a", id = null)
            f.sink.writeCodepoint('A'.code)
            f.sink.startHyperlink(uri = "https://example.com/b", id = null)
            f.sink.writeCodepoint('B'.code)
            f.sink.startHyperlink(uri = "https://example.com/a", id = null)
            f.sink.writeCodepoint('C'.code)
            f.sink.startHyperlink(uri = "https://example.com/c", id = null)
            f.sink.writeCodepoint('D'.code)
            f.sink.startHyperlink(uri = "https://example.com/b", id = null)
            f.sink.writeCodepoint('E'.code)

            assertAll(
                { assertEquals(1, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals(2, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
                { assertEquals(3, f.terminal.getAttrAt(2, 0)?.hyperlinkId) },
                { assertEquals(4, f.terminal.getAttrAt(3, 0)?.hyperlinkId) },
                { assertEquals(5, f.terminal.getAttrAt(4, 0)?.hyperlinkId) },
                { assertNull(f.sink.hyperlinkUri(1)) },
                { assertNull(f.sink.hyperlinkUri(2)) },
                { assertNull(f.sink.hyperlinkUri(3)) },
                { assertEquals("https://example.com/c", f.sink.hyperlinkUri(4)) },
                { assertEquals("https://example.com/b", f.sink.hyperlinkUri(5)) },
            )
        }

        @Test
        fun `OSC hyperlink spam does not disable new links after registry reaches max`() {
            val f =
                Fixture(
                    terminal = TerminalBuffers.create(width = 4, height = 1),
                    hostPolicy = HostPolicy(maxHyperlinkEntries = 2),
                )

            repeat(16) { index ->
                f.sink.startHyperlink(uri = "https://example.com/$index", id = null)
            }

            f.sink.writeCodepoint('A'.code)
            f.sink.startHyperlink(uri = "https://example.com/16", id = null)
            f.sink.writeCodepoint('B'.code)
            f.sink.startHyperlink(uri = "https://example.com/15", id = null)
            f.sink.writeCodepoint('C'.code)
            f.sink.endHyperlink()
            f.sink.writeCodepoint('D'.code)

            assertAll(
                { assertEquals(16, f.terminal.getAttrAt(0, 0)?.hyperlinkId) },
                { assertEquals(17, f.terminal.getAttrAt(1, 0)?.hyperlinkId) },
                { assertEquals(18, f.terminal.getAttrAt(2, 0)?.hyperlinkId) },
                { assertEquals(0, f.terminal.getAttrAt(3, 0)?.hyperlinkId) },
            )
        }

        @Test
        fun `xterm title stack push and pop restores icon and window titles`() {
            val f = Fixture()

            f.acceptAscii("\u001B]0;base\u0007")
            f.acceptAscii("\u001B[22t")
            f.acceptAscii("\u001B]1;icon-temp\u0007")
            f.acceptAscii("\u001B]2;window-temp\u0007")
            f.acceptAscii("\u001B[23t")

            assertAll(
                { assertEquals("base", f.sink.iconTitle) },
                { assertEquals("base", f.sink.windowTitle) },
            )

            f.acceptAscii("\u001B]1;icon-only-base\u0007")
            f.acceptAscii("\u001B]2;window-stays\u0007")
            f.acceptAscii("\u001B[22;1t")
            f.acceptAscii("\u001B]1;icon-only-temp\u0007")
            f.acceptAscii("\u001B[23;1t")

            assertAll(
                { assertEquals("icon-only-base", f.sink.iconTitle) },
                { assertEquals("window-stays", f.sink.windowTitle) },
            )
        }

        @Test
        fun `host event sink receives bell and title metadata changes`() {
            val events = RecordingHostEventSink()
            val terminal = TerminalBuffers.create(width = 10, height = 5)
            val sink = HostCommandAdapter(terminal, events)
            val parser = TerminalParsers.create(sink)

            parser.accept("\u0007\u001B]0;both\u001B\\".encodeToByteArray())
            parser.endOfInput()

            assertAll(
                { assertEquals(1, events.bells) },
                { assertEquals(listOf("both"), events.iconTitles) },
                { assertEquals(listOf("both"), events.windowTitles) },
            )
        }

        @Test
        fun `DECALN parsed from bytes fills the visible grid with E and resets margins and cursor`() {
            val f = Fixture(terminal = TerminalBuffers.create(width = 8, height = 4))

            // Write some garbage and offset cursor
            f.acceptAscii("ABCDEF\n")
            f.acceptAscii("GHIJK\n")

            // Set some non-default margins
            f.acceptAscii("\u001B[2;3r") // scroll region: lines 2 to 3 (indices 1 to 2)
            f.acceptAscii("\u001B[?69h") // enable left-right margins
            f.acceptAscii("\u001B[2;6s") // left-right margins: cols 2 to 6 (indices 1 to 5)

            // Offset cursor
            f.acceptAscii("\u001B[2;3H")

            // Send DECALN: ESC # 8
            f.acceptAscii("\u001B#8")
            f.end()

            // Verify screen contents are entirely filled with 'E'
            for (row in 0 until 4) {
                assertEquals("EEEEEEEE", f.terminal.getLineAsString(row))
            }

            // Verify cursor is homed
            assertEquals(0, f.terminal.cursorRow)
            assertEquals(0, f.terminal.cursorCol)
        }
    }

    @Nested
    @DisplayName("DCS query pipeline")
    inner class DcsQueryPipeline {
        @Test
        fun `DECRQSS SGR query returns default pen`() {
            val f = Fixture()
            f.acceptAscii("\u001BP\$qm\u001B\\")
            f.end()
            assertEquals("\u001BP1\$r0m\u001B\\", f.drainResponses())
        }

        @Test
        fun `DECRQSS SGR query returns active attributes`() {
            val f = Fixture()
            // Set bold + red foreground
            f.acceptAscii("\u001B[1;31m")
            f.acceptAscii("\u001BP\$qm\u001B\\")
            f.end()
            assertEquals("\u001BP1\$r1;31m\u001B\\", f.drainResponses())
        }

        @Test
        fun `DECRQSS scroll margins query returns current margins`() {
            val f = Fixture()
            f.acceptAscii("\u001BP\$qr\u001B\\")
            f.end()
            // Default: top=1, bottom=5 on a 10x5 terminal
            assertEquals("\u001BP1\$r1;5r\u001B\\", f.drainResponses())
        }

        @Test
        fun `DECRQSS horizontal margins query returns current margins`() {
            val f = Fixture()
            f.acceptAscii("\u001BP\$qs\u001B\\")
            f.end()
            // Default: left=1, right=10 on a 10x5 terminal
            assertEquals("\u001BP1\$r1;10s\u001B\\", f.drainResponses())
        }

        @Test
        fun `DECRQSS cursor style query returns current shape`() {
            val f = Fixture()
            f.acceptAscii("\u001BP\$qq\u001B\\")
            f.end()
            // Default: blinking block = 1
            assertEquals("\u001BP1\$r1 q\u001B\\", f.drainResponses())
        }

        @Test
        fun `DECRQSS unsupported query returns failure response`() {
            val f = Fixture()
            f.acceptAscii("\u001BP\$qx\u001B\\")
            f.end()
            assertEquals("\u001BP0\$rx\u001B\\", f.drainResponses())
        }

        @Test
        fun `XTGETTCAP colors query returns 256`() {
            val f = Fixture()
            // Query colors (Co -> hex 436f)
            f.acceptAscii("\u001BP+q436f\u001B\\")
            f.end()
            assertEquals("\u001BP1+r436f=323536\u001B\\", f.drainResponses())
        }

        @Test
        fun `XTGETTCAP boolean capability RGB returns present without value`() {
            val f = Fixture()
            // Query RGB (hex 524742)
            f.acceptAscii("\u001BP+q524742\u001B\\")
            f.end()
            assertEquals("\u001BP1+r524742\u001B\\", f.drainResponses())
        }

        @Test
        fun `XTGETTCAP multi-cap query returns all matching`() {
            val f = Fixture()
            // Query Co (436f) and TN (544e)
            f.acceptAscii("\u001BP+q436f;544e\u001B\\")
            f.end()
            assertEquals(
                "\u001BP1+r436f=323536;544e=787465726d2d323536636f6c6f72\u001B\\",
                f.drainResponses(),
            )
        }

        @Test
        fun `XTGETTCAP unsupported capability returns failure response`() {
            val f = Fixture()
            // Query "xx" (hex 7878) — not in the allowlist
            f.acceptAscii("\u001BP+q7878\u001B\\")
            f.end()
            assertEquals("\u001BP0+r\u001B\\", f.drainResponses())
        }

        @Test
        fun `terminal response policy can deny DECRQSS and XTGETTCAP responses`() {
            val f = Fixture(hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))

            f.acceptAscii("\u001BP\$qm\u001B\\")
            f.acceptAscii("\u001BP+q436f\u001B\\")
            f.end()

            assertEquals("", f.drainResponses())
        }
    }

    @Nested
    @DisplayName("shell integration")
    inner class ShellIntegration {
        @Test
        fun `OSC 7 retains and forwards valid file URIs`() {
            val f = Fixture()

            f.acceptAscii("\u001B]7;file:///home/user/My%20Project\u001B\\")
            f.acceptAscii("\u001B]7;file://build-host/workspace\u0007")

            assertAll(
                {
                    assertEquals(
                        listOf("file:///home/user/My%20Project", "file://build-host/workspace"),
                        f.events.currentWorkingDirectories,
                    )
                },
                { assertEquals("file://build-host/workspace", f.sink.currentWorkingDirectoryUri()) },
                { assertEquals("", f.terminal.getLineAsString(0)) },
            )
        }

        @Test
        fun `OSC 7 rejects malformed unsafe and oversized URIs without replacing metadata`() {
            val f = Fixture(hostPolicy = HostPolicy(maxCurrentWorkingDirectoryUriLength = 32))
            f.acceptAscii("\u001B]7;file:///safe\u001B\\")

            listOf(
                "",
                "/not-a-uri",
                "https://example.com/path",
                "file:/missing-authority-form",
                "file://user@host/path",
                "file:///path?query",
                "file:///path#fragment",
                "file:///bad%escape",
                "file:///this/path/is/too/long/for/policy",
            ).forEach { uri -> f.acceptAscii("\u001B]7;$uri\u001B\\") }

            assertAll(
                { assertEquals(listOf("file:///safe"), f.events.currentWorkingDirectories) },
                { assertEquals("file:///safe", f.sink.currentWorkingDirectoryUri()) },
            )
        }

        @Test
        fun `OSC 7 current working directory policy can deny metadata updates`() {
            val f = Fixture(hostPolicy = HostPolicy(currentWorkingDirectoryPolicy = HostControlPolicy.DENY))

            f.acceptAscii("\u001B]7;file:///safe\u001B\\")

            assertAll(
                { assertTrue(f.events.currentWorkingDirectories.isEmpty()) },
                { assertNull(f.sink.currentWorkingDirectoryUri()) },
            )
        }

        @Test
        fun `OSC 133 shell integration markers are forwarded without grid mutation`() {
            val f = Fixture()

            f.acceptAscii("\u001B]133;A\u0007")
            f.acceptAscii("\u001B]133;D;7\u001B\\")

            assertAll(
                {
                    assertEquals(
                        listOf(
                            ShellIntegrationEvent(ShellIntegrationMarker.PROMPT_START),
                            ShellIntegrationEvent(ShellIntegrationMarker.COMMAND_FINISHED, exitCode = 7),
                        ),
                        f.events.shellIntegrationEvents,
                    )
                },
                { assertEquals("", f.terminal.getLineAsString(0)) },
                { assertEquals(0, f.terminal.cursorCol) },
                { assertEquals(0, f.terminal.cursorRow) },
            )
        }
    }

    @Nested
    @DisplayName("desktop notifications")
    inner class DesktopNotifications {
        @Test
        fun `notifications are forwarded to the event sink`() {
            val f = Fixture()
            f.acceptAscii("\u001B]9;Hello from Host\u0007")
            f.acceptAscii("\u001B]777;notify;Warning!;Disk space low;warning\u0007")
            assertEquals(
                listOf(
                    Triple("", "Hello from Host", NotificationLevel.INFO),
                    Triple("Warning!", "Disk space low", NotificationLevel.WARNING),
                ),
                f.events.notifications,
            )
        }

        @Test
        fun `notifications are clamped by host policy`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            maxNotificationTitleLength = 5,
                            maxNotificationBodyLength = 10,
                        ),
                )
            f.acceptAscii("\u001B]777;notify;1234567;1234567890123;error\u0007")
            assertEquals(listOf(Triple("12345", "1234567890", NotificationLevel.ERROR)), f.events.notifications)
        }

        @Test
        fun `notification policy can deny desktop notification requests`() {
            val f = Fixture(hostPolicy = HostPolicy(notificationPolicy = HostControlPolicy.DENY))

            f.acceptAscii("\u001B]9;denied\u0007")
            f.acceptAscii("\u001B]777;notify;Denied;Body;warning\u0007")

            assertTrue(f.events.notifications.isEmpty())
        }
    }

    @Nested
    @DisplayName("clipboard policy")
    inner class ClipboardPolicy {
        @Test
        fun `OSC 52 clipboard writes are denied by default and audited without payload content`() {
            val f = Fixture()

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                listOf(
                    TerminalClipboardAuditEvent(
                        operation = TerminalClipboardOperation.WRITE,
                        selection = "c",
                        origin = TerminalClipboardOrigin.REMOTE,
                        encodedLength = 8,
                        decodedBytes = 5,
                        maxDecodedBytes = TerminalClipboardPolicy.DEFAULT_MAX_DECODED_BYTES,
                        decision = TerminalClipboardDecision.DENIED_BY_POLICY,
                    ),
                ),
                f.events.clipboardAudits,
            )
        }

        @Test
        fun `OSC 52 clipboard read queries are denied by default`() {
            val f = Fixture()

            f.acceptAscii("\u001B]52;c;?\u001B\\")

            assertEquals(
                TerminalClipboardDecision.DENIED_READ_DISABLED,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
            assertEquals(
                TerminalClipboardOperation.READ_QUERY,
                f.events.clipboardAudits
                    .single()
                    .operation,
            )
        }

        @Test
        fun `OSC 52 local prompt policy requests host prompt without writing clipboard`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.PROMPT,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                TerminalClipboardDecision.PROMPT_REQUIRED,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
            assertEquals(
                listOf(
                    TerminalClipboardPromptEvent(
                        selection = "c",
                        text = "Hello",
                        audit = f.events.clipboardAudits.single(),
                    ),
                ),
                f.events.clipboardPrompts,
            )
            assertTrue(f.events.clipboardWrites.isEmpty())
        }

        @Test
        fun `OSC 52 remote writes remain denied when only local writes are promptable`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.REMOTE,
                                    localWritePermission = TerminalClipboardPermission.PROMPT,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                TerminalClipboardDecision.DENIED_BY_POLICY,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
        }

        @Test
        fun `OSC 52 allowed policy audits allowance and emits decoded clipboard write`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.ALLOW,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                TerminalClipboardDecision.ALLOWED_BY_POLICY,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
            assertEquals(
                listOf(
                    TerminalClipboardWriteEvent(
                        selection = "c",
                        text = "Hello",
                        audit = f.events.clipboardAudits.single(),
                    ),
                ),
                f.events.clipboardWrites,
            )
        }

        @Test
        fun `OSC 52 allowlist policy denies requests when session is not allowlisted`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.ALLOWLIST,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                TerminalClipboardDecision.DENIED_NOT_ALLOWLISTED,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
        }

        @Test
        fun `OSC 52 allowlist policy allows requests when session is allowlisted`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.ALLOWLIST,
                                    allowlisted = true,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                TerminalClipboardDecision.ALLOWED_BY_POLICY,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
            assertEquals(
                listOf(
                    TerminalClipboardWriteEvent(
                        selection = "c",
                        text = "Hello",
                        audit = f.events.clipboardAudits.single(),
                    ),
                ),
                f.events.clipboardWrites,
            )
        }

        @Test
        fun `OSC 52 malformed base64 is denied before permission evaluation`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.ALLOW,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;not base64\u0007")

            assertEquals(
                TerminalClipboardDecision.DENIED_MALFORMED_PAYLOAD,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
            assertEquals(
                0,
                f.events.clipboardAudits
                    .single()
                    .decodedBytes,
            )
            assertTrue(f.events.clipboardWrites.isEmpty())
        }

        @Test
        fun `OSC 52 payload exceeding decoded size limit is denied`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.ALLOW,
                                    maxDecodedBytes = 4,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")

            assertEquals(
                TerminalClipboardDecision.DENIED_PAYLOAD_TOO_LARGE,
                f.events.clipboardAudits
                    .single()
                    .decision,
            )
            assertEquals(
                5,
                f.events.clipboardAudits
                    .single()
                    .decodedBytes,
            )
            assertEquals(
                4,
                f.events.clipboardAudits
                    .single()
                    .maxDecodedBytes,
            )
            assertTrue(f.events.clipboardWrites.isEmpty())
        }

        @Test
        fun `OSC 52 prompt and read query policies do not emit decoded clipboard writes`() {
            val prompt =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.PROMPT,
                                ),
                        ),
                )
            val read =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    readPermission = TerminalClipboardPermission.ALLOW,
                                ),
                        ),
                )

            prompt.acceptAscii("\u001B]52;c;SGVsbG8=\u0007")
            read.acceptAscii("\u001B]52;c;?\u0007")

            assertAll(
                {
                    assertEquals(
                        TerminalClipboardDecision.PROMPT_REQUIRED,
                        prompt.events.clipboardAudits
                            .single()
                            .decision,
                    )
                },
                { assertTrue(prompt.events.clipboardWrites.isEmpty()) },
                {
                    assertEquals(
                        "Hello",
                        prompt.events.clipboardPrompts
                            .single()
                            .text,
                    )
                },
                {
                    assertEquals(
                        TerminalClipboardOperation.READ_QUERY,
                        read.events.clipboardAudits
                            .single()
                            .operation,
                    )
                },
                {
                    assertEquals(
                        TerminalClipboardDecision.ALLOWED_BY_POLICY,
                        read.events.clipboardAudits
                            .single()
                            .decision,
                    )
                },
                { assertTrue(read.events.clipboardWrites.isEmpty()) },
                { assertTrue(read.events.clipboardPrompts.isEmpty()) },
            )
        }

        @Test
        fun `OSC 52 empty allowed write emits clipboard clear text`() {
            val f =
                Fixture(
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    origin = TerminalClipboardOrigin.LOCAL,
                                    localWritePermission = TerminalClipboardPermission.ALLOW,
                                ),
                        ),
                )

            f.acceptAscii("\u001B]52;c;\u0007")

            assertEquals(
                "",
                f.events.clipboardWrites
                    .single()
                    .text,
            )
            assertEquals(
                0,
                f.events.clipboardAudits
                    .single()
                    .decodedBytes,
            )
        }
    }

    @Nested
    @DisplayName("window manipulation")
    inner class WindowManipulation {
        @Test
        fun `window manipulations are forwarded to the event sink`() {
            val f = Fixture()
            f.sink.resizeWindow(24, 80)
            f.sink.moveWindow(100, 200)
            f.sink.minimizeWindow()
            f.sink.deminimizeWindow()
            f.sink.raiseWindow()
            f.sink.lowerWindow()
            f.sink.setMaximized(true)
            f.sink.setMaximized(false)

            assertEquals(listOf(24 to 80), f.events.resizes)
            assertEquals(listOf(100 to 200), f.events.moves)
            assertEquals(1, f.events.minimizes)
            assertEquals(1, f.events.deminimizes)
            assertEquals(1, f.events.raises)
            assertEquals(1, f.events.lowers)
            assertEquals(listOf(true, false), f.events.maximisedStates)
        }

        @Test
        fun `window manipulation policy can deny host window requests`() {
            val f = Fixture(hostPolicy = HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY))
            f.sink.resizeWindow(24, 80)
            f.sink.moveWindow(100, 200)
            f.sink.minimizeWindow()
            f.sink.deminimizeWindow()
            f.sink.raiseWindow()
            f.sink.lowerWindow()
            f.sink.setMaximized(true)

            assertAll(
                { assertTrue(f.events.resizes.isEmpty()) },
                { assertTrue(f.events.moves.isEmpty()) },
                { assertEquals(0, f.events.minimizes) },
                { assertEquals(0, f.events.deminimizes) },
                { assertEquals(0, f.events.raises) },
                { assertEquals(0, f.events.lowers) },
                { assertTrue(f.events.maximisedStates.isEmpty()) },
            )
        }
    }

    private class RecordingHostEventSink : HostEventSink {
        var bells: Int = 0
        val iconTitles = mutableListOf<String>()
        val windowTitles = mutableListOf<String>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        val moves = mutableListOf<Pair<Int, Int>>()
        var minimizes = 0
        var deminimizes = 0
        var raises = 0
        var lowers = 0
        val maximisedStates = mutableListOf<Boolean>()
        val shellIntegrationEvents = mutableListOf<ShellIntegrationEvent>()
        val currentWorkingDirectories = mutableListOf<String>()

        override fun bell() {
            bells++
        }

        override fun iconTitleChanged(title: String) {
            iconTitles += title
        }

        override fun windowTitleChanged(title: String) {
            windowTitles += title
        }

        override fun currentWorkingDirectoryChanged(uri: String) {
            currentWorkingDirectories += uri
        }

        override fun resizeWindow(
            rows: Int,
            columns: Int,
        ) {
            resizes += rows to columns
        }

        override fun moveWindow(
            x: Int,
            y: Int,
        ) {
            moves += x to y
        }

        override fun minimizeWindow() {
            minimizes++
        }

        override fun deminimizeWindow() {
            deminimizes++
        }

        override fun raiseWindow() {
            raises++
        }

        override fun lowerWindow() {
            lowers++
        }

        override fun setMaximized(maximize: Boolean) {
            maximisedStates += maximize
        }

        override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
            shellIntegrationEvents += event
        }

        val notifications = mutableListOf<Triple<String, String, NotificationLevel>>()
        val clipboardAudits = mutableListOf<TerminalClipboardAuditEvent>()
        val clipboardWrites = mutableListOf<TerminalClipboardWriteEvent>()
        val clipboardPrompts = mutableListOf<TerminalClipboardPromptEvent>()

        override fun showNotification(
            title: String,
            body: String,
            level: NotificationLevel,
        ) {
            notifications += Triple(title, body, level)
        }

        override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
            clipboardAudits += event
        }

        override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
            clipboardWrites += event
        }

        override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
            clipboardPrompts += event
        }
    }
}
