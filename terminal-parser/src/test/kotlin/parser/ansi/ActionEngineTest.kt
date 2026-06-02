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
import com.gagik.parser.spi.TerminalCommandSink
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ActionEngine")
class ActionEngineTest {
    // ----- Fixtures ---------------------------------------------------------

    private data class Fixture(
        val events: MutableList<String> = mutableListOf(),
        val sink: RecordingTerminalCommandSink = RecordingTerminalCommandSink(),
        val dispatcher: RecordingCommandDispatcher = RecordingCommandDispatcher(events),
        val printable: RecordingPrintableSink = RecordingPrintableSink(events),
    ) {
        val engine =
            ActionEngine(
                sink = sink,
                dispatcher = dispatcher,
                printableSink = printable,
            )
    }

    private data class DispatchSnapshot(
        val fsmState: Int,
        val paramCount: Int,
        val params: List<Int>,
        val currentParamStarted: Boolean,
        val subParameterMask: Int,
        val intermediates: Int,
        val intermediateCount: Int,
        val privateMarker: Int,
        val payloadLength: Int,
        val payloadCode: Int,
        val payloadOverflowed: Boolean,
    )

    private class RecordingPrintableSink(
        private val events: MutableList<String>,
    ) : PrintableActionSink {
        var flushCount = 0
        val asciiBytes = mutableListOf<Int>()
        val asciiStates = mutableListOf<Int>()
        val utf8Bytes = mutableListOf<Int>()
        val utf8States = mutableListOf<Int>()

        override fun onAsciiByte(
            state: ParserState,
            byteValue: Int,
        ) {
            asciiBytes += byteValue
            asciiStates += state.fsmState
            events += "printAscii:$byteValue"
        }

        override fun onUtf8Byte(
            state: ParserState,
            byteValue: Int,
        ) {
            utf8Bytes += byteValue
            utf8States += state.fsmState
            events += "printUtf8:$byteValue"
        }

        override fun flush(state: ParserState) {
            flushCount++
            events += "flush"
        }
    }

    private class RecordingCommandDispatcher(
        private val events: MutableList<String>,
    ) : CommandDispatcher {
        val controlBytes = mutableListOf<Int>()
        val escFinals = mutableListOf<Int>()
        val csiFinals = mutableListOf<Int>()
        val controlSnapshots = mutableListOf<DispatchSnapshot>()
        val escSnapshots = mutableListOf<DispatchSnapshot>()
        val csiSnapshots = mutableListOf<DispatchSnapshot>()

        override fun executeControl(
            sink: TerminalCommandSink,
            state: ParserState,
            controlByte: Int,
        ) {
            controlBytes += controlByte
            controlSnapshots += snapshot(state)
            events += "control:$controlByte"
        }

        override fun dispatchEsc(
            sink: TerminalCommandSink,
            state: ParserState,
            finalByte: Int,
        ) {
            escFinals += finalByte
            escSnapshots += snapshot(state)
            events += "esc:$finalByte"
        }

        override fun dispatchCsi(
            sink: TerminalCommandSink,
            state: ParserState,
            finalByte: Int,
        ) {
            csiFinals += finalByte
            csiSnapshots += snapshot(state)
            events += "csi:$finalByte"
        }

        private fun snapshot(state: ParserState): DispatchSnapshot =
            DispatchSnapshot(
                fsmState = state.fsmState,
                paramCount = state.paramCount,
                params = state.params.take(state.paramCount),
                currentParamStarted = state.currentParamStarted,
                subParameterMask = state.subParameterMask,
                intermediates = state.intermediates,
                intermediateCount = state.intermediateCount,
                privateMarker = state.privateMarker,
                payloadLength = state.payloadLength,
                payloadCode = state.payloadCode,
                payloadOverflowed = state.payloadOverflowed,
            )
    }

    private class RecordingTerminalCommandSink : TerminalCommandSink {
        val sinkCalls = mutableListOf<String>()

        override fun writeCodepoint(codepoint: Int) {
            sinkCalls += "writeCodepoint:$codepoint"
        }

        override fun writeCluster(
            codepoints: IntArray,
            length: Int,
        ) {
            sinkCalls += "writeCluster:$length"
        }

        override fun appendToPreviousCluster(codepoint: Int) {
            sinkCalls += "appendToPreviousCluster:$codepoint"
        }

        override fun bell() {
            sinkCalls += "bell"
        }

        override fun backspace() {
            sinkCalls += "backspace"
        }

        override fun tab() {
            sinkCalls += "tab"
        }

        override fun lineFeed() {
            sinkCalls += "lineFeed"
        }

        override fun carriageReturn() {
            sinkCalls += "carriageReturn"
        }

