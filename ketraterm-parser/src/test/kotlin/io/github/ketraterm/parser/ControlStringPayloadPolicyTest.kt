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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ControlStringPayloadPolicyTest {
    @Test
    fun `limits count UTF8 bytes and ignore only controls excluded by string handling`() {
        val f = TerminalParserFixture()
        val title = "é".repeat(2047)
        val bytes = "\u001B]2;$title\u0007".encodeToByteArray()
        for (byte in bytes) f.acceptByte(byte.toInt() and 0xff)
        assertEquals(listOf("setWindowTitle:$title"), f.sink.events)
        f.acceptUtf8("\u001B]2;${title}é\u0007")
        assertEquals(listOf("setWindowTitle:$title"), f.sink.events)
        val controls = "\u0001\u007F".repeat(300)
        f.acceptAscii("\u001B]10;$controls?\u0007")
        assertEquals(listOf("setWindowTitle:$title", "queryDynamicColor:10"), f.sink.events)
        // Unlike OSC, DCS retains ordinary C0 bytes, including BEL, in its budget.
        f.acceptAscii("\u001BP\$q" + "\u0007".repeat(63) + "\u001B\\")
        assertEquals(listOf("setWindowTitle:$title", "queryDynamicColor:10"), f.sink.events)
    }

    @Test
    fun `metadata families retain exact 4096 byte boundaries and never dispatch truncated prefixes`() {
        for (size in intArrayOf(4095, 4096, 4097)) {
            val cases =
                listOf(
                    "0;" to "setIconAndWindowTitle:",
                    "1;" to "setIconTitle:",
                    "2;" to "setWindowTitle:",
                    "7;" to "setCurrentWorkingDirectoryUri:",
                    "8;;" to "startHyperlink:",
                    "9;" to "showNotification::",
                    "52;c;" to "requestClipboard:c:",
                    "777;notify;t;" to "showNotification:t:",
                )
            for ((prefix, event) in cases) {
                val body = "x".repeat(size - prefix.length)
                val suffix =
                    when {
                        prefix == "8;;" -> ":null"
                        prefix == "9;" || prefix.startsWith("777;") -> ":INFO"
                        else -> ""
                    }
                for (terminator in listOf("\u0007", "\u001B\\")) {
                    val f = TerminalParserFixture()
                    f.acceptAscii("\u001B]$prefix$body$terminator\u001B]2;ok\u0007")
                    val expected =
                        when {
                            size <= 4096 -> listOf(event + body + suffix, "setWindowTitle:ok")
                            prefix == "8;;" -> listOf("endHyperlink", "setWindowTitle:ok")
                            else -> listOf("setWindowTitle:ok")
                        }
                    assertEquals(expected, f.sink.events, "prefix=$prefix bytes=$size terminator=$terminator")
                }
            }
        }
    }

    @Test
    fun `batched palette and shell markers retain the generic ceiling`() {
        for (size in intArrayOf(4095, 4096, 4097)) {
            val palette = "4;1;#123456".padEnd(size, ' ')
            val shell = "133;A;".padEnd(size, 'x')
            for ((payload, event) in listOf(
                palette to "setPaletteColor:1:-15584170",
                shell to "shellIntegrationMarker:PROMPT_START:null",
            )) {
                val f = TerminalParserFixture()
                f.acceptAscii("\u001B]$payload\u0007")
                assertEquals(if (size <= 4096) listOf(event) else emptyList<String>(), f.sink.events)
            }
        }
    }

    @Test
    fun `dynamic color ceilings apply before partial updates or query responses`() {
        for (command in 10..12) {
            for (size in intArrayOf(255, 256, 257)) {
                val payload = "$command;?".padEnd(size, ' ')
                val bytes = "\u001B]$payload\u001B\\\u001B]2;ok\u0007".encodeToByteArray()
                for (split in 0..bytes.size) {
                    val f = TerminalParserFixture()
                    f.parser.accept(bytes, 0, split)
                    f.parser.accept(bytes, split, bytes.size - split)
                    assertEquals(
                        if (size <= 256) listOf("queryDynamicColor:$command", "setWindowTitle:ok") else listOf("setWindowTitle:ok"),
                        f.sink.events,
                        "command=$command bytes=$size split=$split",
                    )
                }
            }
        }
    }

    @Test
    fun `DCS ceilings retain batches and reject oversized requests without truncated dispatch`() {
        for ((prefix, limit, event) in listOf(Triple("\$q", 64, "queryStatusString:"), Triple("+q", 4096, "queryTerminfo:"))) {
            for (size in intArrayOf(limit - 1, limit, limit + 1)) {
                val body = "x".repeat(size - 2)
                val bytes = "\u001BP$prefix$body\u001B\\\u001B]2;ok\u0007".encodeToByteArray()
                val splits = if (limit == 64) (0..bytes.size).toList() else listOf(0, 1, 2, 3, 4, limit, limit + 2, bytes.size - 1)
                for (split in splits) {
                    val f = TerminalParserFixture()
                    f.parser.accept(bytes, 0, split)
                    f.parser.accept(bytes, split, bytes.size - split)
                    assertEquals(
                        if (size <= limit) listOf(event + body, "setWindowTitle:ok") else listOf("setWindowTitle:ok"),
                        f.sink.events,
                        "prefix=$prefix bytes=$size split=$split",
                    )
                }
            }
        }
    }

    @Test
    fun `smaller scratch capacity remains a backstop at every byte split`() {
        for (payload in listOf("2;", "8;;", "52;c;", "10;?")) {
            for (size in intArrayOf(15, 16, 17)) {
                val body = "x".repeat(size - payload.length)
                val padded = payload + if (payload == "10;?") " ".repeat(body.length) else body
                val bytes = ("\u001B]" + padded + "\u001B\\\u001B]2;ok\u0007").encodeToByteArray()
                val acceptedEvent =
                    when (payload) {
                        "2;" -> "setWindowTitle:$body"
                        "8;;" -> "startHyperlink:$body:null"
                        "52;c;" -> "requestClipboard:c:$body"
                        else -> "queryDynamicColor:10"
                    }
                for (split in 0..bytes.size) {
                    val f = TerminalParserFixture(state = ParserState(maxPayload = 16))
                    f.parser.accept(bytes, 0, split)
                    f.parser.accept(bytes, split, bytes.size - split)
                    val expected =
                        when {
                            size <= 16 -> listOf(acceptedEvent, "setWindowTitle:ok")
                            payload == "8;;" -> listOf("endHyperlink", "setWindowTitle:ok")
                            else -> listOf("setWindowTitle:ok")
                        }
                    assertEquals(expected, f.sink.events, "prefix=$payload size=$size split=$split")
                }
            }
        }
    }

    @Test
    fun `ignored families stop storing after identification and cannot be reclassified`() {
        for ((prefix, retained) in listOf("\u001B]999;" to 4, "\u001B]x;" to 2, "\u001BP?q" to 2)) {
            val f = TerminalParserFixture()
            val storage = f.state.payloadBuffer
            f.acceptAscii(prefix + "2;new title+q436f".repeat(1000))
            assertEquals(retained, f.state.payloadLength)
            assertEquals(0, f.state.payloadLimit)
            assertFalse(f.state.payloadOverflowed)
            assertSame(storage, f.state.payloadBuffer)
            f.acceptAscii("\u001B\\\u001B]2;ok\u0007")
            assertEquals(listOf("setWindowTitle:ok"), f.sink.events)
        }
    }

    @Test
    fun `family overflow stops collection without resizing and long headers cannot bypass it`() {
        for ((prefix, limit) in listOf("\u001B]10;" to 256, "\u001BP\$q" to 64)) {
            val f = TerminalParserFixture()
            val storage = f.state.payloadBuffer
            f.acceptAscii(prefix + "x".repeat(10000))
            assertEquals(limit, f.state.payloadLength)
            assertTrue(f.state.payloadOverflowed)
            assertSame(storage, f.state.payloadBuffer)
        }
        val f = TerminalParserFixture()
        f.acceptAscii("\u001B]" + "0".repeat(256) + "10;?\u0007")
        assertTrue(f.sink.events.isEmpty())
        f.acceptAscii("\u001B]00010;?\u0007")
        assertEquals(listOf("queryDynamicColor:10"), f.sink.events)
    }

    @Test
    fun `larger internal storage cannot bypass absolute ceiling and malformed headers cannot select a family`() {
        val f = TerminalParserFixture(state = ParserState(maxPayload = 8192))
        f.acceptAscii("\u001B]2;" + "x".repeat(4095))
        assertEquals(4096, f.state.payloadLength)
        assertTrue(f.state.payloadOverflowed)
        f.acceptAscii("\u0007")
        for (header in listOf("2147483648", "999999999999999999999999", "", "+8", "-8", "8x")) {
            f.acceptAscii("\u001B]$header;8;;https://bad/\u0007")
        }
        assertTrue(f.sink.events.isEmpty())
        f.acceptAscii("\u001B]2;ok\u0007")
        assertEquals(listOf("setWindowTitle:ok"), f.sink.events)
    }

    @Test
    fun `cancellation EOF and reset discard incomplete payloads and restore family limits`() {
        for (prefix in listOf("\u001B]8;;", "\u001B]10;", "\u001B]999;", "\u001BP\$q", "\u001BP?q", "\u001BP")) {
            for (count in intArrayOf(0, 8, 5000)) {
                for (escape in listOf("", "\u001B")) {
                    for (ending in listOf("\u0018", "\u001A", "eof", "reset")) {
                        val f = TerminalParserFixture()
                        f.acceptAscii(prefix + "x".repeat(count) + escape)
                        when (ending) {
                            "eof" -> f.endOfInput()
                            "reset" -> f.reset()
                            else -> f.acceptAscii(ending)
                        }
                        f.acceptAscii("\u001B]2;ok\u0007")
                        assertEquals(listOf("setWindowTitle:ok"), f.sink.events, "prefix=$prefix count=$count ending=$ending")
                        assertEquals(4096, f.state.payloadLimit)
                        assertFalse(f.state.payloadHeaderComplete)
                    }
                }
            }
        }
    }
}
