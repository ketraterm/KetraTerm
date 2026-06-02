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
package com.gagik.parser.ansi.mode

import com.gagik.parser.ansi.AnsiCommandDispatcher
import com.gagik.parser.ansi.RecordingTerminalCommandSink
import com.gagik.parser.fixture.TerminalParserFixture
import com.gagik.parser.runtime.ParserState
import com.gagik.terminal.protocol.AnsiMode
import com.gagik.terminal.protocol.DecPrivateMode
import com.gagik.terminal.protocol.XtermKeyFormatResource
import com.gagik.terminal.protocol.XtermKeyModifierResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Mode dispatcher")
class ModeDispatchTest {
    private fun dispatchMode(
        finalByte: Int,
        vararg params: Int,
        privateMarker: Int = 0,
    ): RecordingTerminalCommandSink {
        val sink = RecordingTerminalCommandSink()
        val state = ParserState(maxParams = 32)

        for (i in params.indices) {
            state.params[i] = params[i]
        }
        state.paramCount = params.size
        state.privateMarker = privateMarker

        AnsiCommandDispatcher.dispatchCsi(sink, state, finalByte)
        return sink
    }

    private fun setAnsi(vararg params: Int): RecordingTerminalCommandSink = dispatchMode('h'.code, params = params)

    private fun resetAnsi(vararg params: Int): RecordingTerminalCommandSink = dispatchMode('l'.code, params = params)

    private fun setDec(vararg params: Int): RecordingTerminalCommandSink = dispatchMode('h'.code, params = params, privateMarker = '?'.code)

    private fun resetDec(vararg params: Int): RecordingTerminalCommandSink =
        dispatchMode('l'.code, params = params, privateMarker = '?'.code)

    private fun ansiEvent(
        mode: Int,
        enable: Boolean,
    ): String = "setAnsiMode:$mode:$enable"

    private fun decEvent(
        mode: Int,
        enable: Boolean,
    ): String = "setDecMode:$mode:$enable"

    private fun keyModifierEvent(
        resource: Int,
        value: Int,
    ): String = "setKeyModifierOption:$resource:$value"

    private fun keyFormatEvent(
        resource: Int,
        value: Int,
    ): String = "setKeyFormatOption:$resource:$value"

    @Nested
    @DisplayName("ANSI modes CSI Pn h/l")
    inner class AnsiModes {
        @Test
        fun `CSI 4 h and l dispatch insert mode`() {
            assertEquals(listOf(ansiEvent(AnsiMode.INSERT, true)), setAnsi(AnsiMode.INSERT).events)
            assertEquals(listOf(ansiEvent(AnsiMode.INSERT, false)), resetAnsi(AnsiMode.INSERT).events)
        }

        @Test
        fun `CSI 20 h and l dispatch new line mode`() {
            assertEquals(listOf(ansiEvent(AnsiMode.NEW_LINE, true)), setAnsi(AnsiMode.NEW_LINE).events)
            assertEquals(listOf(ansiEvent(AnsiMode.NEW_LINE, false)), resetAnsi(AnsiMode.NEW_LINE).events)
        }

        @Test
        fun `all known ANSI modes can be set in one sequence preserving order`() {
            val modes = intArrayOf(AnsiMode.INSERT, AnsiMode.NEW_LINE)

            assertEquals(
                modes.map { ansiEvent(it, true) },
                setAnsi(*modes).events,
            )
        }

        @Test
        fun `all known ANSI modes can be reset in one sequence preserving order`() {
            val modes = intArrayOf(AnsiMode.INSERT, AnsiMode.NEW_LINE)

            assertEquals(
                modes.map { ansiEvent(it, false) },
                resetAnsi(*modes).events,
            )
        }
    }

