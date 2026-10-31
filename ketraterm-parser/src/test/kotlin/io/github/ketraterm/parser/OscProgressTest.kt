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
import io.github.ketraterm.parser.fixture.TerminalParserFixture
import io.github.ketraterm.parser.spi.TerminalCommandSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OscProgressTest {
    @Test
    fun `omitted or empty numbers default to zero and ignored percentages are normalized`() {
        val cases =
            listOf(
                "9;4" to "HIDDEN:0",
                "9;4;" to "HIDDEN:0",
                "9;4;;" to "HIDDEN:0",
                "9;4;0;100" to "HIDDEN:0",
                "9;4;1" to "NORMAL:0",
                "9;4;1;" to "NORMAL:0",
                "9;4;2;0" to "ERROR:0",
                "9;4;3;100" to "INDETERMINATE:0",
                "9;4;4;0" to "WARNING:0",
                "9;4;01;000100" to "NORMAL:100",
            )
        for ((payload, expected) in cases) {
            val f = TerminalParserFixture()
            f.acceptAscii("\u001B]$payload\u0007")
            assertEquals(listOf("setProgress:$expected"), f.sink.events, payload)
        }
    }

    @Test
    fun `malformed state value or extra fields reject the entire command and recover`() {
        val payloads =
            listOf(
                "4;5;50",
                "4;-1;50",
                "4;+1;50",
                "4; 1;50",
                "4;1 ;50",
                "4;1:2;50",
                "4;999999999999999999999;50",
                "4;1;-1",
                "4;1;+1",
                "4;1; 50",
                "4;1;50 ",
                "4;1;101",
                "4;1;999999999999999999999",
                "4;1;50;",
                "4;1;50;extra",
                "4;;;",
                "4;0;101",
                "4;3;101",
                "4;1;\u00E9",
                "4;\uFF11;50",
            )
        for (payload in payloads) {
            for (terminator in listOf("\u0007", "\u001B\\")) {
                val bytes = "\u001B]9;$payload$terminator\u001B]9;4;1;75\u0007".encodeToByteArray()
                for (split in 0..bytes.size) {
                    val f = TerminalParserFixture()
                    f.parser.accept(bytes, 0, split)
                    f.parser.accept(bytes, split, bytes.size - split)
                    assertEquals(listOf("setProgress:NORMAL:75"), f.sink.events, "$payload split $split")
                }
            }
        }
    }

    @Test
    fun `progress remains ordered and chunk independent for BEL and ST terminators`() {
        val bytes = ("\u001B]9;4;1;0\u0007\u001B]9;4;3;100\u001B\\\u001B]9;4;2;100\u0007").encodeToByteArray()
        val expected = listOf("setProgress:NORMAL:0", "setProgress:INDETERMINATE:0", "setProgress:ERROR:100")
        for (split in 0..bytes.size) {
            val f = TerminalParserFixture()
            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)
            assertEquals(expected, f.sink.events, "split $split")
        }
        val bytewise = TerminalParserFixture()
        for (byte in bytes) bytewise.acceptByte(byte.toInt() and 0xff)
        assertEquals(expected, bytewise.sink.events)
    }

    @Test
    fun `cancel reset EOF invalid bytes and overflow discard progress without partial effects`() {
        for (cancel in listOf("\u0018", "\u001A")) {
            val f = TerminalParserFixture()
            f.acceptAscii("\u001B]9;4;1;50$cancel\u001B]9;4;1;75\u0007")
            assertEquals(listOf("setProgress:NORMAL:75"), f.sink.events)
        }
        for (resetParser in listOf(false, true)) {
            val f = TerminalParserFixture()
            f.acceptAscii("\u001B]9;4;1;50")
            if (resetParser) f.reset() else f.endOfInput()
            f.acceptAscii("\u001B]9;4;1;75\u0007")
            assertEquals(listOf("setProgress:NORMAL:75"), f.sink.events)
        }
        for (invalid in listOf(0x80, 0xc3, 0xff)) {
            val f = TerminalParserFixture()
            f.parser.accept("\u001B]9;4;1;".encodeToByteArray() + byteArrayOf(invalid.toByte(), 7))
            f.acceptAscii("\u001B]9;4;1;75\u0007")
            assertEquals(listOf("setProgress:NORMAL:75"), f.sink.events)
        }
        val exactLimit = TerminalParserFixture()
        exactLimit.acceptAscii("\u001B]9;4;1;" + "0".repeat(4090) + "\u0007")
        assertEquals(listOf("setProgress:NORMAL:0"), exactLimit.sink.events)
        val overflow = TerminalParserFixture()
        overflow.acceptAscii("\u001B]9;4;1;" + "0".repeat(4091) + "\u0007\u001B]9;4;1;75\u0007")
        assertEquals(listOf("setProgress:NORMAL:75"), overflow.sink.events)
    }

    @Test
    fun `legacy sinks explicitly omit progress capability without notification fallback`() {
        val recorded = RecordingTerminalCommandSink()
        val legacy = object : TerminalCommandSink by recorded {}
        val parser = TerminalParsers.create(legacy)
        parser.accept("\u001B]9;4;1;50\u0007\u001B]9;ordinary notification\u0007".encodeToByteArray())
        assertEquals(listOf("showNotification::ordinary notification:INFO"), recorded.events)
    }

    @Test
    fun `OSC 9 progress reports dispatch every documented state without notifications`() {
        val f = TerminalParserFixture()
        f.acceptAscii("\u001B]9;4;1;50\u0007\u001B]9;4;2;25\u001B\\\u001B]9;4;3;0\u0007")
        f.acceptAscii("\u001B]9;4;4;100\u0007\u001B]9;4;0;0\u0007")
        assertEquals(
            listOf(
                "setProgress:NORMAL:50",
                "setProgress:ERROR:25",
                "setProgress:INDETERMINATE:0",
                "setProgress:WARNING:100",
                "setProgress:HIDDEN:0",
            ),
            f.sink.events,
        )
    }
}
