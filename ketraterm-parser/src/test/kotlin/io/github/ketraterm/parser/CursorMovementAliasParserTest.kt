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
package io.github.ketraterm.parser

import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.fixture.ParserEvents.writeCodepoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CursorMovementAliasParserTest {
    @ParameterizedTest
    @CsvSource("a,cursorForward", "e,cursorDown", "j,cursorBackward", "k,cursorUp")
    fun `aliases normalize omitted zero positive and saturated counts across every split`(
        finalByte: Char,
        event: String,
    ) {
        val counts =
            mapOf(
                "" to 1,
                "0" to 1,
                "000" to 1,
                "1" to 1,
                "7" to 7,
                "0007" to 7,
                "2147483647" to Int.MAX_VALUE,
                "2147483648" to Int.MAX_VALUE,
                "9".repeat(80) to Int.MAX_VALUE,
            )
        for ((parameter, count) in counts) {
            assertEventsAtEverySplit("\u001B[$parameter$finalByte!", listOf("$event:$count", writeCodepoint('!'.code)))
        }
    }

    @ParameterizedTest
    @CsvSource("a,cursorForward", "e,cursorDown", "j,cursorBackward", "k,cursorUp")
    fun `unsupported signatures malformed parameters and field overflow recover before the next alias`(
        finalByte: Char,
        event: String,
    ) {
        val rejected = listOf("?3", ">3", "=3", "<3", "3 ", "3!", "3$", "3?", "1;".repeat(32), "1:".repeat(32))
        for (parameters in rejected) {
            assertEventsAtEverySplit(
                "\u001B[$parameters$finalByte\u001B[2$finalByte!",
                listOf("$event:2", writeCodepoint('!'.code)),
            )
        }
    }

    @ParameterizedTest
    @CsvSource("a,cursorForward", "e,cursorDown", "j,cursorBackward", "k,cursorUp")
    fun `cancelled sequences do not dispatch movement and the next alias remains usable`(
        finalByte: Char,
        event: String,
    ) {
        for (cancel in charArrayOf('\u0018', '\u001A', '\u001B')) {
            assertEventsAtEverySplit(
                "\u001B[9$cancel\u001B[2$finalByte!",
                listOf("$event:2", writeCodepoint('!'.code)),
            )
        }
    }

    @ParameterizedTest
    @CsvSource("a,cursorForward", "e,cursorDown", "j,cursorBackward", "k,cursorUp")
    fun `embedded controls and malformed UTF8 preserve alias dispatch order`(
        finalByte: Char,
        event: String,
    ) {
        assertEventsAtEverySplit(
            "\u001B[0\u0007\u007F2$finalByte!",
            listOf("bell", "$event:2", writeCodepoint('!'.code)),
        )
        val bytes = byteArrayOf(0xC2.toByte()) + "\u001B[2$finalByte!".encodeToByteArray()
        assertEventsAtEverySplit(bytes, listOf(writeCodepoint(0xFFFD), "$event:2", writeCodepoint('!'.code)))
    }

    private fun assertEventsAtEverySplit(
        input: String,
        expected: List<String>,
    ) = assertEventsAtEverySplit(input.encodeToByteArray(), expected)

    private fun assertEventsAtEverySplit(
        bytes: ByteArray,
        expected: List<String>,
    ) {
        for (split in 0..bytes.size) {
            val sink = RecordingTerminalCommandSink()
            val parser = TerminalParsers.create(sink)
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            parser.endOfInput()
            assertEquals(expected, sink.events, "split=$split input=${bytes.contentToString()}")
        }
        val sink = RecordingTerminalCommandSink()
        val parser = TerminalParsers.create(sink)
        for (offset in bytes.indices) parser.accept(bytes, offset, 1)
        parser.endOfInput()
        assertEquals(expected, sink.events, "one-byte chunks input=${bytes.contentToString()}")
    }
}
