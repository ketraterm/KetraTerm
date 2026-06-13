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
package io.github.jvterm.parser.ansi

import io.github.jvterm.parser.runtime.ParserState

internal class RecordingPrintableActionSink : PrintableActionSink {
    val asciiBytes = ArrayList<Int>()
    val utf8Bytes = ArrayList<Int>()
    var flushCount: Int = 0

    override fun onAsciiByte(
        state: ParserState,
        byteValue: Int,
    ) {
        asciiBytes += byteValue
    }

    override fun onUtf8Byte(
        state: ParserState,
        byteValue: Int,
    ) {
        utf8Bytes += byteValue
    }

    override fun flush(state: ParserState) {
        flushCount++
    }
}