        override fun reverseIndex() {
            sinkCalls += "reverseIndex"
        }

        override fun nextLine() {
            sinkCalls += "nextLine"
        }

        override fun softReset() {
            sinkCalls += "softReset"
        }

        override fun resetTerminal() {
            sinkCalls += "resetTerminal"
        }

        override fun decaln() {
            sinkCalls += "decaln"
        }

        override fun saveCursor() {
            sinkCalls += "saveCursor"
        }

        override fun restoreCursor() {
            sinkCalls += "restoreCursor"
        }

        override fun setCursorStyle(style: Int) {
            sinkCalls += "setCursorStyle:$style"
        }

        override fun cursorUp(n: Int) {
            sinkCalls += "cursorUp:$n"
        }

        override fun cursorDown(n: Int) {
            sinkCalls += "cursorDown:$n"
        }

        override fun cursorForward(n: Int) {
            sinkCalls += "cursorForward:$n"
        }

        override fun cursorBackward(n: Int) {
            sinkCalls += "cursorBackward:$n"
        }

        override fun cursorNextLine(n: Int) {
            sinkCalls += "cursorNextLine:$n"
        }

        override fun cursorPreviousLine(n: Int) {
            sinkCalls += "cursorPreviousLine:$n"
        }

        override fun cursorForwardTabs(n: Int) {
            sinkCalls += "cursorForwardTabs:$n"
        }

        override fun cursorBackwardTabs(n: Int) {
            sinkCalls += "cursorBackwardTabs:$n"
        }

        override fun setCursorColumn(col: Int) {
            sinkCalls += "setCursorColumn:$col"
        }

        override fun setCursorRow(row: Int) {
            sinkCalls += "setCursorRow:$row"
        }

        override fun setCursorAbsolute(
            row: Int,
            col: Int,
        ) {
            sinkCalls += "setCursorAbsolute:$row:$col"
        }

        override fun setScrollRegion(
            top: Int,
            bottom: Int,
        ) {
            sinkCalls += "setScrollRegion:$top:$bottom"
        }

        override fun setLeftRightMargins(
            left: Int,
            right: Int,
        ) {
            sinkCalls += "setLeftRightMargins:$left:$right"
        }

        override fun eraseInDisplay(
            mode: Int,
            selective: Boolean,
        ) {
            sinkCalls += "eraseInDisplay:$mode:$selective"
        }

        override fun eraseInLine(
            mode: Int,
            selective: Boolean,
        ) {
            sinkCalls += "eraseInLine:$mode:$selective"
        }

        override fun insertLines(n: Int) {
            sinkCalls += "insertLines:$n"
        }

        override fun deleteLines(n: Int) {
            sinkCalls += "deleteLines:$n"
        }

        override fun insertCharacters(n: Int) {
            sinkCalls += "insertCharacters:$n"
        }

        override fun deleteCharacters(n: Int) {
            sinkCalls += "deleteCharacters:$n"
        }

        override fun eraseCharacters(n: Int) {
            sinkCalls += "eraseCharacters:$n"
        }

        override fun scrollUp(n: Int) {
            sinkCalls += "scrollUp:$n"
        }

        override fun scrollDown(n: Int) {
            sinkCalls += "scrollDown:$n"
        }

        override fun setTabStop() {
            sinkCalls += "setTabStop"
        }

        override fun clearTabStop() {
            sinkCalls += "clearTabStop"
        }

        override fun clearAllTabStops() {
            sinkCalls += "clearAllTabStops"
        }

        override fun setAnsiMode(
            mode: Int,
            enable: Boolean,
        ) {
            sinkCalls += "setAnsiMode:$mode:$enable"
        }

        override fun setDecMode(
            mode: Int,
            enable: Boolean,
        ) {
            sinkCalls += "setDecMode:$mode:$enable"
        }

        override fun setKeyModifierOption(
            resource: Int,
            value: Int,
        ) {
            sinkCalls += "setKeyModifierOption:$resource:$value"
        }

        override fun resetKeyModifierOption(resource: Int) {
            sinkCalls += "resetKeyModifierOption:$resource"
        }

        override fun resetKeyModifierOptions() {
            sinkCalls += "resetKeyModifierOptions"
        }

        override fun setKeyFormatOption(
            resource: Int,
            value: Int,
        ) {
            sinkCalls += "setKeyFormatOption:$resource:$value"
        }

        override fun resetKeyFormatOption(resource: Int) {
            sinkCalls += "resetKeyFormatOption:$resource"
        }

        override fun resetKeyFormatOptions() {
            sinkCalls += "resetKeyFormatOptions"
        }

        override fun requestDeviceStatusReport(
            mode: Int,
            decPrivate: Boolean,
        ) {
            sinkCalls += "requestDeviceStatusReport:$mode:$decPrivate"
        }

