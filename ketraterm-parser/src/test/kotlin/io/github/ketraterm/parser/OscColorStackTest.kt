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

import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.fixture.TerminalParserFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OscColorStackTest {
    @Test
    fun `push and pop accept bare commands and empty separators across every split and terminator`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            assertEverySplit(
                (
                    "\u001B]30001$terminator\u001B]30101$terminator" +
                        "\u001B]30001;$terminator\u001B]30101;$terminator\u001B]2;ok\u0007"
                ).encodeToByteArray(),
                listOf("pushColors", "popColors", "pushColors", "popColors", "setWindowTitle:ok"),
            )
        }
    }

    @Test
    fun `arguments and malformed commands cannot mutate the color stack`() {
        for (payload in listOf(
            "30001;0",
            "30101;?",
            "30001;;",
            "30101; ",
            "30001=0",
            "30001x",
            "-30001",
            "+30101",
            "300010",
            "2147483648",
        )) {
            for (terminator in listOf("\u0007", "\u001B\\")) {
                assertEverySplit(
                    "\u001B]$payload$terminator\u001B]2;ok\u0007".encodeToByteArray(),
                    listOf("setWindowTitle:ok"),
                )
            }
        }
        for (command in listOf("30001", "30101")) {
            for (tail in listOf(byteArrayOf(0xc3.toByte()), "é".encodeToByteArray())) {
                assertEverySplit(
                    "\u001B]$command;".encodeToByteArray() + tail + "\u0007\u001B]2;ok\u0007".encodeToByteArray(),
                    listOf("setWindowTitle:ok"),
                )
            }
        }
    }

    @Test
    fun `collection ceiling rejects overlong command prefixes and drains oversized arguments`() {
        for (size in intArrayOf(5, 6, 7)) {
            for (terminator in listOf("\u0007", "\u001B\\")) {
                assertEverySplit(
                    "\u001B]${"30001".padStart(size, '0')}$terminator\u001B]30101\u0007".encodeToByteArray(),
                    if (size <= 6) listOf("pushColors", "popColors") else listOf("popColors"),
                )
            }
        }
        for (terminator in listOf("\u0007", "\u001B\\")) {
            val fixture = TerminalParserFixture()
            fixture.acceptAscii("\u001B]30001;${"x".repeat(4096)}$terminator\u001B]30101\u0007")
            assertEquals(listOf("popColors"), fixture.sink.events)
        }
    }

    @Test
    fun `cancellation and end of input discard incomplete stack commands and recover`() {
        for (command in listOf("30001", "30101", "30001;", "30101;")) {
            for (cancel in listOf("\u0018", "\u001A")) {
                assertEverySplit(
                    "\u001B]$command$cancel\u001B]2;ok\u0007".encodeToByteArray(),
                    listOf("setWindowTitle:ok"),
                )
            }
            for (suffix in listOf("", "\u001B")) {
                val fixture = TerminalParserFixture()
                fixture.acceptAscii("\u001B]$command$suffix")
                fixture.endOfInput()
                assertEquals(emptyList<String>(), fixture.sink.events)
                fixture.acceptAscii("\u001B]30101\u0007")
                assertEquals(listOf("popColors"), fixture.sink.events)
            }
        }
    }

    @Test
    fun `built in color stack commands never reach the custom OSC handler`() {
        val fixture = TerminalParserFixture()
        val parser =
            TerminalParsers.create(fixture.sink, { 0 }) { command, _, _, _ ->
                fixture.sink.events += "custom:$command"
            }
        parser.accept(
            (
                "\u001B]30001\u0007\u001B]30101;\u0007\u001B]30001;ignored\u0007" +
                    "\u001B]30101;?\u0007\u001B]1341;ok\u0007"
            ).encodeToByteArray(),
        )
        assertEquals(listOf("pushColors", "popColors", "custom:1341"), fixture.sink.events)
    }

    private fun assertEverySplit(
        bytes: ByteArray,
        expected: List<String>,
    ) {
        for (split in 0..bytes.size) {
            val fixture = TerminalParserFixture()
            fixture.parser.accept(bytes, 0, split)
            fixture.parser.accept(bytes, split, bytes.size - split)
            assertEquals(expected, fixture.sink.events, "split=$split")
        }
    }
}
