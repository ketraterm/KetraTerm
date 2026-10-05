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
import io.github.ketraterm.parser.api.TerminalCustomOscHandler
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.impl.TerminalParser
import io.github.ketraterm.parser.runtime.ParserState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class CustomOscHandlerTest {
    @Test
    fun `host can admit a custom OSC envelope above the default limit`() {
        val body = "é".repeat(3000)
        var actual: String? = null
        val parser =
            TerminalParsers.create(RecordingTerminalCommandSink(), { 0 }, 6005) { _, payload, offset, length ->
                actual = payload.decodeToString(offset, offset + length)
            }
        parser.accept("\u001b]1341;$body\u0007".encodeToByteArray())
        assertEquals(body, actual)
    }

    @Test
    fun `custom OSC is delivered between surrounding commands`() {
        val sink = RecordingTerminalCommandSink()
        val parser =
            TerminalParsers.create(sink, { 0 }) { command, payload, offset, length ->
                sink.events += "custom:$command:" + payload.decodeToString(offset, offset + length)
            }
        parser.accept("\u001b]2;before\u0007\u001b]1341;anchor\u001b\\\u001b]2;after\u0007".encodeToByteArray())
        assertEquals(
            listOf("setWindowTitle:before", "custom:1341:anchor", "setWindowTitle:after"),
            sink.events,
        )
    }

    @Test
    fun `all split points and bytewise input preserve exact callback order`() {
        for (terminator in listOf("\u0007", "\u001b\\")) {
            val bytes =
                ("\u001b]2;before\u0007\u001b]1341;a;é😀$terminator\u001b]2147483647;$terminator\u001b]2;after\u0007")
                    .encodeToByteArray()
            for (split in -1..bytes.size) {
                val sink = RecordingTerminalCommandSink()
                val parser =
                    TerminalParsers.create(sink, { 0 }) { command, payload, offset, length ->
                        sink.events += "custom:$command:" + payload.decodeToString(offset, offset + length)
                    }
                if (split == -1) {
                    for (byte in bytes) parser.acceptByte(byte.toInt() and 255)
                } else {
                    parser.accept(bytes, 0, split)
                    parser.accept(bytes, split, bytes.size - split)
                }
                assertEquals(
                    listOf("setWindowTitle:before", "custom:1341:a;é😀", "custom:2147483647:", "setWindowTitle:after"),
                    sink.events,
                    "split=$split",
                )
            }
        }
    }

    @Test
    fun `custom bodies retain opaque bytes and follow existing string control rules`() {
        var actual = byteArrayOf()
        val parser =
            TerminalParsers.create(RecordingTerminalCommandSink(), { 0 }) { command, payload, offset, length ->
                assertEquals(1341, command)
                actual = payload.copyOfRange(offset, offset + length)
            }
        val body = byteArrayOf(0xc0.toByte(), 0xff.toByte(), 0x9c.toByte(), 0x9d.toByte(), 0x41)
        parser.accept("\u001b]001341;\u0001\u007f".encodeToByteArray() + body + byteArrayOf(7))
        assertArrayEquals(body, actual)
    }

    @Test
    fun `bounds reject complete commands without growth or truncated dispatch`() {
        for (size in listOf(4095, 4096, 4097, 100000)) {
            val state = ParserState()
            val storage = state.payloadBuffer
            val bodies = mutableListOf<String>()
            val parser =
                TerminalParser(
                    RecordingTerminalCommandSink(),
                    state,
                    customOscHandler =
                        TerminalCustomOscHandler { _, payload, offset, length ->
                            bodies += payload.decodeToString(offset, offset + length)
                        },
                )
            val body = "x".repeat(size - 5)
            parser.accept("\u001b]1341;$body".encodeToByteArray())
            assertSame(storage, state.payloadBuffer)
            assertTrue(state.payloadLength <= 4096)
            parser.accept("\u001b\\\u001b]1341;ok\u0007".encodeToByteArray())
            assertEquals(if (size <= 4096) listOf(body, "ok") else listOf("ok"), bodies)
        }
    }

    @Test
    fun `malformed headers never reach the custom handler`() {
        val commands = mutableListOf<Int>()
        val parser = TerminalParsers.create(RecordingTerminalCommandSink(), { 0 }) { command, _, _, _ -> commands += command }
        for (header in listOf("", "-1", "+1341", "13x41", "2147483648", "9".repeat(5000))) {
            parser.accept("\u001b]$header;bad\u0007".encodeToByteArray())
        }
        parser.accept("\u001b]1341\u0007\u001b]001341;ok\u0007".encodeToByteArray())
        assertEquals(listOf(1341), commands)
    }

    @Test
    fun `cancellation EOF and reset discard pending custom commands`() {
        for (suffix in listOf("", "\u001b")) {
            for (ending in listOf("can", "sub", "eof", "reset", "escapeCan")) {
                for (size in listOf(0, 12, 5000)) {
                    val bodies = mutableListOf<String>()
                    val parser =
                        TerminalParsers.create(RecordingTerminalCommandSink(), { 0 }) { _, payload, offset, length ->
                            bodies += payload.decodeToString(offset, offset + length)
                        }
                    parser.accept(("\u001b]1341;" + "x".repeat(size) + suffix).encodeToByteArray())
                    when (ending) {
                        "can" -> parser.acceptByte(0x18)
                        "sub" -> parser.acceptByte(0x1a)
                        "eof" -> parser.endOfInput()
                        "reset" -> parser.reset()
                        "escapeCan" -> parser.accept("\u001b[0m\u0018".encodeToByteArray())
                    }
                    parser.accept("\u001b]1341;ok\u0007".encodeToByteArray())
                    assertEquals(listOf("ok"), bodies, "$ending size=$size suffix=$suffix")
                }
            }
        }
    }

    @Test
    fun `supported families never fall through for malformed or oversized bodies`() {
        val commands = mutableListOf<Int>()
        val sink = RecordingTerminalCommandSink()
        val parser = TerminalParsers.create(sink, { 0 }) { command, _, _, _ -> commands += command }
        for (command in listOf(0, 1, 2, 4, 7, 8, 9, 10, 11, 12, 52, 133, 777)) {
            for (body in listOf("", "?", "bad", "x".repeat(5000))) {
                parser.accept("\u001b]$command;$body\u0007".encodeToByteArray())
            }
        }
        parser.accept("\u001b]52;c;?\u0007\u001b]10;?\u0007\u001b]1341;ok\u0007".encodeToByteArray())
        assertEquals(listOf(1341), commands)
        assertTrue(sink.events.contains("requestClipboard:c:?"))
        assertTrue(sink.events.contains("queryDynamicColor:10"))
    }

    @Test
    fun `callback failure propagates unchanged without processing later commands`() {
        for (failure in listOf(IllegalStateException("host failed"), CancellationException("host cancelled"))) {
            val sink = RecordingTerminalCommandSink()
            val parser = TerminalParsers.create(sink, { 0 }) { _, _, _, _ -> throw failure }
            assertSame(
                failure,
                assertThrows(failure.javaClass) {
                    parser.accept("\u001b]2;before\u0007\u001b]1341;fail\u0007\u001b]2;after\u0007".encodeToByteArray())
                },
            )
            parser.endOfInput()
            assertEquals(listOf("setWindowTitle:before"), sink.events)
        }
    }

    @Test
    fun `callback cannot reenter parser mutation and may handle rejection`() {
        val sink = RecordingTerminalCommandSink()
        lateinit var parser: TerminalOutputParser
        var calls = 0
        parser =
            TerminalParsers.create(sink, { 0 }) { _, _, _, _ ->
                calls++
                assertThrows(IllegalStateException::class.java) { parser.accept(byteArrayOf()) }
                assertThrows(IllegalStateException::class.java) { parser.acceptByte(65) }
                assertThrows(IllegalStateException::class.java) { parser.reset() }
                assertThrows(IllegalStateException::class.java) { parser.endOfInput() }
            }
        parser.accept("\u001b]1341;one\u0007\u001b]1341;two\u0007\u001b]2;after\u0007".encodeToByteArray())
        assertEquals(2, calls)
        assertEquals(listOf("setWindowTitle:after"), sink.events)
    }
}