    @Nested
    @DisplayName("DEC private modes CSI question Pn h/l")
    inner class DecPrivateModes {
        @Test
        fun `cursor and rendering physics modes dispatch`() {
            val modes =
                intArrayOf(
                    DecPrivateMode.APPLICATION_CURSOR_KEYS,
                    DecPrivateMode.DECCOLM,
                    DecPrivateMode.REVERSE_VIDEO,
                    DecPrivateMode.ORIGIN,
                    DecPrivateMode.AUTO_WRAP,
                    DecPrivateMode.CURSOR_BLINK,
                    DecPrivateMode.CURSOR_VISIBLE,
                    DecPrivateMode.APPLICATION_KEYPAD,
                    DecPrivateMode.LEFT_RIGHT_MARGIN,
                )

            assertEquals(
                modes.map { decEvent(it, true) },
                setDec(*modes).events,
            )
            assertEquals(
                modes.map { decEvent(it, false) },
                resetDec(*modes).events,
            )
        }

        @Test
        fun `mouse protocol modes dispatch`() {
            val modes =
                intArrayOf(
                    DecPrivateMode.MOUSE_X10,
                    DecPrivateMode.MOUSE_NORMAL,
                    DecPrivateMode.MOUSE_BUTTON_EVENT,
                    DecPrivateMode.MOUSE_ANY_EVENT,
                    DecPrivateMode.MOUSE_UTF8,
                    DecPrivateMode.MOUSE_SGR,
                    DecPrivateMode.MOUSE_URXVT,
                    DecPrivateMode.SYNCHRONIZED_OUTPUT,
                )

            assertEquals(
                modes.map { decEvent(it, true) },
                setDec(*modes).events,
            )
            assertEquals(
                modes.map { decEvent(it, false) },
                resetDec(*modes).events,
            )
        }

        @Test
        fun `focus and bracketed paste modes dispatch`() {
            val modes =
                intArrayOf(
                    DecPrivateMode.FOCUS_REPORTING,
                    DecPrivateMode.BRACKETED_PASTE,
                )

            assertEquals(
                modes.map { decEvent(it, true) },
                setDec(*modes).events,
            )
            assertEquals(
                modes.map { decEvent(it, false) },
                resetDec(*modes).events,
            )
        }

        @Test
        fun `alternate screen and cursor save modes dispatch`() {
            val modes =
                intArrayOf(
                    DecPrivateMode.ALT_SCREEN,
                    DecPrivateMode.ALT_SCREEN_BUFFER,
                    DecPrivateMode.SAVE_RESTORE_CURSOR,
                    DecPrivateMode.ALT_SCREEN_SAVE_CURSOR,
                )

            assertEquals(
                modes.map { decEvent(it, true) },
                setDec(*modes).events,
            )
            assertEquals(
                modes.map { decEvent(it, false) },
                resetDec(*modes).events,
            )
        }
    }

    @Nested
    @DisplayName("sequence behavior")
    inner class SequenceBehavior {
        @Test
        fun `multiple modes in one DEC private sequence preserve source order`() {
            val sink =
                setDec(
                    DecPrivateMode.APPLICATION_CURSOR_KEYS,
                    DecPrivateMode.CURSOR_VISIBLE,
                    DecPrivateMode.BRACKETED_PASTE,
                )

            assertEquals(
                listOf(
                    decEvent(DecPrivateMode.APPLICATION_CURSOR_KEYS, true),
                    decEvent(DecPrivateMode.CURSOR_VISIBLE, true),
                    decEvent(DecPrivateMode.BRACKETED_PASTE, true),
                ),
                sink.events,
            )
        }

        @Test
        fun `omitted params are skipped without losing later modes`() {
            assertEquals(
                listOf(decEvent(DecPrivateMode.CURSOR_VISIBLE, true)),
                setDec(-1, DecPrivateMode.CURSOR_VISIBLE).events,
            )
            assertEquals(
                listOf(
                    decEvent(DecPrivateMode.APPLICATION_CURSOR_KEYS, false),
                    decEvent(DecPrivateMode.BRACKETED_PASTE, false),
                ),
                resetDec(DecPrivateMode.APPLICATION_CURSOR_KEYS, -1, DecPrivateMode.BRACKETED_PASTE).events,
            )
        }

        @Test
        fun `empty SM and RM sequences do not dispatch phantom mode zero`() {
            assertTrue(setAnsi().events.isEmpty())
            assertTrue(resetAnsi().events.isEmpty())
            assertTrue(setDec().events.isEmpty())
            assertTrue(resetDec().events.isEmpty())
        }

        @Test
        fun `zero and unknown positive modes pass through because policy belongs to the sink`() {
            assertEquals(
                listOf(ansiEvent(0, true), ansiEvent(9999, true)),
                setAnsi(0, 9999).events,
            )
            assertEquals(
                listOf(decEvent(0, false), decEvent(9999, false)),
                resetDec(0, 9999).events,
            )
        }

        @Test
        fun `unsupported private marker shape is ignored by CSI signature router`() {
            assertTrue(
                dispatchMode(
                    'h'.code,
                    DecPrivateMode.CURSOR_VISIBLE,
                    privateMarker = '>'.code,
                ).events.isEmpty(),
            )
        }
    }

