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
package com.gagik.parser.ansi

import com.gagik.parser.runtime.ParserState
import com.gagik.parser.text.PrintableProcessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CommandDispatcher")
class CommandDispatcherTest {
    private fun executeControl(byteValue: Int): RecordingTerminalCommandSink = executeControl(ParserState(), byteValue)

    private fun executeControl(
        state: ParserState,
        byteValue: Int,
    ): RecordingTerminalCommandSink {
        val sink = RecordingTerminalCommandSink()
        AnsiCommandDispatcher.executeControl(
            sink = sink,
            state = state,
            controlByte = byteValue,
        )
        return sink
    }

    private fun dispatchEsc(
        finalByte: Char,
        state: ParserState = ParserState(),
        intermediates: Int = 0,
        intermediateCount: Int = 0,
    ): RecordingTerminalCommandSink {
        val sink = RecordingTerminalCommandSink()
        state.intermediates = intermediates
        state.intermediateCount = intermediateCount
        AnsiCommandDispatcher.dispatchEsc(
            sink = sink,
            state = state,
            finalByte = finalByte.code,
        )
        return sink
    }

    private fun dispatchCsi(
        finalByte: Int,
        params: List<Int> = emptyList(),
        privateMarker: Int = 0,
        intermediates: Int = 0,
        intermediateCount: Int = 0,
        subParameterMask: Int = 0,
    ): RecordingTerminalCommandSink {
        val sink = RecordingTerminalCommandSink()
        val state = ParserState(maxParams = 32)
        for (i in params.indices) {
            state.params[i] = params[i]
        }
        state.paramCount = params.size
        state.privateMarker = privateMarker
        state.intermediates = intermediates
        state.intermediateCount = intermediateCount
        state.subParameterMask = subParameterMask

        AnsiCommandDispatcher.dispatchCsi(
            sink = sink,
            state = state,
            finalByte = finalByte,
        )

        return sink
    }

    private fun dispatchCsi(
        finalByte: Char,
        params: List<Int> = emptyList(),
        privateMarker: Int = 0,
        intermediates: Int = 0,
        intermediateCount: Int = 0,
        subParameterMask: Int = 0,
    ): RecordingTerminalCommandSink =
        dispatchCsi(
            finalByte = finalByte.code,
            params = params,
            privateMarker = privateMarker,
            intermediates = intermediates,
            intermediateCount = intermediateCount,
            subParameterMask = subParameterMask,
        )

    // ----- executeControl ---------------------------------------------------

