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
import io.github.ketraterm.protocol.TerminalPointerShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OscPointerShapeTest {
    @Test
    fun `built-in pointer requests never escape through a custom OSC callback`() {
        val fixture = TerminalParserFixture()
        val parser =
            TerminalParsers.create(fixture.sink, { 0 }) { command, _, _, _ ->
                fixture.sink.events += "custom:$command"
            }
        parser.accept(
            (
                "\u001B]22;wait\u0007\u001B]22;unknown\u0007\u001B]22;?__current__\u0007" +
                    "\u001B]22;" + "x".repeat(33) + "\u0007\u001B]1341;ok\u0007"
            ).encodeToByteArray(),
        )
        assertEquals(listOf("setPointerShape:WAIT", "custom:1341"), fixture.sink.events)
    }

    @Test
    fun `all portable names and empty reset survive every split and both terminators`() {
        for (shape in TerminalPointerShape.entries) {
            for (terminator in listOf("\u0007", "\u001B\\")) {
                for (prefix in listOf("", "=")) {
                    val bytes = "\u001B]22;$prefix${shape.wireName}$terminator\u001B]22;$prefix$terminator".encodeToByteArray()
                    for (split in 0..bytes.size) {
                        val fixture = TerminalParserFixture()
                        fixture.parser.accept(bytes, 0, split)
                        fixture.parser.accept(bytes, split, bytes.size - split)
                        assertEquals(
                            listOf("setPointerShape:$shape", "setPointerShape:null"),
                            fixture.sink.events,
                            "shape=$shape split=$split prefix=$prefix",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `unsupported and malformed requests never reset an existing pointer and recover`() {
        for (payload in listOf(
            "Pointer",
            " pointer",
            "pointer ",
            "pointer;",
            "pointer,text",
            ">wait",
            "<",
            "?__current__",
            "none",
            "é",
            "pointer" + "x".repeat(100),
        )) {
            val bytes = "\u001B]22;wait\u0007\u001B]22;$payload\u0007\u001B]2;ok\u0007".encodeToByteArray()
            for (split in 0..bytes.size) {
                val fixture = TerminalParserFixture()
                fixture.parser.accept(bytes, 0, split)
                fixture.parser.accept(bytes, split, bytes.size - split)
                assertEquals(listOf("setPointerShape:WAIT", "setWindowTitle:ok"), fixture.sink.events, "payload=$payload split=$split")
            }
        }
    }

    @Test
    fun `missing separator cancellation incomplete input and malformed UTF8 have no effects`() {
        for (bytes in listOf(
            "\u001B]22\u0007".encodeToByteArray(),
            "\u001B]22;pointer\u0018".encodeToByteArray(),
            "\u001B]22;pointer\u001A".encodeToByteArray(),
            "\u001B]22;".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + byteArrayOf(7),
        )) {
            val fixture = TerminalParserFixture()
            for (byte in bytes) fixture.parser.accept(byteArrayOf(byte))
            fixture.acceptAscii("\u001B]22;crosshair\u0007")
            assertEquals(listOf("setPointerShape:CROSSHAIR"), fixture.sink.events)
        }
        val fixture = TerminalParserFixture()
        fixture.acceptAscii("\u001B]22;pointer")
        fixture.endOfInput()
        assertEquals(emptyList<String>(), fixture.sink.events)
    }

    @Test
    fun `payload limit includes command prefix and never dispatches a truncated name`() {
        for (length in 31..33) {
            val fixture = TerminalParserFixture()
            val prefix = "0".repeat(length - "22;pointer".length)
            fixture.acceptAscii("\u001B]${prefix}22;pointer\u0007\u001B]2;ok\u0007")
            val expected = if (length <= 32) listOf("setPointerShape:POINTER", "setWindowTitle:ok") else listOf("setWindowTitle:ok")
            assertEquals(expected, fixture.sink.events, "length=$length")
        }
    }
}