    @Nested
    @DisplayName("xterm key option controls")
    inner class XtermKeyOptions {
        @Test
        fun `CSI greater-than Pp Pv m dispatches key modifier option set`() {
            assertEquals(
                listOf(keyModifierEvent(XtermKeyModifierResource.MODIFY_OTHER_KEYS, 3)),
                dispatchMode(
                    'm'.code,
                    XtermKeyModifierResource.MODIFY_OTHER_KEYS,
                    3,
                    privateMarker = '>'.code,
                ).events,
            )
        }

        @Test
        fun `CSI greater-than Pp Pv f dispatches key format option set`() {
            assertEquals(
                listOf(keyFormatEvent(XtermKeyFormatResource.FORMAT_OTHER_KEYS, 1)),
                dispatchMode(
                    'f'.code,
                    XtermKeyFormatResource.FORMAT_OTHER_KEYS,
                    1,
                    privateMarker = '>'.code,
                ).events,
            )
        }

        @Test
        fun `CSI greater-than Pp m and f reset one key option resource`() {
            assertEquals(
                listOf("resetKeyModifierOption:${XtermKeyModifierResource.MODIFY_OTHER_KEYS}"),
                dispatchMode(
                    'm'.code,
                    XtermKeyModifierResource.MODIFY_OTHER_KEYS,
                    privateMarker = '>'.code,
                ).events,
            )
            assertEquals(
                listOf("resetKeyFormatOption:${XtermKeyFormatResource.FORMAT_OTHER_KEYS}"),
                dispatchMode(
                    'f'.code,
                    XtermKeyFormatResource.FORMAT_OTHER_KEYS,
                    privateMarker = '>'.code,
                ).events,
            )
        }

        @Test
        fun `CSI greater-than empty m and f reset supported key option families`() {
            assertEquals(listOf("resetKeyModifierOptions"), dispatchMode('m'.code, privateMarker = '>'.code).events)
            assertEquals(listOf("resetKeyFormatOptions"), dispatchMode('f'.code, privateMarker = '>'.code).events)
        }

        @Test
        fun `colon subparameter modifier mask is ignored until explicitly supported`() {
            val state = ParserState(maxParams = 32)
            val sink = RecordingTerminalCommandSink()
            state.privateMarker = '>'.code
            state.params[0] = XtermKeyModifierResource.MODIFY_OTHER_KEYS
            state.params[1] = 1
            state.paramCount = 2
            state.subParameterMask = 0b10

            AnsiCommandDispatcher.dispatchCsi(sink, state, 'm'.code)

            assertTrue(sink.events.isEmpty())
        }
    }

