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
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class HostStatusQueryTest {
    @ParameterizedTest
    @ValueSource(strings = ["unsupported", "\nAUDIT_MARKER\n", "\u010AAUDIT_MARKER\u010A", "\u011B[31m"])
    fun `unsupported status queries return an empty failure without reflecting request text`(query: String) {
        val bytes = "\u001BP\$q$query\u001B\\X".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            parser.endOfInput()

            val response = ByteArray(128)
            val count = terminal.readResponseBytes(response)
            assertAll(
                "split=$split",
                { assertEquals("\u001BP0\$r\u001B\\", response.decodeToString(0, count)) },
                { assertEquals("X", terminal.getLineAsString(0)) },
                { assertEquals(0, terminal.pendingResponseBytes) },
            )
        }
    }

    @Test
    fun `malformed UTF8 in an unsupported status query cannot enter its failure reply`() {
        val bytes = "\u001BP\$q".encodeToByteArray() + byteArrayOf(0xC3.toByte()) + "m\u001B\\X".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            val response = ByteArray(128)
            val count = terminal.readResponseBytes(response)
            assertAll(
                "split=$split",
                { assertEquals("\u001BP0\$r\u001B\\", response.decodeToString(0, count)) },
                { assertEquals("X", terminal.getLineAsString(0)) },
            )
        }
    }

    @Test
    fun `cursor style status uses the space q selector and rejects bare q`() {
        val bytes = "\u001B[5 q\u001BP\$q q\u001B\\\u001BP\$qq\u001B\\X".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            val response = ByteArray(128)
            val count = terminal.readResponseBytes(response)
            assertEquals(
                "\u001BP1\$r5 q\u001B\\\u001BP0\$r\u001B\\",
                response.decodeToString(0, count),
                "split=$split",
            )
            assertEquals("X", terminal.getLineAsString(0))
        }
    }

    @Test
    fun `denying terminal responses suppresses valid malformed and unsupported status replies`() {
        val bytes =
            (
                "\u001BP\$qm\u001B\\\u001BP\$q q\u001B\\\u001BP\$qq\u001B\\" +
                    "\u001BP\$q\nAUDIT_MARKER\n\u001B\\\u001BP\$q\u010A\u001B\\\u001B[6n\u001B[?6nX"
            ).encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser =
                TerminalParsers.create(
                    HostCommandAdapter(terminal, hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY)),
                )
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            assertEquals(0, terminal.pendingResponseBytes, "split=$split")
            assertEquals("X", terminal.getLineAsString(0), "split=$split")
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `cursor position reports use the active origin for both reply forms`(
        horizontalMargins: Boolean,
        originMode: Boolean,
    ) {
        val marginSetup = if (horizontalMargins) "\u001B[?69h\u001B[3;8s" else ""
        val positionSetup = if (originMode) "\u001B[?6h\u001B[1;1H" else "\u001B[2;4H"
        val bytes = ("\u001B[2;4r$marginSetup$positionSetup\u001B[6n\u001B[?6n").encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            val response = ByteArray(128)
            val count = terminal.readResponseBytes(response)
            val expected = if (originMode) "\u001B[1;1R\u001B[?1;1R" else "\u001B[2;4R\u001B[?2;4R"
            assertAll(
                "split=$split",
                { assertEquals(expected, response.decodeToString(0, count)) },
                { assertEquals(1, terminal.cursorRow) },
                {
                    assertEquals(
                        if (originMode) {
                            if (horizontalMargins) {
                                2
                            } else {
                                0
                            }
                        } else {
                            3
                        },
                        terminal.cursorCol,
                    )
                },
                {
                    parser.accept("\u001B[?6l\u001B[2;4H\u001B[6n\u001B[?6n".encodeToByteArray())
                    val absoluteCount = terminal.readResponseBytes(response)
                    assertEquals("\u001B[2;4R\u001B[?2;4R", response.decodeToString(0, absoluteCount))
                },
            )
        }
    }
}
