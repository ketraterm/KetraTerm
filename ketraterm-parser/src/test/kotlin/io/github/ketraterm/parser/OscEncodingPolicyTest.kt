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

import io.github.ketraterm.parser.fixture.TerminalParserFixture
import io.github.ketraterm.parser.runtime.ParserState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OscEncodingPolicyTest {
    private val malformed =
        listOf(
            listOf(0x80),
            listOf(0xFF),
            listOf(0xC0, 0xAF),
            listOf(0xC1, 0xBF),
            listOf(0xC3),
            listOf(0xE2, 0x82),
            listOf(0xF0, 0x9F, 0x98),
            listOf(0xE0, 0x80, 0x80),
            listOf(0xED, 0xA0, 0x80),
            listOf(0xF0, 0x80, 0x80, 0x80),
            listOf(0xF4, 0x90, 0x80, 0x80),
            listOf(0xF5, 0x80, 0x80, 0x80),
            listOf(0xE2, 0x41),
        )

    @Test
    fun `malformed structured commands have no partial effects at every split`() {
        val prefixes =
            listOf(
                "4;1;#123456;2;",
                "7;file:///tmp/",
                "8;id=x;https://example.com/",
                "8;id=",
                "10;#123456;",
                "11;#123456;",
                "12;#123456;",
                "133;A;",
                "133;D;0;",
                "52;c;",
                "52;",
            )
        for (prefix in prefixes) {
            for (invalid in malformed) {
                for (terminator in listOf("\u0007", "\u001B\\")) {
                    val stream =
                        ("\u001B]" + prefix).encodeToByteArray() + invalid.map(Int::toByte).toByteArray() +
                            (terminator + "\u001B]2;recovered\u0007").encodeToByteArray()
                    val expected =
                        if (prefix.startsWith("8;")) {
                            listOf("endHyperlink", "setWindowTitle:recovered")
                        } else {
                            listOf("setWindowTitle:recovered")
                        }
                    for (split in 0..stream.size) {
                        val f = TerminalParserFixture()
                        f.parser.accept(stream, 0, split)
                        f.parser.accept(stream, split, stream.size - split)
                        f.endOfInput()
                        assertEquals(expected, f.sink.events, "prefix=$prefix invalid=$invalid split=$split")
                    }
                }
            }
        }
    }

    @Test
    fun `display text replaces malformed bytes while literal replacement characters remain valid`() {
        val cases =
            listOf(
                "0;" to "setIconAndWindowTitle:",
                "1;" to "setIconTitle:",
                "2;" to "setWindowTitle:",
                "9;" to "showNotification::",
                "777;notify;t;" to "showNotification:t:",
            )
        for ((prefix, event) in cases) {
            for (terminator in listOf("\u0007", "\u001B\\")) {
                val stream = ("\u001B]${prefix}é🙂�").encodeToByteArray() + byteArrayOf(0xC3.toByte()) + terminator.encodeToByteArray()
                for (split in 0..stream.size) {
                    val f = TerminalParserFixture()
                    f.parser.accept(stream, 0, split)
                    f.parser.accept(stream, split, stream.size - split)
                    f.endOfInput()
                    val level = if (prefix.startsWith("9;") || prefix.startsWith("777;")) ":INFO" else ""
                    assertEquals(listOf(event + "é🙂��" + level), f.sink.events)
                }
            }
        }
    }

    @Test
    fun `valid structured Unicode including literal replacement survives bytewise input after rejection`() {
        val f = TerminalParserFixture()
        f.parser.accept("\u001B]7;file:///".encodeToByteArray() + byteArrayOf(0xE2.toByte()) + byteArrayOf(7))
        val stream = "\u001B]7;file:///é🙂�\u0007\u001B]8;id=é�;https://example.com/🙂�\u001B\\"
        for (byte in stream.encodeToByteArray()) f.acceptByte(byte.toInt() and 0xff)
        assertEquals(listOf("setCurrentWorkingDirectoryUri:file:///é🙂�", "startHyperlink:https://example.com/🙂�:é�"), f.sink.events)
    }

    @Test
    fun `display replacement subsequences have explicit expected output`() {
        val replacements = listOf("�", "�", "��", "��", "�", "�", "�", "���", "�", "����", "����", "����", "�A")
        for ((invalid, replacement) in malformed.zip(replacements)) {
            val f = TerminalParserFixture()
            f.parser.accept("\u001B]2;".encodeToByteArray() + invalid.map(Int::toByte).toByteArray() + byteArrayOf(7))
            assertEquals(listOf("setWindowTitle:$replacement"), f.sink.events, "bytes=$invalid")
        }
    }

    @Test
    fun `abort overflow reset and EOF discard OSC without metadata effects`() {
        for (prefix in listOf("2;", "7;", "8;;", "52;c;")) {
            for (ending in listOf("\u0018", "\u001A")) {
                val f = TerminalParserFixture()
                f.acceptAscii("\u001B]${prefix}partial$ending\u001B]2;next\u0007")
                assertEquals(listOf("setWindowTitle:next"), f.sink.events)
            }
            for (escapePending in listOf(false, true)) {
                for (reset in listOf(false, true)) {
                    val f = TerminalParserFixture()
                    f.acceptAscii("\u001B]${prefix}partial" + if (escapePending) "\u001B" else "")
                    if (reset) f.reset() else f.endOfInput()
                    f.acceptAscii("\u001B]2;next\u0007")
                    assertEquals(listOf("setWindowTitle:next"), f.sink.events)
                }
            }
            val f = TerminalParserFixture(state = ParserState(maxPayload = 16))
            f.acceptAscii("\u001B]$prefix" + "x".repeat(20) + "\u0007\u001B]2;next\u0007")
            assertEquals(listOf("setWindowTitle:next"), f.sink.events)
        }
    }
}
