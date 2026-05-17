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

@DisplayName("RecordingPrintableActionSink")
class RecordingPrintableActionSinkTest {
    @Nested
    @DisplayName("printable byte recording")
    inner class PrintableByteRecording {
        @Test
        fun `records ASCII and UTF-8 bytes into separate streams in order`() {
            val printable = RecordingPrintableActionSink()
            val state = ParserState()

            printable.onAsciiByte(state, 'A'.code)
            printable.onUtf8Byte(state, 0xE2)
            printable.onAsciiByte(state, 'B'.code)
            printable.onUtf8Byte(state, 0x82)

            assertAll(
                { assertEquals(listOf('A'.code, 'B'.code), printable.asciiBytes) },
                { assertEquals(listOf(0xE2, 0x82), printable.utf8Bytes) },
                { assertEquals(0, printable.flushCount) },
            )
        }
    }

    @Nested
    @DisplayName("flush recording")
    inner class FlushRecording {
        @Test
        fun `counts every flush without clearing recorded bytes`() {
            val printable = RecordingPrintableActionSink()
            val state = ParserState()
            printable.onAsciiByte(state, 'x'.code)
            printable.onUtf8Byte(state, 0xF0)

            printable.flush(state)
            printable.flush(state)

            assertAll(
                { assertEquals(2, printable.flushCount) },
                { assertEquals(listOf('x'.code), printable.asciiBytes) },
                { assertEquals(listOf(0xF0), printable.utf8Bytes) },
            )
        }
    }

    @Nested
    @DisplayName("initial state")
    inner class InitialState {
        @Test
        fun `starts with no bytes and zero flushes`() {
            val printable = RecordingPrintableActionSink()

            assertAll(
                { assertTrue(printable.asciiBytes.isEmpty()) },
                { assertTrue(printable.utf8Bytes.isEmpty()) },
                { assertEquals(0, printable.flushCount) },
            )
        }
    }
}
