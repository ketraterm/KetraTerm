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

internal class AnsiHarness(
    val state: ParserState = ParserState(),
    val sink: RecordingTerminalCommandSink = RecordingTerminalCommandSink(),
    val dispatcher: RecordingCommandDispatcher = RecordingCommandDispatcher(),
    val printable: RecordingPrintableActionSink = RecordingPrintableActionSink(),
) {
    private val engine =
        ActionEngine(
            sink = sink,
            dispatcher = dispatcher,
            printableSink = printable,
        )

    fun accept(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            acceptByte(bytes[i].toInt() and 0xff)
            i++
        }
    }

    fun acceptAscii(text: String) {
        accept(text.encodeToByteArray())
    }

    fun acceptByte(byteValue: Int) {
        require(byteValue in 0..255)

        val byteClass = ByteClass.classify(byteValue)
        val transition = AnsiStateMachine.transition(state.fsmState, byteClass)
        val nextState = AnsiStateMachine.nextState(transition)
        val action = AnsiStateMachine.action(transition)

        engine.execute(
            state = state,
            nextState = nextState,
            action = action,
            byteValue = byteValue,
        )
    }
}
