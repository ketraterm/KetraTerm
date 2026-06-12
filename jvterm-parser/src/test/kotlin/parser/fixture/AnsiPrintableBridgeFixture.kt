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
package com.gagik.parser.fixture

import com.gagik.parser.ansi.*
import com.gagik.parser.runtime.ParserState
import com.gagik.parser.text.PrintableProcessor
import com.gagik.parser.text.PrintableProcessorActionSink

internal class AnsiPrintableBridgeFixture(
    val state: ParserState = ParserState(),
    val sink: RecordingTerminalCommandSink = RecordingTerminalCommandSink(),
) {
    private val processor = PrintableProcessor(sink)
    private val engine =
        ActionEngine(
            sink = sink,
            dispatcher = AnsiCommandDispatcher,
            printableSink = PrintableProcessorActionSink(processor),
        )

    fun acceptAscii(text: String) {
        for (byteValue in text.encodeToByteArray()) {
            acceptByte(byteValue.toInt() and 0xff)
        }
    }

    fun acceptByte(byteValue: Int) {
        val byteClass = ByteClass.classify(byteValue)
        val transition = AnsiStateMachine.transition(state.fsmState, byteClass)
        engine.execute(
            state = state,
            nextState = AnsiStateMachine.nextState(transition),
            action = AnsiStateMachine.action(transition),
            byteValue = byteValue,
        )
    }

    fun endOfInput() {
        processor.flush(state)
    }
}
