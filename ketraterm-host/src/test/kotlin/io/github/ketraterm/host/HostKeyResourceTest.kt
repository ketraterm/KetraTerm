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
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostKeyResourceTest {
    @Test
    fun `set query disable reset and format query survive every byte split`() {
        val bytes =
            (
                "\u001B[>1;3m\u001B[>3;4m\u001B[>6;4m\u001B[>7;4m" +
                    "\u001B[>1;1f\u001B[?1;2;3;4;6;7m\u001B[?1;4g" +
                    "\u001B[>n\u001B[?2m\u001B[>2;m\u001B[?2m"
            ).encodeToByteArray()
        val expected =
            "\u001B[>1;3m\u001B[>2;2m\u001B[>3;4m\u001B[>4;0m\u001B[>6;4m\u001B[>7;4m" +
                "\u001B[>1;1f\u001B[>4;0f\u001B[>2;65535m\u001B[>2;2m"
        for (split in 0..bytes.size) {
            val terminal = TerminalBuffers.create(10, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(expected, drain(terminal), "split=$split")
            assertEquals(3, TerminalInputState.keyModifierOption(terminal.getInputModeBits(), 1))
        }
    }

    @Test
    fun `documented reset all includes keyboard modifier and special resources`() {
        val terminal = TerminalBuffers.create(10, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept(
            (
                "\u001B[>0;15m\u001B[>6;4m\u001B[>7n\u001B[>0;1f\u001B[>6;1f\u001B[>7;1f" +
                    "\u001B[>m\u001B[?0;1;2;6;7m\u001B[?0;6;7g\u001B[>f\u001B[?0;6;7g"
            ).encodeToByteArray(),
        )
        assertEquals(
            "\u001B[>0;0m\u001B[>1;2m\u001B[>2;2m\u001B[>6;0m\u001B[>7;0m" +
                "\u001B[>0;1f\u001B[>6;1f\u001B[>7;1f\u001B[>0;0f\u001B[>6;0f\u001B[>7;0f",
            drain(terminal),
        )
    }

    @Test
    fun `response denial suppresses both families without suppressing settings`() {
        val terminal = TerminalBuffers.create(10, 3)
        val parser =
            TerminalParsers.create(
                HostCommandAdapter(terminal, hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY)),
            )
        parser.accept("\u001B[>1;3m\u001B[>1;1f\u001B[?0;1;2;3;4;6;7m\u001B[?0;1;2;3;4;6;7g".encodeToByteArray())
        assertEquals("", drain(terminal))
        assertEquals(3, TerminalInputState.keyModifierOption(terminal.getInputModeBits(), 1))
        assertEquals(1, TerminalInputState.keyFormatOption(terminal.getInputModeBits(), 1))
    }

    @Test
    fun `malformed and unsupported controls neither partially mutate nor partially reply`() {
        for (sequence in listOf(
            ">1;3;0m",
            ">1;1;0f",
            ">1:3m",
            ">1;3:0m",
            ">1;1:0f",
            ">;3m",
            ">1;999999999999999999999m",
            ">1;999999999999999999999f",
            "?1;999999999999999999999m",
            "?1;999999999999999999999g",
            "?1;2:0m",
            "?1;2:0g",
            "?1;m",
            "?1;g",
            ">1;2n",
            ">5;2m",
            ">5;1f",
            "?5m",
            "?5g",
            ">8;2m",
            "?8g",
            ">4;4m",
            ">0;16m",
            ">1;5m",
            ">1;2f",
        )) {
            val terminal = TerminalBuffers.create(10, 3)
            val before = terminal.getInputModeBits()
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(("\u001B[" + sequence).encodeToByteArray())
            assertEquals(before, terminal.getInputModeBits(), sequence)
            assertEquals("", drain(terminal), sequence)
            parser.accept("\u001B[>1;3m\u001B[?1m".encodeToByteArray())
            assertEquals("\u001B[>1;3m", drain(terminal), sequence)
        }
    }

    private fun drain(terminal: TerminalBuffer): String {
        val bytes = ByteArray(1024)
        return bytes.decodeToString(0, terminal.readResponseBytes(bytes))
    }
}
