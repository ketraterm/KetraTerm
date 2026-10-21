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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class HostGraphemeModeTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "\u001B[?2027h", "\u001B[?2027l", "\u001B[!p", "\u001Bc", "\u001B[?1049h", "\u001B[?1049h\u001B[?1049l"])
    fun `grapheme processing remains enabled across mode and lifecycle commands`(command: String) {
        val buffer = TerminalBuffers.create(10, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        parser.accept((command + "\u001B[?2027\$p\uD83D\uDC69\u200D\uD83D\uDCBBX\u001B[6n").encodeToByteArray())
        assertEquals("\u001B[?2027;3\$y\u001B[1;4R", drain(buffer))
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBBX", buffer.getLineAsString(0))
        assertEquals(-1, buffer.getCodepointAt(1, 0))
        assertEquals('X'.code, buffer.getCodepointAt(2, 0))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mode commands and queries preserve cells pending wrap and frame generation under either reply policy`(allowReplies: Boolean) {
        val bytes = "\u001B[?2027h\u001B[?2027\$p\u001B[?2027l\u001B[?2027\$p\u001B[2027\$p".encodeToByteArray()
        for (split in 0..bytes.size) {
            val buffer = TerminalBuffers.create(6, 2)
            val parser =
                TerminalParsers.create(
                    HostCommandAdapter(
                        buffer,
                        hostPolicy =
                            HostPolicy(
                                terminalResponsePolicy = if (allowReplies) HostControlPolicy.ALLOW else HostControlPolicy.DENY,
                                windowManipulationPolicy = HostControlPolicy.DENY,
                            ),
                    ),
                )
            parser.accept("abcdef".encodeToByteArray())
            val modes = buffer.getModeBitsSnapshot()
            var generation = -1L
            (buffer as TerminalRenderFrameReader).readRenderFrame { generation = it.frameGeneration }
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(if (allowReplies) "\u001B[?2027;3\$y\u001B[?2027;3\$y\u001B[2027;0\$y" else "", drain(buffer))
            assertEquals(modes, buffer.getModeBitsSnapshot())
            buffer.readRenderFrame { assertEquals(generation, it.frameGeneration) }
            assertEquals("abcdef", buffer.getLineAsString(0))
            assertEquals(5, buffer.cursorCol)
            assertEquals(0, buffer.cursorRow)
            parser.accept("X".encodeToByteArray())
            assertEquals("X", buffer.getLineAsString(1), "pending wrap survives mode requests, split=$split")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "A,1",
        "\u4E00,2",
        "e\u0301,1",
        "\u2764\uFE0F,2",
        "\u2615\uFE0E,1",
        "\uD83D\uDC69\u200D\uD83D\uDCBB,2",
        "\uD83D\uDC4D\uD83C\uDFFD,2",
        "\uD83C\uDDFA\uD83C\uDDF8,2",
        "\u1100\u1161\u11A8,2",
    )
    fun `reported grapheme processing agrees with cluster cells and cursor reports across every byte split`(
        cluster: String,
        width: Int,
    ) {
        val bytes = ("\u001B[?2027l\u001B[?2027h\u001B[?2027\$p" + cluster + "X\u001B[6n").encodeToByteArray()
        for (split in -1..bytes.size) {
            val buffer = TerminalBuffers.create(10, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(buffer))
            if (split < 0) {
                for (byte in bytes) parser.acceptByte(byte.toInt() and 0xff)
            } else {
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
            }
            parser.endOfInput()
            val context = "cluster=$cluster, split=$split"
            assertEquals("\u001B[?2027;3\$y\u001B[1;${width + 2}R", drain(buffer), context)
            assertEquals(cluster + "X", buffer.getLineAsString(0), context)
            assertEquals('X'.code, buffer.getCodepointAt(width, 0), context)
            if (width == 2) assertEquals(-1, buffer.getCodepointAt(1, 0), context)
            val expected = cluster.codePoints().toArray()
            if (expected.size > 1) {
                val actual = IntArray(expected.size)
                assertEquals(expected.size, buffer.getLine(0).readCluster(0, actual), context)
                assertArrayEquals(expected, actual, context)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u001B[?2027;1\$p", "\u001B[?2027:1\$p", "\u001B[?20270000000\$p", "\u001B[?2027\u0018", "\u001B[?2027\u001A"])
    fun `invalid mode queries recover without replies or printed payload`(invalid: String) {
        val bytes = (invalid + "X\u001B[?2027\$p").encodeToByteArray()
        for (split in 0..bytes.size) {
            val buffer = TerminalBuffers.create(10, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(buffer))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            parser.endOfInput()
            assertEquals("\u001B[?2027;3\$y", drain(buffer), "split=$split")
            assertEquals("X", buffer.getLineAsString(0), "split=$split")
        }
    }

    private fun drain(buffer: TerminalBuffer): String {
        val bytes = ByteArray(buffer.pendingResponseBytes)
        buffer.readResponseBytes(bytes)
        return bytes.decodeToString()
    }
}
