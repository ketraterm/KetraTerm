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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RecordingCommandDispatcher")
class RecordingCommandDispatcherTest {
    private val sink = RecordingTerminalCommandSink()

    @Nested
    @DisplayName("control recording")
    inner class ControlRecording {
        @Test
        fun `records executed C0 control bytes in order`() {
            val dispatcher = RecordingCommandDispatcher()
            val state = ParserState()

            dispatcher.executeControl(sink, state, 0x07)
            dispatcher.executeControl(sink, state, 0x18)

            assertEquals(
                listOf(
                    RecordingCommandDispatcher.C0(0x07),
                    RecordingCommandDispatcher.C0(0x18),
                ),
                dispatcher.controls,
            )
        }
    }

    @Nested
    @DisplayName("ESC recording")
    inner class EscRecording {
        @Test
        fun `records ESC final byte and intermediate snapshot`() {
            val dispatcher = RecordingCommandDispatcher()
            val state = ParserState()
            state.intermediates = 0x21_20
            state.intermediateCount = 2

            dispatcher.dispatchEsc(sink, state, 'c'.code)

            assertEquals(
                RecordingCommandDispatcher.Esc(
                    finalByte = 'c'.code,
                    intermediates = 0x21_20,
                    intermediateCount = 2,
                ),
                dispatcher.esc.single(),
            )
        }
    }

    @Nested
    @DisplayName("CSI recording")
    inner class CsiRecording {
        @Test
        fun `records CSI final byte and parser accumulator snapshot`() {
            val dispatcher = RecordingCommandDispatcher()
            val state = ParserState(maxParams = 4)
            state.params[0] = 38
            state.params[1] = 2
            state.params[2] = -1
            state.params[3] = 255
            state.paramCount = 3
            state.privateMarker = '?'.code
            state.intermediates = 0x24_20
            state.intermediateCount = 2
            state.subParameterMask = 0b110

            dispatcher.dispatchCsi(sink, state, 'm'.code)

            val csi = dispatcher.csi.single()
            assertAll(
                { assertEquals('m'.code, csi.finalByte) },
                { assertEquals(listOf(38, 2, -1), csi.params) },
                { assertEquals('?'.code, csi.privateMarker) },
                { assertEquals(0x24_20, csi.intermediates) },
                { assertEquals(2, csi.intermediateCount) },
                { assertEquals(0b110, csi.subParameterMask) },
            )
        }

        @Test
        fun `copies CSI params so later state mutations do not rewrite the record`() {
            val dispatcher = RecordingCommandDispatcher()
            val state = ParserState(maxParams = 2)
            state.params[0] = 1
            state.params[1] = 2
            state.paramCount = 2

            dispatcher.dispatchCsi(sink, state, 'A'.code)
            state.params[0] = 99
            state.paramCount = 0

            assertEquals(listOf(1, 2), dispatcher.csi.single().params)
        }
    }

    @Nested
    @DisplayName("initial state")
    inner class InitialState {
        @Test
        fun `starts with no recorded dispatches`() {
            val dispatcher = RecordingCommandDispatcher()

            assertAll(
                { assertTrue(dispatcher.controls.isEmpty()) },
                { assertTrue(dispatcher.esc.isEmpty()) },
                { assertTrue(dispatcher.csi.isEmpty()) },
            )
        }
    }
}
