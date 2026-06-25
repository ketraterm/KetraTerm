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
package io.github.ketraterm.parser.fixture

import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import io.github.ketraterm.parser.impl.TerminalParser
import io.github.ketraterm.parser.runtime.ParserState

internal class TerminalParserFixture(
    val sink: RecordingTerminalCommandSink = RecordingTerminalCommandSink(),
    val state: ParserState = ParserState(),
) {
    val parser = TerminalParser(sink, state)

    fun acceptAscii(text: String) {
        parser.accept(text.encodeToByteArray())
    }

    fun acceptUtf8(text: String) {
        parser.accept(text.encodeToByteArray())
    }

    fun acceptBytes(vararg byteValues: Int) {
        parser.accept(byteValues.map { it.toByte() }.toByteArray())
    }

    fun acceptByte(byteValue: Int) {
        parser.acceptByte(byteValue)
    }

    fun endOfInput() {
        parser.endOfInput()
    }

    fun reset() {
        parser.reset()
    }
}
