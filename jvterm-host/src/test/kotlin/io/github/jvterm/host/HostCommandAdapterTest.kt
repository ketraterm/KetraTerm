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
package io.github.jvterm.host

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.core.api.TerminalBuffer
import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.parser.api.TerminalOutputParser
import io.github.jvterm.parser.api.TerminalParsers
import io.github.jvterm.protocol.MouseEncodingMode
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.protocol.keyboard.FormatOtherKeysMode
import io.github.jvterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.jvterm.protocol.keyboard.ModifyOtherKeysMode
import io.github.jvterm.render.api.TerminalRenderCursorShape
import io.github.jvterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("HostCommandAdapter")
class HostCommandAdapterTest {
    private data class Fixture(
        val terminal: TerminalBuffer = TerminalBuffers.create(width = 10, height = 5),
        val hostPolicy: HostPolicy = HostPolicy(),
        val sink: HostCommandAdapter =
            HostCommandAdapter(
                terminal = terminal,
                hostPolicy = hostPolicy,
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
            val text = "∀∂∈ℝ∧∪≡∞ ↑↗↨↻⇣ ┐┼╔╘░►☺♀ ﬁ�⑀₂ἠḂӥẄɐː⍎אԱა"
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
                    KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES or
                    KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
                f.terminal.getModeSnapshot().kittyKeyboardFlags,
            )

            f.acceptAscii("\u001B[=1;3u")
            assertEquals(
                KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES or
                    KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
                f.terminal.getModeSnapshot().kittyKeyboardFlags,
            )
        }

        @Test
        fun `Kitty keyboard controls mask unsupported flags through core storage`() {
            val f = Fixture()

            f.acceptAscii("\u001B[=1023u")

            assertEquals(KittyKeyboardProgressiveFlag.SUPPORTED_MASK, f.terminal.getModeSnapshot().kittyKeyboardFlags)
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

            // 1. Set initial flags to 3: CSI = 3 u
            f.acceptAscii("\u001B[=3u")
            assertEquals(3, f.terminal.getModeSnapshot().kittyKeyboardFlags)

            // 2. Push flags 12: CSI > 12 u
            f.acceptAscii("\u001B[>12u")
            assertEquals(12, f.terminal.getModeSnapshot().kittyKeyboardFlags)

            // 3. Pop 1 count: CSI < 1 u
            f.acceptAscii("\u001B[<1u")
            assertEquals(3, f.terminal.getModeSnapshot().kittyKeyboardFlags)

            // 4. Push 12, then pop using default count (omitted parameter) which defaults to 1: CSI < u
            f.acceptAscii("\u001B[>12u")
            assertEquals(12, f.terminal.getModeSnapshot().kittyKeyboardFlags)
            f.acceptAscii("\u001B[<u")
            assertEquals(3, f.terminal.getModeSnapshot().kittyKeyboardFlags)
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
                { assertEquals(1, f.terminal.getAttrAt(2, 0)?.hyperlinkId) },
                { assertEquals(3, f.terminal.getAttrAt(3, 0)?.hyperlinkId) },
                { assertEquals(4, f.terminal.getAttrAt(4, 0)?.hyperlinkId) },
                { assertNull(f.sink.hyperlinkUri(1)) },
                { assertEquals("https://example.com/c", f.sink.hyperlinkUri(3)) },
                { assertEquals("https://example.com/b", f.sink.hyperlinkUri(4)) },
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
                { assertEquals(16, f.terminal.getAttrAt(2, 0)?.hyperlinkId) },
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
    }

    private class RecordingHostEventSink : HostEventSink {
        var bells: Int = 0
        val iconTitles = mutableListOf<String>()
        val windowTitles = mutableListOf<String>()

        override fun bell() {
            bells++
        }

        override fun iconTitleChanged(title: String) {
            iconTitles += title
        }

        override fun windowTitleChanged(title: String) {
            windowTitles += title
        }

        override fun resizeWindow(
            rows: Int,
            columns: Int,
        ) {
            // No-op for tests unless assertions need it
        }
    }
}