        override fun requestDeviceAttributes(
            kind: Int,
            parameter: Int,
        ) {
            sinkCalls += "requestDeviceAttributes:$kind:$parameter"
        }

        override fun requestWindowReport(mode: Int) {
            sinkCalls += "requestWindowReport:$mode"
        }

        override fun pushTitleStack(scope: Int) {
            sinkCalls += "pushTitleStack:$scope"
        }

        override fun popTitleStack(scope: Int) {
            sinkCalls += "popTitleStack:$scope"
        }

        override fun resetAttributes() {
            sinkCalls += "resetAttributes"
        }

        override fun setBold(enabled: Boolean) {
            sinkCalls += "setBold:$enabled"
        }

        override fun setFaint(enabled: Boolean) {
            sinkCalls += "setFaint:$enabled"
        }

        override fun setItalic(enabled: Boolean) {
            sinkCalls += "setItalic:$enabled"
        }

        override fun setUnderlineStyle(style: Int) {
            sinkCalls += "setUnderlineStyle:$style"
        }

        override fun setBlink(enabled: Boolean) {
            sinkCalls += "setBlink:$enabled"
        }

        override fun setInverse(enabled: Boolean) {
            sinkCalls += "setInverse:$enabled"
        }

        override fun setConceal(enabled: Boolean) {
            sinkCalls += "setConceal:$enabled"
        }

        override fun setStrikethrough(enabled: Boolean) {
            sinkCalls += "setStrikethrough:$enabled"
        }

        override fun setOverline(enabled: Boolean) {
            sinkCalls += "setOverline:$enabled"
        }

        override fun setSelectiveEraseProtection(enabled: Boolean) {
            sinkCalls += "setSelectiveEraseProtection:$enabled"
        }

        override fun setForegroundDefault() {
            sinkCalls += "setForegroundDefault"
        }

        override fun setBackgroundDefault() {
            sinkCalls += "setBackgroundDefault"
        }

        override fun setUnderlineColorDefault() {
            sinkCalls += "setUnderlineColorDefault"
        }

        override fun setForegroundIndexed(index: Int) {
            sinkCalls += "setForegroundIndexed:$index"
        }

        override fun setBackgroundIndexed(index: Int) {
            sinkCalls += "setBackgroundIndexed:$index"
        }

        override fun setUnderlineColorIndexed(index: Int) {
            sinkCalls += "setUnderlineColorIndexed:$index"
        }

        override fun setForegroundRgb(
            red: Int,
            green: Int,
            blue: Int,
        ) {
            sinkCalls += "setForegroundRgb:$red:$green:$blue"
        }

        override fun setBackgroundRgb(
            red: Int,
            green: Int,
            blue: Int,
        ) {
            sinkCalls += "setBackgroundRgb:$red:$green:$blue"
        }

        override fun setUnderlineColorRgb(
            red: Int,
            green: Int,
            blue: Int,
        ) {
            sinkCalls += "setUnderlineColorRgb:$red:$green:$blue"
        }

        override fun setWindowTitle(title: String) {
            sinkCalls += "setWindowTitle:$title"
        }

        override fun setIconTitle(title: String) {
            sinkCalls += "setIconTitle:$title"
        }

        override fun setIconAndWindowTitle(title: String) {
            sinkCalls += "setIconAndWindowTitle:$title"
        }

        override fun startHyperlink(
            uri: String,
            id: String?,
        ) {
            sinkCalls += "startHyperlink:$uri:${id ?: "null"}"
        }

        override fun endHyperlink() {
            sinkCalls += "endHyperlink"
        }
    }

    private fun dirtySequenceState(state: ParserState) {
        state.params[0] = 12
        state.params[1] = -1
        state.paramCount = 2
        state.currentParamStarted = true
        state.subParameterMask = 0b10
        state.intermediates = 0x21_20
        state.intermediateCount = 2
        state.privateMarker = '?'.code
    }

    private fun dirtyPayloadState(state: ParserState) {
        state.payloadBuffer[0] = '0'.code.toByte()
        state.payloadBuffer[1] = ';'.code.toByte()
        state.payloadBuffer[2] = 't'.code.toByte()
        state.payloadLength = 3
        state.payloadCode = 0
        state.payloadOverflowed = true
    }

    private fun execute(
        fixture: Fixture,
        state: ParserState,
        nextState: Int,
        action: Int,
        byteValue: Int,
    ) {
        fixture.engine.execute(
            state = state,
            nextState = nextState,
            action = action,
            byteValue = byteValue,
        )
    }

