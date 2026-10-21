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
import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.protocol.TerminalGraphicsResult
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphicsParserTest {
    @Test
    fun `decoded transfer limit rejects the first excess group and recovers on the next command`() {
        val text = RecordingTerminalCommandSink()
        val results = mutableListOf<Triple<Long, Int, TerminalGraphicsResult>>()
        val parser =
            TerminalParsers.create(
                object : TerminalCommandSink by text {
                    override fun applyGraphicsCommand(
                        command: TerminalGraphicsCommand,
                        data: ByteArray,
                        length: Int,
                        result: TerminalGraphicsResult,
                    ) {
                        results += Triple(command.valueOr('i', 0), length, result)
                    }
                },
            )
        val payload = "A".repeat(TerminalGraphicsLimits.CHUNK_BYTES)
        val continuation = apc("m=1", payload).encodeToByteArray()
        parser.accept(apc("i=1,m=1", payload).encodeToByteArray())
        val decodedChunk = TerminalGraphicsLimits.CHUNK_BYTES / 4 * 3
        repeat(TerminalGraphicsLimits.TRANSFER_BYTES / decodedChunk - 1) { parser.accept(continuation) }
        assertTrue(results.isEmpty())
        parser.accept(apc("m=0", "AAAA").encodeToByteArray())
        assertEquals(listOf(Triple(1L, TerminalGraphicsLimits.TRANSFER_BYTES, TerminalGraphicsResult.TOO_LARGE)), results)
        parser.accept(apc("i=2", "AAAA").encodeToByteArray())
        assertEquals(Triple(2L, 3, TerminalGraphicsResult.OK), results.last())
        assertTrue(text.events.isEmpty())
    }

    private data class Command(
        val id: Long,
        val bytes: ByteArray,
        val result: TerminalGraphicsResult,
    )

    private class Fixture {
        val text = RecordingTerminalCommandSink()
        val commands = mutableListOf<Command>()
        var epoch = 0L
        val parser =
            TerminalParsers.create(
                object : TerminalCommandSink by text {
                    override val graphicsTransferGeneration get() = epoch

                    override fun applyGraphicsCommand(
                        command: TerminalGraphicsCommand,
                        data: ByteArray,
                        length: Int,
                        result: TerminalGraphicsResult,
                    ) {
                        commands += Command(command.valueOr('i', 0), data.copyOf(length), result)
                    }
                },
            )

        fun feed(text: String) = parser.accept(text.encodeToByteArray())
    }

    @Test fun `padded and unpadded final groups preserve the same bytes`() {
        for ((payload, expected) in listOf(
            "AA" to byteArrayOf(0),
            "AA==" to byteArrayOf(0),
            "AAE" to byteArrayOf(0, 1),
            "AAE=" to byteArrayOf(0, 1),
            "AAEC" to byteArrayOf(0, 1, 2),
        )) {
            val f = Fixture()
            f.feed(apc("i=1", payload))
            assertContentEquals(expected, f.commands.single().bytes, payload)
            assertEquals(TerminalGraphicsResult.OK, f.commands.single().result)
        }
    }

    @Test fun `nonfinal groups require complete unpadded quanta`() {
        for (payload in listOf("A", "AA", "AAA", "AA==")) {
            val f = Fixture()
            f.feed(apc("i=1,m=1", payload))
            assertEquals(TerminalGraphicsResult.INVALID, f.commands.single().result, payload)
        }
    }

    @Test fun `payload limit is independent of read chunk boundaries and never leaks text`() {
        val f = Fixture()
        f.feed(apc("i=1", "A".repeat(TerminalGraphicsLimits.CHUNK_BYTES)))
        assertEquals(
            98_304,
            f.commands
                .single()
                .bytes.size,
        )
        assertEquals(TerminalGraphicsResult.OK, f.commands.single().result)
        f.feed(apc("i=2", "A".repeat(TerminalGraphicsLimits.CHUNK_BYTES + 1)))
        assertEquals(TerminalGraphicsResult.TOO_LARGE, f.commands.last().result)
        f.feed(apc("i=3", "AAAA"))
        assertEquals(3L, f.commands.last().id)
        assertEquals(TerminalGraphicsResult.OK, f.commands.last().result)
        assertTrue(f.text.events.isEmpty())
    }

    @Test fun `oversized headers unknown keys invalid escape and non ASCII drain through ST`() {
        for (body in listOf(
            "G" + "i".repeat(1025),
            "Gi=1,k=8;AAAA",
            "Gi=1;AA\u001bXAA",
            "Gi=1;A\u0080AA",
            "Gi=1;AA\u0007AA",
            "Gi=1;AA\u007fAA",
        )) {
            val f = Fixture()
            f.feed("\u001b_$body\u001b\\" + apc("i=9", "AAAA"))
            assertEquals(9L, f.commands.last().id, body)
            assertTrue(f.text.events.isEmpty(), body)
        }
    }

    @Test fun `conflicting continuations abort without dispatching stale metadata`() {
        val f = Fixture()
        f.feed(apc("a=t,i=1,m=1", "AAAA"))
        f.feed(apc("a=T,m=0", "AAAA"))
        assertTrue(f.commands.isEmpty())
        f.feed(apc("i=2", "AAAA"))
        assertEquals(2L, f.commands.single().id)
    }

    @Test fun `EOF reset and epoch invalidate transfers between completed APCs`() {
        for (boundary in listOf("eof", "reset", "epoch")) {
            val f = Fixture()
            f.feed(apc("i=1,m=1", "AAAA"))
            when (boundary) {
                "eof" -> f.parser.endOfInput()
                "reset" -> f.parser.reset()
                else -> f.epoch++
            }
            f.feed(apc("i=2", "AAAA"))
            assertEquals(2L, f.commands.single().id)
            assertEquals(
                3,
                f.commands
                    .single()
                    .bytes.size,
            )
        }
    }

    @Test fun `epoch changes discard partial first and continuation chunks at every byte boundary`() {
        for (continuation in listOf(false, true)) {
            for (more in 0..1) {
                val chunk = apc(if (continuation) "m=$more" else "i=1,m=$more", "AAEC")
                for (split in 2 until chunk.length) {
                    val context = "continuation=$continuation more=$more split=$split"
                    val f = Fixture()
                    if (continuation) f.feed(apc("i=1,m=1", "AwQF"))
                    f.feed(chunk.substring(0, split))
                    f.epoch++
                    f.feed(chunk.substring(split))
                    assertTrue(f.commands.isEmpty(), context)

                    // A discarded nonfinal envelope must not keep the old image ID or bytes.
                    if (more == 1) {
                        f.feed(apc("m=0", "Bg=="))
                        val unbound = f.commands.single()
                        assertEquals(0L, unbound.id, context)
                        assertContentEquals(byteArrayOf(6), unbound.bytes, context)
                        f.commands.clear()
                    }
                    f.feed(apc("i=2", "BwgJ"))
                    val recovered = f.commands.single()
                    assertEquals(2L, recovered.id, context)
                    assertContentEquals(byteArrayOf(7, 8, 9), recovered.bytes, context)
                    assertEquals(TerminalGraphicsResult.OK, recovered.result, context)
                    assertTrue(f.text.events.isEmpty(), context)
                }
            }
        }
    }

    @Test fun `unknown APC between chunks preserves a valid transfer across read boundaries`() {
        val unknown = "\u001b_unknown\u001b\\"
        for (split in 2 until unknown.length) {
            val f = Fixture()
            f.feed(apc("i=1,m=1", "AAEC"))
            f.feed(unknown.substring(0, split))
            f.feed(unknown.substring(split))
            f.feed(apc("m=0", "Aw=="))
            val command = f.commands.single()
            assertEquals(1L, command.id, "split=$split")
            assertContentEquals(byteArrayOf(0, 1, 2, 3), command.bytes, "split=$split")
            assertEquals(TerminalGraphicsResult.OK, command.result, "split=$split")
            assertTrue(f.text.events.isEmpty(), "split=$split")
        }
    }

    private companion object {
        fun apc(
            header: String,
            payload: String,
        ) = "\u001b_G$header;$payload\u001b\\"
    }
}
