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

import io.github.ketraterm.parser.ansi.ControlStringPolicy
import io.github.ketraterm.parser.ansi.RecordingTerminalCommandSink
import io.github.ketraterm.parser.fixture.TerminalParserFixture
import io.github.ketraterm.parser.impl.TerminalParser
import io.github.ketraterm.parser.runtime.ParserState
import io.github.ketraterm.parser.spi.TerminalCommandSink
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OscClipboardPayloadTest {
    @Test
    fun `larger writes grow only on demand and release storage at both terminators`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            var requests = 0
            val f =
                TerminalParserFixture(clipboardWriteLimitBytes = {
                    requests++
                    6144
                })
            val initial = f.state.payloadBuffer
            val body = "YWFh".repeat(2048)
            f.acceptAscii("\u001B]52;c;" + body.take(4091))
            assertSame(initial, f.state.payloadBuffer)
            assertEquals(1, requests)
            f.acceptAscii(body.drop(4091))
            assertNotSame(initial, f.state.payloadBuffer)
            assertEquals(8197, f.state.payloadLength)
            assertTrue(f.state.payloadBuffer.size <= 8197)
            assertTrue(f.sink.events.isEmpty())
            f.acceptAscii(terminator)
            assertEquals(listOf("requestClipboard:c:$body"), f.sink.events)
            assertSame(initial, f.state.payloadBuffer)
            assertEquals(-1, f.state.clipboardDataStart)
            assertEquals(4096, f.state.payloadLimit)
        }
    }

    @Test
    fun `encoded byte ceiling rejects the first excess byte and releases storage before termination`() {
        for (size in intArrayOf(8191, 8192, 8193)) {
            val f = TerminalParserFixture(clipboardWriteLimitBytes = { 6144 })
            val initial = f.state.payloadBuffer
            val body = "A".repeat(size)
            f.acceptAscii("\u001B]52;c;$body")
            if (size > 8192) {
                assertTrue(f.state.payloadOverflowed)
                assertSame(initial, f.state.payloadBuffer)
            }
            f.acceptAscii("\u0007\u001B]2;ok\u0007")
            val expected = if (size <= 8192) listOf("requestClipboard:c:$body", "setWindowTitle:ok") else listOf("setWindowTitle:ok")
            assertEquals(expected, f.sink.events)
            assertSame(initial, f.state.payloadBuffer)
        }
    }

    @Test
    fun `growth and termination survive every split with a small initial buffer`() {
        val body = "YWFh".repeat(16)
        val bytes = "\u001B]52;c;$body\u001B\\\u001B]2;ok\u0007".encodeToByteArray()
        for (split in 0..bytes.size) {
            val f = TerminalParserFixture(state = ParserState(maxPayload = 8), clipboardWriteLimitBytes = { 48 })
            val initial = f.state.payloadBuffer
            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)
            assertEquals(listOf("requestClipboard:c:$body", "setWindowTitle:ok"), f.sink.events, "split=$split")
            assertSame(initial, f.state.payloadBuffer)
        }
    }

    @Test
    fun `only bounded clipboard write headers can obtain a larger budget`() {
        var requests = 0
        val f =
            TerminalParserFixture(clipboardWriteLimitBytes = {
                requests++
                65536
            })
        val initial = f.state.payloadBuffer
        for (prefix in listOf("52;c;?", "52;c;=", "52;" + "c".repeat(4096), "2;", "7;", "8;;", "10;", "999;")) {
            f.acceptAscii("\u001B]$prefix" + "A".repeat(5000) + "\u0007")
            assertSame(initial, f.state.payloadBuffer)
        }
        f.acceptAscii("\u001B]52;c;\u0007")
        assertEquals(0, requests)
        assertEquals(listOf("endHyperlink", "requestClipboard:c:"), f.sink.events)
        // The envelope can fill the ordinary buffer exactly; growth begins only with data.
        val selection = "c".repeat(4092)
        f.acceptAscii("\u001B]52;$selection;YQ==\u0007")
        assertEquals(1, requests)
        assertEquals("requestClipboard:$selection:YQ==", f.sink.events.last())
    }

    @Test
    fun `large storage is released by cancellation reset and EOF without dispatch`() {
        for (ending in listOf("\u0018", "\u001A", "reset", "eof")) {
            for (escape in listOf("", "\u001B")) {
                val f = TerminalParserFixture(clipboardWriteLimitBytes = { 65536 })
                val initial = f.state.payloadBuffer
                f.acceptAscii("\u001B]52;c;" + "YWFh".repeat(2000) + escape)
                assertNotSame(initial, f.state.payloadBuffer)
                when (ending) {
                    "reset" -> f.reset()
                    "eof" -> f.endOfInput()
                    else -> f.acceptAscii(ending)
                }
                assertSame(initial, f.state.payloadBuffer)
                f.acceptAscii("\u001B]2;ok\u0007")
                assertEquals(listOf("setWindowTitle:ok"), f.sink.events)
            }
        }
    }

    @Test
    fun `budget is sampled once per write and never resurrects an overflowed payload`() {
        var budget = 4096
        var requests = 0
        val f =
            TerminalParserFixture(clipboardWriteLimitBytes = {
                requests++
                budget
            })
        f.acceptAscii("\u001B]52;c;AAAA")
        budget = 65536
        f.acceptAscii("A".repeat(6000))
        assertTrue(f.state.payloadOverflowed)
        assertEquals(1, requests)
        f.acceptAscii("\u0007\u001B]52;c;" + "A".repeat(6000) + "\u0007")
        assertEquals(2, requests)
        assertEquals(listOf("requestClipboard:c:" + "A".repeat(6000)), f.sink.events)
    }

    @Test
    fun `sink failure cannot retain large storage or leave OSC active`() {
        val state = ParserState()
        val initial = state.payloadBuffer
        val recorded = RecordingTerminalCommandSink()
        val sink =
            object : TerminalCommandSink by recorded {
                override fun requestClipboard(
                    selection: String,
                    encodedData: String,
                ) {
                    error("host callback failed")
                }
            }
        val parser = TerminalParser(sink, state, clipboardWriteLimitBytes = { 65536 })
        parser.accept(("\u001B]52;c;" + "A".repeat(5000)).encodeToByteArray())
        assertThrows(IllegalStateException::class.java) { parser.acceptByte(7) }
        assertSame(initial, state.payloadBuffer)
        parser.accept("\u001B]2;ok\u0007".encodeToByteArray())
        assertEquals(listOf("setWindowTitle:ok"), recorded.events)
    }

    @Test
    fun `encoded budget arithmetic is checked and saturates without integer overflow`() {
        assertEquals(5, ControlStringPolicy.clipboardLimit(5, 0))
        assertEquals(9, ControlStringPolicy.clipboardLimit(5, 1))
        assertEquals(9, ControlStringPolicy.clipboardLimit(5, 2))
        assertEquals(9, ControlStringPolicy.clipboardLimit(5, 3))
        assertEquals(13, ControlStringPolicy.clipboardLimit(5, 4))
        assertEquals(Int.MAX_VALUE - 8, ControlStringPolicy.clipboardLimit(4096, Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { ControlStringPolicy.clipboardLimit(5, -1) }
    }
}