    @Nested
    @DisplayName("full parser path")
    inner class FullParserPath {
        @Test
        fun `ANSI modes route through ByteClass FSM action engine and dispatcher`() {
            val fixture = TerminalParserFixture()

            fixture.acceptAscii("\u001B[4;20h\u001B[4;20l")

            assertEquals(
                listOf(
                    ansiEvent(AnsiMode.INSERT, true),
                    ansiEvent(AnsiMode.NEW_LINE, true),
                    ansiEvent(AnsiMode.INSERT, false),
                    ansiEvent(AnsiMode.NEW_LINE, false),
                ),
                fixture.sink.events,
            )
        }

        @Test
        fun `DEC private modes route through ByteClass FSM action engine and dispatcher`() {
            val fixture = TerminalParserFixture()

            fixture.acceptAscii("\u001B[?1;3;5;6;7;9;12;25;47;66;69h")
            fixture.acceptAscii("\u001B[?1000;1002;1003;1004;1005;1006;1015h")
            fixture.acceptAscii("\u001B[?1047;1048;1049;2004;2026h")

            assertEquals(
                listOf(
                    decEvent(DecPrivateMode.APPLICATION_CURSOR_KEYS, true),
                    decEvent(DecPrivateMode.DECCOLM, true),
                    decEvent(DecPrivateMode.REVERSE_VIDEO, true),
                    decEvent(DecPrivateMode.ORIGIN, true),
                    decEvent(DecPrivateMode.AUTO_WRAP, true),
                    decEvent(DecPrivateMode.MOUSE_X10, true),
                    decEvent(DecPrivateMode.CURSOR_BLINK, true),
                    decEvent(DecPrivateMode.CURSOR_VISIBLE, true),
                    decEvent(DecPrivateMode.ALT_SCREEN, true),
                    decEvent(DecPrivateMode.APPLICATION_KEYPAD, true),
                    decEvent(DecPrivateMode.LEFT_RIGHT_MARGIN, true),
                    decEvent(DecPrivateMode.MOUSE_NORMAL, true),
                    decEvent(DecPrivateMode.MOUSE_BUTTON_EVENT, true),
                    decEvent(DecPrivateMode.MOUSE_ANY_EVENT, true),
                    decEvent(DecPrivateMode.FOCUS_REPORTING, true),
                    decEvent(DecPrivateMode.MOUSE_UTF8, true),
                    decEvent(DecPrivateMode.MOUSE_SGR, true),
                    decEvent(DecPrivateMode.MOUSE_URXVT, true),
                    decEvent(DecPrivateMode.ALT_SCREEN_BUFFER, true),
                    decEvent(DecPrivateMode.SAVE_RESTORE_CURSOR, true),
                    decEvent(DecPrivateMode.ALT_SCREEN_SAVE_CURSOR, true),
                    decEvent(DecPrivateMode.BRACKETED_PASTE, true),
                    decEvent(DecPrivateMode.SYNCHRONIZED_OUTPUT, true),
                ),
                fixture.sink.events,
            )
        }

        @Test
        fun `full parser skips omitted DEC private fields and keeps following modes`() {
            val fixture = TerminalParserFixture()

            fixture.acceptAscii("\u001B[?;25;;2004l")

            assertEquals(
                listOf(
                    decEvent(DecPrivateMode.CURSOR_VISIBLE, false),
                    decEvent(DecPrivateMode.BRACKETED_PASTE, false),
                ),
                fixture.sink.events,
            )
        }

        @Test
        fun `xterm key option controls route through full parser`() {
            val fixture = TerminalParserFixture()

            fixture.acceptAscii("\u001B[>4;3m\u001B[>4;1f\u001B[>4m\u001B[>4f")

            assertEquals(
                listOf(
                    keyModifierEvent(XtermKeyModifierResource.MODIFY_OTHER_KEYS, 3),
                    keyFormatEvent(XtermKeyFormatResource.FORMAT_OTHER_KEYS, 1),
                    "resetKeyModifierOption:${XtermKeyModifierResource.MODIFY_OTHER_KEYS}",
                    "resetKeyFormatOption:${XtermKeyFormatResource.FORMAT_OTHER_KEYS}",
                ),
                fixture.sink.events,
            )
        }
    }
}