    private fun runMatrixTrace(
        fixture: Fixture,
        state: ParserState,
        vararg byteValues: Int,
    ) {
        for (byteValue in byteValues) {
            val previousState = state.fsmState
            val byteClass = ByteClass.classify(byteValue)
            val transition = AnsiStateMachine.transition(previousState, byteClass)

            fixture.engine.execute(
                state = state,
                nextState = AnsiStateMachine.nextState(transition),
                action = AnsiStateMachine.action(transition),
                byteValue = byteValue,
            )
        }
    }

    // ----- Validation -------------------------------------------------------

    @Nested
    @DisplayName("input validation")
    inner class InputValidation {
        @Test
        fun `rejects byte values outside unsigned byte range`() {
            val fixture = Fixture()
            val state = ParserState()

            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    execute(fixture, state, nextState = AnsiState.GROUND, action = FsmAction.IGNORE, byteValue = -1)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    execute(fixture, state, nextState = AnsiState.GROUND, action = FsmAction.IGNORE, byteValue = 256)
                }

            assertAll(
                { assertEquals("byteValue out of range: -1", below.message) },
                { assertEquals("byteValue out of range: 256", above.message) },
                { assertTrue(fixture.events.isEmpty()) },
            )
        }

        @Test
        fun `unknown action id fails loudly without mutating state`() {
            val fixture = Fixture()
            val state = ParserState()
            state.fsmState = AnsiState.CSI_PARAM

            val error =
                assertThrows(IllegalStateException::class.java) {
                    execute(fixture, state, nextState = AnsiState.GROUND, action = FsmAction.COUNT, byteValue = 0)
                }

            assertAll(
                { assertEquals("Unknown FsmAction: ${FsmAction.COUNT}", error.message) },
                { assertEquals(AnsiState.CSI_PARAM, state.fsmState) },
                { assertTrue(fixture.events.isEmpty()) },
            )
        }
    }

    // ----- Basic routing actions -------------------------------------------

    @Nested
    @DisplayName("basic routing actions")
    inner class BasicRoutingActions {
        @Test
        fun `IGNORE only updates FSM state`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)

            execute(fixture, state, nextState = AnsiState.CSI_IGNORE, action = FsmAction.IGNORE, byteValue = 'x'.code)

            assertAll(
                { assertEquals(AnsiState.CSI_IGNORE, state.fsmState) },
                { assertEquals(2, state.paramCount) },
                { assertEquals(0, fixture.printable.flushCount) },
                { assertTrue(fixture.events.isEmpty()) },
            )
        }

        @Test
        fun `CLEAR_SEQUENCE flushes printable output before clearing sequence state`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.ESCAPE,
                action = FsmAction.CLEAR_SEQUENCE,
                byteValue = 0x1B,
            )

            assertAll(
                { assertEquals(listOf("flush"), fixture.events) },
                { assertEquals(AnsiState.ESCAPE, state.fsmState) },
                { assertEquals(0, state.paramCount) },
                { assertFalse(state.currentParamStarted) },
                { assertEquals(0, state.subParameterMask) },
                { assertEquals(0, state.intermediates) },
                { assertEquals(0, state.intermediateCount) },
                { assertEquals(0, state.privateMarker) },
                { assertEquals(3, state.payloadLength, "payload is not sequence state") },
            )
        }

        @Test
        fun `PRINT_ASCII updates state before forwarding byte and does not flush`() {
            val fixture = Fixture()
            val state = ParserState()

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.PRINT_ASCII,
                byteValue = 'A'.code,
            )

            assertAll(
                { assertEquals(listOf('A'.code), fixture.printable.asciiBytes) },
                { assertEquals(listOf(AnsiState.GROUND), fixture.printable.asciiStates) },
                { assertEquals(0, fixture.printable.flushCount) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `PRINT_UTF8 updates state before forwarding raw byte and does not flush`() {
            val fixture = Fixture()
            val state = ParserState()

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.PRINT_UTF8,
                byteValue = 0xE2,
            )

            assertAll(
                { assertEquals(listOf(0xE2), fixture.printable.utf8Bytes) },
                { assertEquals(listOf(AnsiState.GROUND), fixture.printable.utf8States) },
                { assertEquals(0, fixture.printable.flushCount) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }
    }

    // ----- C0 controls ------------------------------------------------------

    @Nested
    @DisplayName("C0 controls")
    inner class C0Controls {
        @Test
        fun `EXECUTE flushes then delegates control without clearing sequence state`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.EXECUTE,
                byteValue = 0x08,
            )

            assertAll(
                { assertEquals(listOf("flush", "control:8"), fixture.events) },
                { assertEquals(listOf(0x08), fixture.dispatcher.controlBytes) },
                { assertEquals(2, state.paramCount) },
                { assertEquals(AnsiState.CSI_PARAM, state.fsmState) },
            )
        }

        @Test
        fun `EXECUTE_AND_CLEAR flushes delegates and clears sequence state`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.EXECUTE_AND_CLEAR,
                byteValue = 0x18,
            )

            assertAll(
                { assertEquals(listOf("flush", "control:24"), fixture.events) },
                { assertEquals(listOf(0x18), fixture.dispatcher.controlBytes) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.privateMarker) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `OSC BEL terminates OSC through OSC_EXECUTE_CONTROL`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.OSC_STRING,
                action = FsmAction.OSC_EXECUTE_CONTROL,
                byteValue = 0x07,
            )

            assertAll(
                { assertTrue(fixture.events.isEmpty()) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }
    }

    // ----- Sequence accumulation -------------------------------------------

    @Nested
    @DisplayName("sequence accumulation")
    inner class SequenceAccumulation {
        @Test
        fun `COLLECT_INTERMEDIATE packs up to four bytes low to high`() {
            val fixture = Fixture()
            val state = ParserState()

            for (byteValue in listOf(0x20, 0x21, 0x22, 0x23, 0x24)) {
                execute(
                    fixture,
                    state,
                    nextState = AnsiState.ESCAPE_INTERMEDIATE,
                    action = FsmAction.COLLECT_INTERMEDIATE,
                    byteValue = byteValue,
                )
            }

            assertAll(
                { assertEquals(4, state.intermediateCount) },
                { assertEquals(0x23_22_21_20, state.intermediates) },
                { assertEquals(5, fixture.printable.flushCount, "each structural byte flushes pending printables") },
                { assertEquals(AnsiState.ESCAPE_INTERMEDIATE, state.fsmState) },
            )
        }

        @Test
        fun `PARAM_DIGIT creates and appends decimal parameter values`() {
            val fixture = Fixture()
            val state = ParserState()

            for (byteValue in listOf('1'.code, '2'.code, '3'.code)) {
                execute(
                    fixture,
                    state,
                    nextState = AnsiState.CSI_PARAM,
                    action = FsmAction.PARAM_DIGIT,
                    byteValue = byteValue,
                )
            }

            assertAll(
                { assertEquals(1, state.paramCount) },
                { assertTrue(state.currentParamStarted) },
                { assertEquals(123, state.params[0]) },
                { assertEquals(3, fixture.printable.flushCount) },
                { assertEquals(AnsiState.CSI_PARAM, state.fsmState) },
            )
        }

        @Test
        fun `PARAM_DIGIT replaces an omitted parameter sentinel`() {
            val fixture = Fixture()
            val state = ParserState()
            state.params[0] = -1
            state.paramCount = 1
            state.currentParamStarted = true

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_DIGIT,
                byteValue = '7'.code,
            )

            assertEquals(7, state.params[0])
        }

        @Test
        fun `PARAM_DIGIT saturates very large parameters`() {
            val fixture = Fixture()
            val state = ParserState()
            state.params[0] = 214_748_364
            state.paramCount = 1
            state.currentParamStarted = true

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_DIGIT,
                byteValue = '9'.code,
            )

            assertEquals(Int.MAX_VALUE, state.params[0])
        }

        @Test
        fun `PARAM_DIGIT rejects non-digit bytes after flushing`() {
            val fixture = Fixture()
            val state = ParserState()

            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    execute(
                        fixture,
                        state,
                        nextState = AnsiState.CSI_PARAM,
                        action = FsmAction.PARAM_DIGIT,
                        byteValue = 'x'.code,
                    )
                }

            assertAll(
                { assertEquals("Expected decimal digit byte, got: ${'x'.code}", error.message) },
                { assertEquals(listOf("flush"), fixture.events) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(AnsiState.GROUND, state.fsmState, "state updates after successful mutation only") },
            )
        }

        @Test
        fun `PARAM_DIGIT fills the current materialized field when storage is full and field is omitted`() {
            val fixture = Fixture()
            val state = ParserState(maxParams = 1)
            state.params[0] = 5
            state.paramCount = 1
            state.currentParamStarted = false

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_DIGIT,
                byteValue = '9'.code,
            )

            assertAll(
                { assertEquals(1, state.paramCount) },
                { assertEquals(9, state.params[0]) },
                { assertTrue(state.currentParamStarted) },
                { assertEquals(AnsiState.CSI_PARAM, state.fsmState) },
            )
        }

        @Test
        fun `PARAM_SEPARATOR materializes omitted fields and opens the next slot`() {
            val fixture = Fixture()
            val state = ParserState(maxParams = 4)

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_SEPARATOR,
                byteValue = ';'.code,
            )

            assertAll(
                { assertEquals(2, state.paramCount) },
                { assertEquals(-1, state.params[0]) },
                { assertFalse(state.currentParamStarted) },
                { assertEquals(AnsiState.CSI_PARAM, state.fsmState) },
            )
        }

        @Test
        fun `PARAM_SEPARATOR after active field opens an omitted next slot lazily`() {
            val fixture = Fixture()
            val state = ParserState(maxParams = 4)
            state.params[0] = 12
            state.paramCount = 1
            state.currentParamStarted = true

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_SEPARATOR,
                byteValue = ';'.code,
            )

            assertAll(
                { assertEquals(2, state.paramCount) },
                { assertEquals(12, state.params[0]) },
                { assertFalse(state.currentParamStarted) },
            )
        }

        @Test
        fun `PARAM_SEPARATOR at capacity does not write beyond storage`() {
            val fixture = Fixture()
            val state = ParserState(maxParams = 1)
            state.params[0] = 12
            state.paramCount = 1
            state.currentParamStarted = true

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_SEPARATOR,
                byteValue = ';'.code,
            )

            assertAll(
                { assertEquals(1, state.paramCount) },
                { assertEquals(12, state.params[0]) },
                { assertFalse(state.currentParamStarted) },
            )
        }

        @Test
        fun `PARAM_COLON materializes a subparameter and marks the new slot`() {
            val fixture = Fixture()
            val state = ParserState(maxParams = 4)
            state.params[0] = 38
            state.paramCount = 1
            state.currentParamStarted = true

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_COLON,
                byteValue = ':'.code,
            )

            assertAll(
                { assertEquals(2, state.paramCount) },
                { assertEquals(38, state.params[0]) },
                { assertEquals(0b10, state.subParameterMask) },
                { assertFalse(state.currentParamStarted) },
            )
        }

        @Test
        fun `PARAM_COLON does not mark a subparameter bit for a slot that cannot be stored`() {
            val fixture = Fixture()
            val state = ParserState(maxParams = 1)
            state.params[0] = 38
            state.paramCount = 1
            state.currentParamStarted = true

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.PARAM_COLON,
                byteValue = ':'.code,
            )

            assertAll(
                { assertEquals(1, state.paramCount) },
                { assertEquals(38, state.params[0]) },
                { assertEquals(0, state.subParameterMask, "no valid params[1] exists, so bit 1 must remain clear") },
                { assertFalse(state.currentParamStarted) },
            )
        }

        @Test
        fun `SET_PRIVATE_MARKER records only the first marker`() {
            val fixture = Fixture()
            val state = ParserState()

            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.SET_PRIVATE_MARKER,
                byteValue = '?'.code,
            )
            execute(
                fixture,
                state,
                nextState = AnsiState.CSI_PARAM,
                action = FsmAction.SET_PRIVATE_MARKER,
                byteValue = '>'.code,
            )

            assertAll(
                { assertEquals('?'.code, state.privateMarker) },
                { assertEquals(2, fixture.printable.flushCount) },
                { assertEquals(AnsiState.CSI_PARAM, state.fsmState) },
            )
        }
    }

    // ----- Dispatch ---------------------------------------------------------

    @Nested
    @DisplayName("dispatch")
    inner class Dispatch {
        @Test
        fun `ESC_DISPATCH flushes before dispatch and clears sequence after dispatcher sees it`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.ESC_DISPATCH,
                byteValue = 'c'.code,
            )

            val snapshot = fixture.dispatcher.escSnapshots.single()
            assertAll(
                { assertEquals(listOf("flush", "esc:${'c'.code}"), fixture.events) },
                { assertEquals(listOf('c'.code), fixture.dispatcher.escFinals) },
                { assertEquals(2, snapshot.paramCount) },
                { assertEquals(0x21_20, snapshot.intermediates) },
                { assertEquals('?'.code, snapshot.privateMarker) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.privateMarker) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `CSI_DISPATCH flushes before dispatch and clears sequence after dispatcher sees it`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.CSI_DISPATCH,
                byteValue = 'm'.code,
            )

            val snapshot = fixture.dispatcher.csiSnapshots.single()
            assertAll(
                { assertEquals(listOf("flush", "csi:${'m'.code}"), fixture.events) },
                { assertEquals(listOf('m'.code), fixture.dispatcher.csiFinals) },
                { assertEquals(2, snapshot.paramCount) },
                { assertEquals(listOf(12, -1), snapshot.params) },
                { assertEquals(0b10, snapshot.subParameterMask) },
                { assertEquals('?'.code, snapshot.privateMarker) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.subParameterMask) },
                { assertEquals(0, state.privateMarker) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `ESC_DISPATCH stays a plain ESC dispatch even if previous state was a string`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.ESC_DISPATCH,
                byteValue = '\\'.code,
            )

            assertAll(
                { assertEquals(listOf("flush", "esc:${'\\'.code}"), fixture.events) },
                { assertEquals(listOf('\\'.code), fixture.dispatcher.escFinals) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertEquals(3, state.payloadLength, "string termination is matrix-driven, not ESC_DISPATCH-driven") },
                { assertEquals(0, state.paramCount) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `ESC backslash outside string context remains ordinary ESC dispatch`() {
            val fixture = Fixture()
            val state = ParserState()

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.ESC_DISPATCH,
                byteValue = '\\'.code,
            )

            assertAll(
                { assertEquals(listOf("flush", "esc:${'\\'.code}"), fixture.events) },
                { assertEquals(listOf('\\'.code), fixture.dispatcher.escFinals) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }
    }

    // ----- Payload ----------------------------------------------------------

    @Nested
    @DisplayName("payload actions")
    inner class PayloadActions {
        @Test
        fun `OSC_START flushes and clears sequence and stale payload state`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.OSC_STRING,
                action = FsmAction.OSC_START,
                byteValue = ']'.code,
            )

            assertAll(
                { assertEquals(listOf("flush"), fixture.events) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(AnsiState.OSC_STRING, state.fsmState) },
            )
        }

        @Test
        fun `OSC payload appends ASCII and UTF-8 bytes without flushing`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            execute(
                fixture,
                state,
                nextState = AnsiState.OSC_STRING,
                action = FsmAction.OSC_PUT_ASCII,
                byteValue = 'A'.code,
            )
            execute(
                fixture,
                state,
                nextState = AnsiState.OSC_STRING,
                action = FsmAction.OSC_PUT_UTF8,
                byteValue = 0xE2,
            )

            assertAll(
                { assertEquals(0, fixture.printable.flushCount) },
                { assertEquals(2, state.payloadLength) },
                { assertEquals('A'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals(0xE2.toByte(), state.payloadBuffer[1]) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(AnsiState.OSC_STRING, state.fsmState) },
            )
        }

        @Test
        fun `payload append marks overflow and drops bytes after capacity`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 2)

            for (byteValue in listOf('A'.code, 'B'.code, 'C'.code, 'D'.code)) {
                execute(
                    fixture,
                    state,
                    nextState = AnsiState.OSC_STRING,
                    action = FsmAction.OSC_PUT_ASCII,
                    byteValue = byteValue,
                )
            }

            assertAll(
                { assertEquals(2, state.payloadLength) },
                { assertTrue(state.payloadOverflowed) },
                { assertEquals('A'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals('B'.code.toByte(), state.payloadBuffer[1]) },
            )
        }

        @Test
        fun `DCS_IGNORE_START flushes clears stale payload and stores the first byte`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.DCS_PASSTHROUGH,
                action = FsmAction.DCS_IGNORE_START,
                byteValue = 'q'.code,
            )

            assertAll(
                { assertEquals(listOf("flush"), fixture.events) },
                { assertEquals(1, state.payloadLength) },
                { assertEquals('q'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
            )
        }

        @Test
        fun `DCS payload appends ASCII and UTF-8 bytes without flushing`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            execute(
                fixture,
                state,
                nextState = AnsiState.DCS_PASSTHROUGH,
                action = FsmAction.DCS_PUT_ASCII,
                byteValue = 'A'.code,
            )
            execute(
                fixture,
                state,
                nextState = AnsiState.DCS_PASSTHROUGH,
                action = FsmAction.DCS_PUT_UTF8,
                byteValue = 0xF0,
            )

            assertAll(
                { assertEquals(0, fixture.printable.flushCount) },
                { assertEquals(2, state.payloadLength) },
                { assertEquals('A'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals(0xF0.toByte(), state.payloadBuffer[1]) },
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
            )
        }

        @Test
        fun `OSC_END dispatches bounded payload and clears parser-owned string state`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.OSC_END,
                byteValue = '\\'.code,
            )

            assertAll(
                { assertTrue(fixture.events.isEmpty()) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.intermediateCount) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `DCS_END clears bounded passthrough payload without dispatching`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.DCS_END,
                byteValue = '\\'.code,
            )

            assertAll(
                { assertTrue(fixture.events.isEmpty()) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.intermediateCount) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `STRING_END clears ignored string state without dispatching`() {
            val fixture = Fixture()
            val state = ParserState()
            dirtySequenceState(state)
            dirtyPayloadState(state)

            execute(
                fixture,
                state,
                nextState = AnsiState.GROUND,
                action = FsmAction.STRING_END,
                byteValue = '\\'.code,
            )

            assertAll(
                { assertTrue(fixture.events.isEmpty()) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.intermediateCount) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }
    }

    // ----- Integrated matrix/action traces ---------------------------------

    @Nested
    @DisplayName("integrated matrix and action traces")
    inner class IntegratedMatrixAndActionTraces {
        @Test
        fun `CSI trace accumulates params and dispatches with sequence intact`() {
            val fixture = Fixture()
            val state = ParserState()

            runMatrixTrace(fixture, state, 0x1B, '['.code, '?'.code, '2'.code, '5'.code, 'h'.code)

            val snapshot = fixture.dispatcher.csiSnapshots.single()
            assertAll(
                { assertEquals(listOf("flush", "flush", "flush", "flush", "flush", "flush", "csi:${'h'.code}"), fixture.events) },
                { assertEquals(listOf('h'.code), fixture.dispatcher.csiFinals) },
                { assertEquals('?'.code, snapshot.privateMarker) },
                { assertEquals(listOf(25), snapshot.params) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `OSC BEL through the real matrix and action engine dispatches OSC`() {
            val fixture = Fixture()
            val state = ParserState()

            runMatrixTrace(fixture, state, 0x1B, ']'.code, '0'.code, ';'.code, 't'.code, 0x07)

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:t"), fixture.sink.sinkCalls) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `OSC ST through the real matrix and action engine dispatches OSC`() {
            val fixture = Fixture()
            val state = ParserState()

            runMatrixTrace(fixture, state, 0x1B, ']'.code, '0'.code, ';'.code, 't'.code, 0x1B, '\\'.code)

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:t"), fixture.sink.sinkCalls) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(0, state.payloadLength) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `OSC ESC followed by non-ST resumes OSC payload through the real matrix and action engine`() {
            val fixture = Fixture()
            val state = ParserState()

            runMatrixTrace(fixture, state, 0x1B, ']'.code, 'a'.code, 0x1B, 'b'.code, 0x07)

            assertAll(
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `repeated ESC inside OSC keeps waiting for ST without adding ESC bytes to payload`() {
            val fixture = Fixture()
            val state = ParserState()

            runMatrixTrace(fixture, state, 0x1B, ']'.code, 'a'.code, 0x1B, 0x1B, 'b'.code, 0x07)

            assertAll(
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `ordinary C0 inside OSC is ignored through the real matrix and action engine`() {
            val fixture = Fixture()
            val state = ParserState()

            runMatrixTrace(fixture, state, 0x1B, ']'.code, 0x00)

            assertAll(
                { assertEquals(AnsiState.OSC_STRING, state.fsmState) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
                { assertEquals(0, state.payloadLength) },
            )
        }

        @Test
        fun `ordinary C0 in DCS entry is ignored through the real matrix and action engine`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            runMatrixTrace(fixture, state, 0x1B, 'P'.code, 0x00)

            assertAll(
                { assertEquals(AnsiState.DCS_ENTRY, state.fsmState) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertEquals(0, state.payloadLength) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
            )
        }

        @Test
        fun `ordinary C0 inside DCS passthrough is retained as payload through the real matrix and action engine`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            runMatrixTrace(fixture, state, 0x1B, 'P'.code, 'q'.code, 0x00)

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertEquals(2, state.payloadLength) },
                { assertEquals('q'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals(0x00.toByte(), state.payloadBuffer[1]) },
            )
        }

        @Test
        fun `DCS ESC followed by non-ST resumes passthrough payload through the real matrix and action engine`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            runMatrixTrace(fixture, state, 0x1B, 'P'.code, 'q'.code, 0x1B, 'A'.code)

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertTrue(fixture.dispatcher.controlBytes.isEmpty()) },
                { assertEquals(2, state.payloadLength) },
                { assertEquals('q'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals('A'.code.toByte(), state.payloadBuffer[1]) },
            )
        }

        @Test
        fun `repeated ESC inside DCS keeps waiting for ST without adding ESC bytes to payload`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            runMatrixTrace(fixture, state, 0x1B, 'P'.code, 'q'.code, 0x1B, 0x1B, 'A'.code)

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(2, state.payloadLength) },
                { assertEquals('q'.code.toByte(), state.payloadBuffer[0]) },
                { assertEquals('A'.code.toByte(), state.payloadBuffer[1]) },
            )
        }

        @Test
        fun `DCS ST through the real matrix and action engine clears passthrough payload without ESC dispatch`() {
            val fixture = Fixture()
            val state = ParserState(maxPayload = 4)

            runMatrixTrace(fixture, state, 0x1B, 'P'.code, 'q'.code, 0x1B, '\\'.code)

            assertAll(
                { assertEquals(AnsiState.GROUND, state.fsmState) },
                { assertTrue(fixture.dispatcher.escFinals.isEmpty()) },
                { assertEquals(0, state.payloadLength) },
                { assertTrue(fixture.sink.sinkCalls.isEmpty()) },
            )
        }
    }
}