    @Nested
    @DisplayName("executeControl")
    inner class ExecuteControl {
        @Test
        fun `BEL dispatches bell`() {
            assertEquals(listOf("bell"), executeControl(0x07).events)
        }

        @Test
        fun `BS dispatches backspace`() {
            assertEquals(listOf("backspace"), executeControl(0x08).events)
        }

        @Test
        fun `HT dispatches tab`() {
            assertEquals(listOf("tab"), executeControl(0x09).events)
        }

        @Test
        fun `LF VT and FF dispatch lineFeed`() {
            assertEquals(listOf("lineFeed"), executeControl(0x0A).events)
            assertEquals(listOf("lineFeed"), executeControl(0x0B).events)
            assertEquals(listOf("lineFeed"), executeControl(0x0C).events)
        }

        @Test
        fun `CR dispatches carriageReturn`() {
            assertEquals(listOf("carriageReturn"), executeControl(0x0D).events)
        }

        @Test
        fun `SO and SI switch GL between G1 and G0 without terminal sink events`() {
            val state = ParserState()

            val soSink = executeControl(state, 0x0E)
            val siSink = executeControl(state, 0x0F)

            assertAll(
                { assertTrue(soSink.events.isEmpty()) },
                { assertTrue(siSink.events.isEmpty()) },
                { assertEquals(0, state.glSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }

        @Test
        fun `SO selects G1 and SI selects G0 without mutating charset designations`() {
            val state = ParserState()
            state.charsets[1] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
            state.charsets[2] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
            state.singleShiftSlot = 2

            executeControl(state, 0x0E)
            assertAll(
                { assertEquals(1, state.glSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertArrayEquals(intArrayOf(0, 1, 1, 0), state.charsets) },
            )

            state.singleShiftSlot = 3
            executeControl(state, 0x0F)

            assertAll(
                { assertEquals(0, state.glSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
                { assertArrayEquals(intArrayOf(0, 1, 1, 0), state.charsets) },
            )
        }

        @Test
        fun `unsupported C0 controls are ignored`() {
            assertTrue(executeControl(0x00).events.isEmpty())
        }
    }

    // ----- ESC --------------------------------------------------------------

    @Nested
    @DisplayName("dispatchEsc")
    inner class DispatchEsc {
        @Test
        fun `ESC 7 saves cursor`() {
            assertEquals(listOf("saveCursor"), dispatchEsc('7').events)
        }

        @Test
        fun `ESC 8 restores cursor`() {
            assertEquals(listOf("restoreCursor"), dispatchEsc('8').events)
        }

        @Test
        fun `ESC hash 8 dispatches alignment test decaln`() {
            assertEquals(
                listOf("decaln"),
                dispatchEsc('8', intermediates = '#'.code, intermediateCount = 1).events,
            )
        }

        @Test
        fun `ESC D performs lineFeed`() {
            assertEquals(listOf("lineFeed"), dispatchEsc('D').events)
        }

        @Test
        fun `ESC E performs nextLine`() {
            assertEquals(listOf("nextLine"), dispatchEsc('E').events)
        }

        @Test
        fun `ESC H sets a tab stop`() {
            assertEquals(listOf("setTabStop"), dispatchEsc('H').events)
        }

        @Test
        fun `ESC c dispatches full terminal reset`() {
            assertEquals(listOf("resetTerminal"), dispatchEsc('c').events)
        }

        @Test
        fun `ESC M performs reverseIndex`() {
            assertEquals(listOf("reverseIndex"), dispatchEsc('M').events)
        }

        @Test
        fun `ESC N single shifts G2 for one printable character`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)
            state.charsets[2] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS

            AnsiCommandDispatcher.dispatchEsc(sink, state, 'N'.code)
            processor.acceptAsciiByte(state, 'q'.code)
            processor.acceptAsciiByte(state, 'q'.code)
            processor.flush(state)

            assertAll(
                { assertEquals(-1, state.singleShiftSlot) },
                {
                    assertEquals(
                        listOf(
                            "writeCodepoint:9472",
                            "writeCodepoint:113",
                        ),
                        sink.events,
                    )
                },
            )
        }

        @Test
        fun `ESC O single shifts G3 for one printable character`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)
            state.charsets[3] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS

            AnsiCommandDispatcher.dispatchEsc(sink, state, 'O'.code)
            processor.acceptAsciiByte(state, 'x'.code)
            processor.acceptAsciiByte(state, 'x'.code)
            processor.flush(state)

            assertAll(
                { assertEquals(-1, state.singleShiftSlot) },
                {
                    assertEquals(
                        listOf(
                            "writeCodepoint:9474",
                            "writeCodepoint:120",
                        ),
                        sink.events,
                    )
                },
            )
        }

        @Test
        fun `plain ESC commands require no intermediate bytes`() {
            assertAll(
                { assertTrue(dispatchEsc('7', intermediates = '#'.code, intermediateCount = 1).events.isEmpty()) },
                { assertTrue(dispatchEsc('D', intermediates = '#'.code, intermediateCount = 1).events.isEmpty()) },
                {
                    assertTrue(
                        dispatchEsc(
                            finalByte = 'M',
                            intermediates = '#'.code or ('$'.code shl 8),
                            intermediateCount = 2,
                        ).events.isEmpty(),
                    )
                },
            )
        }

        @Test
        fun `ESC left paren 0 designates G0 DEC Special Graphics`() {
            val state = ParserState()
            val sink = dispatchEsc('0', state, intermediates = '('.code, intermediateCount = 1)

            assertAll(
                { assertEquals(ParserState.CHARSET_DEC_SPECIAL_GRAPHICS, state.charsets[0]) },
                { assertEquals(ParserState.CHARSET_ASCII, state.charsets[1]) },
                { assertTrue(sink.events.isEmpty()) },
            )
        }

        @Test
        fun `ESC right paren 0 designates G1 DEC Special Graphics`() {
            val state = ParserState()
            val sink = dispatchEsc('0', state, intermediates = ')'.code, intermediateCount = 1)

            assertAll(
                { assertEquals(ParserState.CHARSET_ASCII, state.charsets[0]) },
                { assertEquals(ParserState.CHARSET_DEC_SPECIAL_GRAPHICS, state.charsets[1]) },
                { assertTrue(sink.events.isEmpty()) },
            )
        }

        @Test
        fun `ESC star 0 and ESC plus 0 designate G2 and G3 DEC Special Graphics`() {
            val state = ParserState()

            val g2Sink = dispatchEsc('0', state, intermediates = '*'.code, intermediateCount = 1)
            val g3Sink = dispatchEsc('0', state, intermediates = '+'.code, intermediateCount = 1)

            assertAll(
                { assertEquals(ParserState.CHARSET_DEC_SPECIAL_GRAPHICS, state.charsets[2]) },
                { assertEquals(ParserState.CHARSET_DEC_SPECIAL_GRAPHICS, state.charsets[3]) },
                { assertTrue(g2Sink.events.isEmpty()) },
                { assertTrue(g3Sink.events.isEmpty()) },
            )
        }

        @Test
        fun `ESC charset B designates ASCII for all supported G slots`() {
            val state = ParserState()
            state.charsets[0] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
            state.charsets[1] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
            state.charsets[2] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
            state.charsets[3] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS

            dispatchEsc('B', state, intermediates = '('.code, intermediateCount = 1)
            dispatchEsc('B', state, intermediates = ')'.code, intermediateCount = 1)
            dispatchEsc('B', state, intermediates = '*'.code, intermediateCount = 1)
            dispatchEsc('B', state, intermediates = '+'.code, intermediateCount = 1)

            assertArrayEquals(intArrayOf(0, 0, 0, 0), state.charsets)
        }

        @Test
        fun `unsupported charset designation final is consumed without dispatching a plain ESC command`() {
            val state = ParserState()

            val sink = dispatchEsc('D', state, intermediates = '('.code, intermediateCount = 1)

            assertAll(
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), state.charsets) },
                { assertTrue(sink.events.isEmpty()) },
            )
        }

