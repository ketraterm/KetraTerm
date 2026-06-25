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
package io.github.ketraterm.parser.text

import io.github.ketraterm.parser.ansi.PrintableActionSink
import io.github.ketraterm.parser.runtime.ParserState

/**
 * Adapter from ANSI ActionEngine printable callbacks to the real printable processor.
 */
internal class PrintableProcessorActionSink(
    private val processor: PrintableProcessor,
) : PrintableActionSink {
    override fun onAsciiByte(
        state: ParserState,
        byteValue: Int,
    ) {
        processor.acceptAsciiByte(state, byteValue)
    }

    override fun onUtf8Byte(
        state: ParserState,
        byteValue: Int,
    ) {
        error("UTF-8 payload must be decoded by TerminalParser before printable processing")
    }

    override fun flush(state: ParserState) {
        processor.flush(state)
    }
}
