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
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.impl.TerminalParser
import io.github.ketraterm.parser.runtime.ParserState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class CustomOscLimitTest {
    @Test
    fun `limits include the header and reject the whole command on overflow`() {
        for (limit in listOf(1, 4, 5, 4096, 6005, 16389)) {
            for (size in listOf(5, maxOf(5, limit - 1), maxOf(5, limit), maxOf(5, limit + 1))) {
                for (terminator in listOf("\u0007", "\u001b\\")) {
                    val state = ParserState()
                    val storage = state.payloadBuffer
                    val bodies = mutableListOf<String>()
                    val parser =
                        TerminalParser(
                            RecordingTerminalCommandSink(),
                            state,
                            customOscPayloadLimitBytes = limit,
                            customOscHandler =
                                TerminalCustomOscHandler { _, payload, offset, length ->
                                    bodies += payload.decodeToString(offset, offset + length)
                                },
                        )
                    val body = "x".repeat(size - 5)
                    for (byte in "\u001b]1341;$body$terminator".encodeToByteArray()) {
                        parser.acceptByte(byte.toInt() and 255)
                        assertTrue(state.payloadBuffer.size <= maxOf(4096, limit))
                    }
                    assertEquals(if (size <= limit) listOf(body) else emptyList(), bodies, "limit=$limit size=$size")
                    assertSame(storage, state.payloadBuffer)
                    assertEquals(0, state.payloadLength)
                }
            }
        }
    }

    @Test
    fun `every termination path releases temporary storage`() {
        for (ending in listOf("bel", "st", "can", "sub", "eof", "reset", "overflow", "failure", "cancellation")) {
            val state = ParserState()
            val storage = state.payloadBuffer
            var calls = 0
            val failure = if (ending == "cancellation") CancellationException("host cancelled") else IllegalStateException("host failed")
            val parser =
                TerminalParser(
                    RecordingTerminalCommandSink(),
                    state,
                    customOscPayloadLimitBytes = 6005,
                    customOscHandler =
                        TerminalCustomOscHandler { _, _, _, length ->
                            calls++
                            assertEquals(5000, length)
                            if (ending == "failure" || ending == "cancellation") throw failure
                        },
                )
            parser.accept(("\u001b]1341;" + "x".repeat(5000)).encodeToByteArray())
            assertNotSame(storage, state.payloadBuffer)
            assertTrue(state.payloadBuffer.size <= 6005)
            when (ending) {
                "bel" -> parser.acceptByte(7)
                "st" -> parser.accept("\u001b\\".encodeToByteArray())
                "can" -> parser.acceptByte(0x18)
                "sub" -> parser.acceptByte(0x1a)
                "eof" -> parser.endOfInput()
                "reset" -> parser.reset()
                "overflow" -> parser.accept("x".repeat(1001).encodeToByteArray())
                else -> assertSame(failure, assertThrows(failure.javaClass) { parser.acceptByte(7) })
            }
            assertSame(storage, state.payloadBuffer, ending)
            assertEquals(if (ending in listOf("bel", "st", "failure", "cancellation")) 1 else 0, calls, ending)
            parser.endOfInput()
            assertEquals(0, state.payloadLength)
        }
    }

    @Test
    fun `custom limits leave built in limits and header limits unchanged`() {
        val state = ParserState()
        val storage = state.payloadBuffer
        val sink = RecordingTerminalCommandSink()
        val commands = mutableListOf<Int>()
        val parser =
            TerminalParser(
                sink,
                state,
                customOscPayloadLimitBytes = Int.MAX_VALUE - 8,
                customOscHandler = TerminalCustomOscHandler { command, _, _, _ -> commands += command },
            )
        val envelopes =
            listOf(
                "\u001b]2;" + "x".repeat(4095),
                "\u001b]10;" + "x".repeat(254),
                "\u001b]52;c;" + "A".repeat(5000),
                "\u001b]133;" + "x".repeat(5000),
                "\u001b]2147483648;" + "x".repeat(5000),
                "\u001b]" + "0".repeat(4092) + "1341;body",
                "\u001bP+q" + "x".repeat(4095),
                "\u001bP" + '$' + "q" + "x".repeat(63),
            )
        for (envelope in envelopes) {
            parser.accept(envelope.encodeToByteArray())
            assertSame(storage, state.payloadBuffer)
            assertTrue(state.payloadLength <= 4096)
            parser.accept("\u001b\\".encodeToByteArray())
        }
        assertTrue(commands.isEmpty())
        assertFalse(sink.events.any { it.startsWith("setWindowTitle:") || it.startsWith("requestClipboard:") })
        parser.accept("\u001b]1341;ok\u0007".encodeToByteArray())
        assertEquals(listOf(1341), commands)
        assertSame(storage, state.payloadBuffer)
    }

    @Test
    fun `public limits are validated without reserving the maximum capacity`() {
        val sink = RecordingTerminalCommandSink()
        val handler = TerminalCustomOscHandler { _, _, _, _ -> }
        for (limit in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE - 7, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TerminalParsers.create(sink, { 0 }, limit, handler) }
        }
        TerminalParsers.create(sink, { 0 }, 1, handler).accept("\u001b]1341;x\u0007".encodeToByteArray())
        var calls = 0
        val parser =
            TerminalParsers.create(sink, { 0 }, Int.MAX_VALUE - 8) { _, _, _, length ->
                calls++
                assertEquals(1, length)
            }
        parser.accept("\u001b]1341;x\u0007".encodeToByteArray())
        assertEquals(1, calls)
    }

    @Test
    fun `UTF8 bytes survive chunks at growth and terminator boundaries`() {
        val body = "é😀".repeat(2000)
        for (terminator in listOf("\u0007", "\u001b\\")) {
            val bytes = "\u001b]1341;$body$terminator\u001b]2;after\u0007".encodeToByteArray()
            for (split in listOf(0, 2, 6, 4095, 4096, 4097, 8193, 12007, 12008, bytes.size)) {
                val sink = RecordingTerminalCommandSink()
                var actual: String? = null
                val parser =
                    TerminalParsers.create(sink, { 0 }, 12005) { _, payload, offset, length ->
                        actual = payload.decodeToString(offset, offset + length)
                        assertTrue(sink.events.isEmpty())
                    }
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
                assertEquals(body, actual, "split=$split")
                assertEquals(listOf("setWindowTitle:after"), sink.events)
            }
        }
    }
}