        @Test
        fun `unknown ESC intermediate shape is ignored instead of dispatching plain ESC final semantics`() {
            val state = ParserState()

            val sink = dispatchEsc('D', state, intermediates = '#'.code, intermediateCount = 1)

            assertAll(
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), state.charsets) },
                { assertTrue(sink.events.isEmpty()) },
            )
        }

        @Test
        fun `ESC charset designation drives printable DEC Special Graphics output`() {
            val state = ParserState()
            val sink = RecordingTerminalCommandSink()
            val processor = PrintableProcessor(sink)

            AnsiCommandDispatcher.dispatchEsc(
                sink = sink,
                state =
                    state.also {
                        it.intermediates = '('.code
                        it.intermediateCount = 1
                    },
                finalByte = '0'.code,
            )
            state.clearSequenceState()

            processor.acceptAsciiByte(state, 'q'.code)
            processor.flush(state)

            assertEquals(listOf("writeCodepoint:9472"), sink.events)
        }
    }

    // ----- CSI cursor movement ---------------------------------------------

    @Nested
    @DisplayName("CSI cursor movement")
    inner class CsiCursorMovement {
        @Test
        fun `CSI A B C and D dispatch relative cursor movement`() {
            assertEquals(listOf("cursorUp:5"), dispatchCsi('A', params = listOf(5)).events)
            assertEquals(listOf("cursorDown:1"), dispatchCsi('B').events)
            assertEquals(listOf("cursorForward:1"), dispatchCsi('C', params = listOf(0)).events)
            assertEquals(listOf("cursorBackward:1"), dispatchCsi('D', params = listOf(-1)).events)
        }

        @Test
        fun `CSI E and F dispatch cursor next and previous line`() {
            assertEquals(listOf("cursorNextLine:1"), dispatchCsi('E').events)
            assertEquals(listOf("cursorNextLine:1"), dispatchCsi('E', params = listOf(0)).events)
            assertEquals(listOf("cursorPreviousLine:3"), dispatchCsi('F', params = listOf(3)).events)
            assertEquals(listOf("cursorPreviousLine:1"), dispatchCsi('F', params = listOf(-1)).events)
        }

        @Test
        fun `CSI G and d dispatch absolute column and row as zero-origin`() {
            assertEquals(listOf("setCursorColumn:0"), dispatchCsi('G').events)
            assertEquals(listOf("setCursorColumn:9"), dispatchCsi('G', params = listOf(10)).events)
            assertEquals(listOf("setCursorRow:0"), dispatchCsi('d', params = listOf(0)).events)
            assertEquals(listOf("setCursorRow:6"), dispatchCsi('d', params = listOf(7)).events)
        }

        @Test
        fun `CSI I and Z dispatch cursor tabulation counts`() {
            assertEquals(listOf("cursorForwardTabs:1"), dispatchCsi('I').events)
            assertEquals(listOf("cursorForwardTabs:1"), dispatchCsi('I', params = listOf(0)).events)
            assertEquals(listOf("cursorForwardTabs:4"), dispatchCsi('I', params = listOf(4)).events)
            assertEquals(listOf("cursorBackwardTabs:1"), dispatchCsi('Z').events)
            assertEquals(listOf("cursorBackwardTabs:1"), dispatchCsi('Z', params = listOf(-1)).events)
            assertEquals(listOf("cursorBackwardTabs:5"), dispatchCsi('Z', params = listOf(5)).events)
        }

        @Test
        fun `CSI H and f dispatch absolute cursor position as zero-origin`() {
            assertEquals(listOf("setCursorAbsolute:0:0"), dispatchCsi('H').events)
            assertEquals(listOf("setCursorAbsolute:11:33"), dispatchCsi('f', params = listOf(12, 34)).events)
        }

        @Test
        fun `CSI H defaults omitted or zero row and column to one before zero-origin translation`() {
            assertEquals(listOf("setCursorAbsolute:0:4"), dispatchCsi('H', params = listOf(-1, 5)).events)
            assertEquals(listOf("setCursorAbsolute:0:0"), dispatchCsi('H', params = listOf(0, 0)).events)
        }

        @Test
        fun `CSI 9999 9999 H passes through zero-origin values without clamping`() {
            assertEquals(listOf("setCursorAbsolute:9998:9998"), dispatchCsi('H', params = listOf(9999, 9999)).events)
        }
    }

    // ----- CSI erase edit scroll -------------------------------------------

    @Nested
    @DisplayName("CSI erase edit and scroll")
    inner class CsiEraseEditAndScroll {
        @Test
        fun `CSI J and K dispatch erase commands with default mode zero`() {
            assertEquals(listOf("eraseInDisplay:0:false"), dispatchCsi('J').events)
            assertEquals(listOf("eraseInDisplay:2:false"), dispatchCsi('J', params = listOf(2)).events)
            assertEquals(listOf("eraseInLine:0:false"), dispatchCsi('K').events)
            assertEquals(listOf("eraseInLine:1:false"), dispatchCsi('K', params = listOf(1)).events)
        }

        @Test
        fun `CSI private J and K dispatch selective erase commands`() {
            assertEquals(
                listOf("eraseInDisplay:0:true"),
                dispatchCsi('J', privateMarker = '?'.code).events,
            )
            assertEquals(
                listOf("eraseInDisplay:2:true"),
                dispatchCsi('J', params = listOf(2), privateMarker = '?'.code).events,
            )
            assertEquals(
                listOf("eraseInLine:0:true"),
                dispatchCsi('K', privateMarker = '?'.code).events,
            )
            assertEquals(
                listOf("eraseInLine:1:true"),
                dispatchCsi('K', params = listOf(1), privateMarker = '?'.code).events,
            )
        }

        @Test
        fun `CSI L M at P and X dispatch edit commands`() {
            assertEquals(listOf("insertLines:1"), dispatchCsi('L').events)
            assertEquals(listOf("deleteLines:2"), dispatchCsi('M', params = listOf(2)).events)
            assertEquals(listOf("insertCharacters:1"), dispatchCsi('@', params = listOf(0)).events)
            assertEquals(listOf("deleteCharacters:3"), dispatchCsi('P', params = listOf(3)).events)
            assertEquals(listOf("eraseCharacters:1"), dispatchCsi('X', params = listOf(-1)).events)
        }

        @Test
        fun `CSI S and T dispatch scroll commands`() {
            assertEquals(listOf("scrollUp:1"), dispatchCsi('S').events)
            assertEquals(listOf("scrollUp:1"), dispatchCsi('S', params = listOf(0)).events)
            assertEquals(listOf("scrollDown:4"), dispatchCsi('T', params = listOf(4)).events)
        }

        @Test
        fun `CSI t dispatches only safe window reports and title stack operations`() {
            assertEquals(listOf("requestWindowReport:14"), dispatchCsi('t', params = listOf(14)).events)
            assertEquals(listOf("requestWindowReport:18"), dispatchCsi('t', params = listOf(18)).events)
            assertEquals(listOf("pushTitleStack:0"), dispatchCsi('t', params = listOf(22)).events)
            assertEquals(listOf("pushTitleStack:1"), dispatchCsi('t', params = listOf(22, 1)).events)
            assertEquals(listOf("pushTitleStack:2"), dispatchCsi('t', params = listOf(22, 2)).events)
            assertEquals(listOf("popTitleStack:0"), dispatchCsi('t', params = listOf(23)).events)
            assertEquals(listOf("popTitleStack:1"), dispatchCsi('t', params = listOf(23, 1)).events)
            assertEquals(listOf("popTitleStack:2"), dispatchCsi('t', params = listOf(23, 2)).events)
        }

        @Test
        fun `unsafe or malformed CSI t operations are ignored`() {
            assertTrue(dispatchCsi('t').events.isEmpty())
            assertTrue(dispatchCsi('t', params = listOf(3)).events.isEmpty())
            assertTrue(dispatchCsi('t', params = listOf(8, 40, 120)).events.isEmpty())
            assertTrue(dispatchCsi('t', params = listOf(22, 3)).events.isEmpty())
            assertTrue(dispatchCsi('t', params = listOf(23, 3)).events.isEmpty())
        }

        @Test
        fun `CSI n dispatches DSR and CPR requests`() {
            assertEquals(
                listOf("requestDeviceStatusReport:5:false"),
                dispatchCsi('n', params = listOf(5)).events,
            )
            assertEquals(
                listOf("requestDeviceStatusReport:6:false"),
                dispatchCsi('n', params = listOf(6)).events,
            )
            assertEquals(
                listOf("requestDeviceStatusReport:6:true"),
                dispatchCsi('n', params = listOf(6), privateMarker = '?'.code).events,
            )
        }

        @Test
        fun `CSI r dispatches DECSTBM scroll region as zero-origin margins`() {
            assertEquals(listOf("setScrollRegion:0:-1"), dispatchCsi('r').events)
            assertEquals(listOf("setScrollRegion:0:9"), dispatchCsi('r', params = listOf(-1, 10)).events)
            assertEquals(listOf("setScrollRegion:4:-1"), dispatchCsi('r', params = listOf(5, -1)).events)
            assertEquals(listOf("setScrollRegion:4:9"), dispatchCsi('r', params = listOf(5, 10)).events)
            assertEquals(listOf("setScrollRegion:0:-1"), dispatchCsi('r', params = listOf(0, 0)).events)
        }

        @Test
        fun `CSI s dispatches DECSLRM left right margins as zero-origin margins`() {
            assertEquals(listOf("setLeftRightMargins:0:-1"), dispatchCsi('s').events)
            assertEquals(listOf("setLeftRightMargins:0:9"), dispatchCsi('s', params = listOf(-1, 10)).events)
            assertEquals(listOf("setLeftRightMargins:4:-1"), dispatchCsi('s', params = listOf(5, -1)).events)
            assertEquals(listOf("setLeftRightMargins:4:9"), dispatchCsi('s', params = listOf(5, 10)).events)
            assertEquals(listOf("setLeftRightMargins:0:-1"), dispatchCsi('s', params = listOf(0, 0)).events)
        }

        @Test
        fun `CSI double quote q dispatches DECSCA selective erase protection`() {
            assertEquals(
                listOf("setSelectiveEraseProtection:false"),
                dispatchCsi('q', intermediates = '"'.code, intermediateCount = 1).events,
            )
            assertEquals(
                listOf("setSelectiveEraseProtection:false"),
                dispatchCsi('q', params = listOf(0), intermediates = '"'.code, intermediateCount = 1).events,
            )
            assertEquals(
                listOf("setSelectiveEraseProtection:true"),
                dispatchCsi('q', params = listOf(1), intermediates = '"'.code, intermediateCount = 1).events,
            )
            assertEquals(
                listOf("setSelectiveEraseProtection:false"),
                dispatchCsi('q', params = listOf(2), intermediates = '"'.code, intermediateCount = 1).events,
            )
            assertTrue(dispatchCsi('q', params = listOf(3), intermediates = '"'.code, intermediateCount = 1).events.isEmpty())
        }
    }

    // ----- CSI tab stops ----------------------------------------------------

    @Nested
    @DisplayName("CSI tab stop dispatch")
    inner class CsiTabStopDispatch {
        @Test
        fun `CSI g clears current tab stop by default or mode zero`() {
            assertEquals(listOf("clearTabStop"), dispatchCsi('g').events)
            assertEquals(listOf("clearTabStop"), dispatchCsi('g', params = listOf(0)).events)
            assertEquals(listOf("clearTabStop"), dispatchCsi('g', params = listOf(-1)).events)
        }

        @Test
        fun `CSI 3 g clears all tab stops`() {
            assertEquals(listOf("clearAllTabStops"), dispatchCsi('g', params = listOf(3)).events)
        }

        @Test
        fun `unsupported CSI g modes are ignored`() {
            assertTrue(dispatchCsi('g', params = listOf(1)).events.isEmpty())
            assertTrue(dispatchCsi('g', params = listOf(2)).events.isEmpty())
            assertTrue(dispatchCsi('g', params = listOf(4)).events.isEmpty())
        }
    }

    // ----- CSI modes --------------------------------------------------------

    @Nested
    @DisplayName("CSI mode dispatch")
    inner class CsiModeDispatch {
        @Test
        fun `CSI c dispatches primary secondary and tertiary DA requests`() {
            assertEquals(listOf("requestDeviceAttributes:0:0"), dispatchCsi('c').events)
            assertEquals(listOf("requestDeviceAttributes:0:1"), dispatchCsi('c', params = listOf(1)).events)
            assertEquals(
                listOf("requestDeviceAttributes:1:0"),
                dispatchCsi('c', privateMarker = '>'.code).events,
            )
            assertEquals(
                listOf("requestDeviceAttributes:2:0"),
                dispatchCsi('c', privateMarker = '='.code).events,
            )
        }

        @Test
        fun `CSI h and l dispatch ANSI mode set and reset`() {
            assertEquals(
                listOf("setAnsiMode:4:true", "setAnsiMode:20:true"),
                dispatchCsi('h', params = listOf(4, 20)).events,
            )
            assertEquals(
                listOf("setAnsiMode:4:false"),
                dispatchCsi('l', params = listOf(4)).events,
            )
        }

        @Test
        fun `CSI private h and l dispatch DEC mode set and reset`() {
            assertEquals(
                listOf("setDecMode:25:true", "setDecMode:1049:true"),
                dispatchCsi('h', params = listOf(25, 1049), privateMarker = '?'.code).events,
            )
            assertEquals(
                listOf("setDecMode:25:false"),
                dispatchCsi('l', params = listOf(25), privateMarker = '?'.code).events,
            )
        }

        @Test
        fun `mode dispatch skips omitted params`() {
            assertEquals(
                listOf("setAnsiMode:4:true"),
                dispatchCsi('h', params = listOf(-1, 4)).events,
            )
        }

        @Test
        fun `unsupported private mode marker is ignored`() {
            assertTrue(dispatchCsi('h', params = listOf(4), privateMarker = '>'.code).events.isEmpty())
        }
    }

    // ----- CSI SGR ----------------------------------------------------------

    @Nested
    @DisplayName("CSI SGR dispatch")
    inner class CsiSgrDispatch {
        @Test
        fun `CSI m and CSI 0 m reset attributes`() {
            assertEquals(listOf("resetAttributes"), dispatchCsi('m').events)
            assertEquals(listOf("resetAttributes"), dispatchCsi('m', params = listOf(0)).events)
            assertEquals(listOf("resetAttributes"), dispatchCsi('m', params = listOf(-1)).events)
        }

        @Test
        fun `CSI 1 m enables bold and CSI 22 m disables bold and faint`() {
            assertEquals(listOf("setBold:true"), dispatchCsi('m', params = listOf(1)).events)
            assertEquals(
                listOf("setBold:false", "setFaint:false"),
                dispatchCsi('m', params = listOf(22)).events,
            )
        }

        @Test
        fun `CSI 2 m enables faint and CSI 22 m resets bold intensity state`() {
            assertEquals(listOf("setFaint:true"), dispatchCsi('m', params = listOf(2)).events)
            assertEquals(
                listOf("setBold:false", "setFaint:false"),
                dispatchCsi('m', params = listOf(22)).events,
            )
        }

        @Test
        fun `CSI 1 31 m applies bold then foreground red in order`() {
            assertEquals(
                listOf("setBold:true", "setForegroundIndexed:1"),
                dispatchCsi('m', params = listOf(1, 31)).events,
            )
        }

        @Test
        fun `CSI 31 1 m applies foreground red then bold in order`() {
            assertEquals(
                listOf("setForegroundIndexed:1", "setBold:true"),
                dispatchCsi('m', params = listOf(31, 1)).events,
            )
        }

        @Test
        fun `CSI 0 31 m resets then applies foreground red`() {
            assertEquals(
                listOf("resetAttributes", "setForegroundIndexed:1"),
                dispatchCsi('m', params = listOf(0, 31)).events,
            )
        }

        @Test
        fun `CSI omitted then 31 m resets then applies foreground red`() {
            assertEquals(
                listOf("resetAttributes", "setForegroundIndexed:1"),
                dispatchCsi('m', params = listOf(-1, 31)).events,
            )
        }

        @Test
        fun `CSI 31 then omitted m applies foreground red then resets`() {
            assertEquals(
                listOf("setForegroundIndexed:1", "resetAttributes"),
                dispatchCsi('m', params = listOf(31, -1)).events,
            )
        }

        @Test
        fun `CSI 3 m and 23 m toggle italic`() {
            assertEquals(listOf("setItalic:true"), dispatchCsi('m', params = listOf(3)).events)
            assertEquals(listOf("setItalic:false"), dispatchCsi('m', params = listOf(23)).events)
        }

        @Test
        fun `CSI 4 m 21 m and 24 m set underline styles`() {
            assertEquals(listOf("setUnderlineStyle:1"), dispatchCsi('m', params = listOf(4)).events)
            assertEquals(listOf("setUnderlineStyle:2"), dispatchCsi('m', params = listOf(21)).events)
            assertEquals(listOf("setUnderlineStyle:0"), dispatchCsi('m', params = listOf(24)).events)
        }

        @Test
        fun `CSI colon underline dispatches extended underline styles`() {
            assertEquals(
                listOf("setUnderlineStyle:3", "setUnderlineStyle:4", "setUnderlineStyle:5"),
                listOf(3, 4, 5).flatMap { style ->
                    dispatchCsi(
                        finalByte = 'm',
                        params = listOf(4, style),
                        subParameterMask = 0b10,
                    ).events
                },
            )
        }

        @Test
        fun `CSI 7 m and 27 m toggle inverse`() {
            assertEquals(listOf("setInverse:true"), dispatchCsi('m', params = listOf(7)).events)
            assertEquals(listOf("setInverse:false"), dispatchCsi('m', params = listOf(27)).events)
        }

        @Test
        fun `CSI 5 m 6 m and 25 m toggle blink`() {
            assertEquals(listOf("setBlink:true"), dispatchCsi('m', params = listOf(5)).events)
            assertEquals(listOf("setBlink:true"), dispatchCsi('m', params = listOf(6)).events)
            assertEquals(listOf("setBlink:false"), dispatchCsi('m', params = listOf(25)).events)
        }

        @Test
        fun `CSI 8 m and 28 m toggle conceal`() {
            assertEquals(listOf("setConceal:true"), dispatchCsi('m', params = listOf(8)).events)
            assertEquals(listOf("setConceal:false"), dispatchCsi('m', params = listOf(28)).events)
        }

        @Test
        fun `CSI 9 m and 29 m toggle strikethrough`() {
            assertEquals(listOf("setStrikethrough:true"), dispatchCsi('m', params = listOf(9)).events)
            assertEquals(listOf("setStrikethrough:false"), dispatchCsi('m', params = listOf(29)).events)
        }

        @Test
        fun `CSI 53 m and 55 m toggle overline`() {
            assertEquals(listOf("setOverline:true"), dispatchCsi('m', params = listOf(53)).events)
            assertEquals(listOf("setOverline:false"), dispatchCsi('m', params = listOf(55)).events)
        }

        @Test
        fun `standard foreground indexed colors map 30 through 37 to indexes 0 through 7`() {
            assertEquals(
                (0..7).map { "setForegroundIndexed:$it" },
                dispatchCsi('m', params = (30..37).toList()).events,
            )
        }

        @Test
        fun `bright foreground indexed colors map 90 through 97 to indexes 8 through 15`() {
            assertEquals(
                (8..15).map { "setForegroundIndexed:$it" },
                dispatchCsi('m', params = (90..97).toList()).events,
            )
        }

        @Test
        fun `standard background indexed colors map 40 through 47 to indexes 0 through 7`() {
            assertEquals(
                (0..7).map { "setBackgroundIndexed:$it" },
                dispatchCsi('m', params = (40..47).toList()).events,
            )
        }

        @Test
        fun `bright background indexed colors map 100 through 107 to indexes 8 through 15`() {
            assertEquals(
                (8..15).map { "setBackgroundIndexed:$it" },
                dispatchCsi('m', params = (100..107).toList()).events,
            )
        }

        @Test
        fun `CSI 39 m and 49 m restore default foreground and background`() {
            assertEquals(listOf("setForegroundDefault"), dispatchCsi('m', params = listOf(39)).events)
            assertEquals(listOf("setBackgroundDefault"), dispatchCsi('m', params = listOf(49)).events)
        }

        @Test
        fun `CSI 38 5 indexed foreground dispatches 256-color foreground`() {
            assertEquals(
                listOf("setForegroundIndexed:196"),
                dispatchCsi('m', params = listOf(38, 5, 196)).events,
            )
        }

        @Test
        fun `CSI 38 5 m ignores malformed indexed foreground`() {
            assertTrue(dispatchCsi('m', params = listOf(38, 5)).events.isEmpty())
        }

        @Test
        fun `CSI 38 5 300 m ignores invalid indexed foreground`() {
            assertTrue(dispatchCsi('m', params = listOf(38, 5, 300)).events.isEmpty())
        }

        @Test
        fun `CSI 48 5 indexed background dispatches 256-color background`() {
            assertEquals(
                listOf("setBackgroundIndexed:17"),
                dispatchCsi('m', params = listOf(48, 5, 17)).events,
            )
        }

        @Test
        fun `CSI 38 2 RGB foreground dispatches truecolor foreground`() {
            assertEquals(
                listOf("setForegroundRgb:10:20:30"),
                dispatchCsi('m', params = listOf(38, 2, 10, 20, 30)).events,
            )
        }

        @Test
        fun `CSI 38 2 incomplete RGB foreground is ignored`() {
            assertTrue(dispatchCsi('m', params = listOf(38, 2, 10, 20)).events.isEmpty())
        }

        @Test
        fun `CSI 38 2 RGB foreground then bold applies in order`() {
            assertEquals(
                listOf("setForegroundRgb:10:20:30", "setBold:true"),
                dispatchCsi('m', params = listOf(38, 2, 10, 20, 30, 1)).events,
            )
        }

        @Test
        fun `CSI 48 2 RGB background dispatches truecolor background`() {
            assertEquals(
                listOf("setBackgroundRgb:10:20:30"),
                dispatchCsi('m', params = listOf(48, 2, 10, 20, 30)).events,
            )
        }

        @Test
        fun `CSI 58 and 59 dispatch underline color updates`() {
            assertEquals(
                listOf("setUnderlineColorIndexed:42"),
                dispatchCsi('m', params = listOf(58, 5, 42)).events,
            )
            assertEquals(
                listOf("setUnderlineColorRgb:10:20:30"),
                dispatchCsi('m', params = listOf(58, 2, 10, 20, 30)).events,
            )
            assertEquals(listOf("setUnderlineColorDefault"), dispatchCsi('m', params = listOf(59)).events)
        }

        @Test
        fun `CSI colon indexed foreground dispatches indexed foreground`() {
            assertEquals(
                listOf("setForegroundIndexed:196"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(38, 5, 196),
                    subParameterMask = 0b110,
                ).events,
            )
        }

        @Test
        fun `CSI colon indexed background dispatches indexed background`() {
            assertEquals(
                listOf("setBackgroundIndexed:17"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(48, 5, 17),
                    subParameterMask = 0b110,
                ).events,
            )
        }

        @Test
        fun `CSI colon RGB foreground without color-space slot dispatches truecolor foreground`() {
            assertEquals(
                listOf("setForegroundRgb:10:20:30"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(38, 2, 10, 20, 30),
                    subParameterMask = 0b1_1110,
                ).events,
            )
        }

        @Test
        fun `CSI colon RGB foreground with explicit color-space id dispatches truecolor foreground`() {
            assertEquals(
                listOf("setForegroundRgb:10:20:30"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(38, 2, 1, 10, 20, 30),
                    subParameterMask = 0b11_1110,
                ).events,
            )
        }

        @Test
        fun `CSI colon RGB foreground supports omitted color-space id`() {
            assertEquals(
                listOf("setForegroundRgb:10:20:30"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(38, 2, -1, 10, 20, 30),
                    subParameterMask = 0b11_1110,
                ).events,
            )
        }

        @Test
        fun `CSI colon indexed foreground with explicit color-space id dispatches indexed foreground`() {
            assertEquals(
                listOf("setForegroundIndexed:196"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(38, 5, 1, 196),
                    subParameterMask = 0b1110,
                ).events,
            )
        }

        @Test
        fun `CSI colon underline double dispatches double underline only`() {
            assertEquals(
                listOf("setUnderlineStyle:2"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(4, 2),
                    subParameterMask = 0b10,
                ).events,
            )
        }

        @Test
        fun `mixed semicolon and colon SGR groups preserve ordering`() {
            assertEquals(
                listOf("resetAttributes", "setForegroundIndexed:196", "setBold:true"),
                dispatchCsi(
                    finalByte = 'm',
                    params = listOf(0, 38, 5, 196, 1),
                    subParameterMask = 0b1100,
                ).events,
            )
        }

        @Test
        fun `invalid indexed color is ignored and later normal SGR still applies`() {
            assertEquals(
                listOf("setBold:true"),
                dispatchCsi('m', params = listOf(38, 5, 256, 1)).events,
            )
        }

        @Test
        fun `unsupported SGR parameters are ignored`() {
            assertTrue(dispatchCsi('m', params = listOf(999, 9999)).events.isEmpty())
        }

        @Test
        fun `malformed extended color is ignored and later normal SGR still applies`() {
            assertEquals(
                listOf("setBold:true"),
                dispatchCsi('m', params = listOf(38, 2, 999, 20, 30, 1)).events,
            )
        }
    }

    // ----- CSI reset --------------------------------------------------------

    @Nested
    @DisplayName("CSI reset dispatch")
    inner class CsiResetDispatch {
        @Test
        fun `CSI bang p dispatches soft reset`() {
            val sink =
                dispatchCsi(
                    finalByte = 'p'.code,
                    intermediates = '!'.code,
                    intermediateCount = 1,
                )

            assertEquals(listOf("softReset"), sink.events)
        }

        @Test
        fun `plain CSI p does not dispatch soft reset`() {
            assertTrue(dispatchCsi(finalByte = 'p'.code).events.isEmpty())
        }
    }
}
