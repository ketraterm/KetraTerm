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
import io.github.ketraterm.protocol.TerminalHostModeCapability
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HostModeStatusTest {
    @ParameterizedTest(name = "private={0}, mode={1}, default status={2}")
    @CsvSource(
        "false,4,2",
        "false,20,2",
        "true,1,2",
        "true,3,2",
        "true,5,2",
        "true,6,2",
        "true,7,1",
        "true,9,2",
        "true,12,1",
        "true,25,1",
        "true,47,2",
        "true,66,2",
        "true,67,2",
        "true,69,2",
        "true,1000,2",
        "true,1002,2",
        "true,1003,2",
        "true,1004,2",
        "true,1005,2",
        "true,1006,2",
        "true,1015,2",
        "true,1016,2",
        "true,1042,2",
        "true,1043,2",
        "true,1047,2",
        "true,1049,2",
        "true,2004,2",
        "true,2026,2",
    )
    fun everySupportedModeReportsDefaultSetAndReset(
        privateMode: Boolean,
        mode: Int,
        initial: Int,
    ) {
        val buffer = TerminalBuffers.create(80, 3)
        val events =
            object : HostEventSink by HostEventSink.NONE {
                override fun requestColumnMode(
                    rows: Int,
                    columns: Int,
                ): Boolean = true
            }
        val parser =
            TerminalParsers.create(
                HostCommandAdapter(buffer, events, modeReportCapabilities = TerminalHostModeCapability.ALL),
            )
        val prefix = if (privateMode) "?" else ""
        val sequence = "\u001B[$prefix$mode"
        parser.accept((sequence + "\$p").encodeToByteArray())
        assertEquals("\u001B[$prefix$mode;$initial\$y", drain(buffer))
        parser.accept((sequence + "h" + sequence + "\$p").encodeToByteArray())
        assertEquals("\u001B[$prefix$mode;1\$y", drain(buffer))
        parser.accept((sequence + "l" + sequence + "\$p").encodeToByteArray())
        assertEquals("\u001B[$prefix$mode;2\$y", drain(buffer))
    }

    @Test
    fun mouseSelectionsAndAlternateAliasesReportEffectiveState() {
        val buffer = TerminalBuffers.create(10, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        parser.accept(
            (
                "\u001B[?1000h\u001B[?1003h\u001B[?1005h\u001B[?1006h" +
                    "\u001B[?1000\$p\u001B[?1003\$p\u001B[?1005\$p\u001B[?1006\$p"
            ).encodeToByteArray(),
        )
        assertEquals("\u001B[?1000;2\$y\u001B[?1003;1\$y\u001B[?1005;2\$y\u001B[?1006;1\$y", drain(buffer))
        for (entry in listOf(47, 1047, 1049)) {
            parser.accept(("\u001B[?$entry" + "h\u001B[?47\$p\u001B[?1047\$p\u001B[?1049\$p").encodeToByteArray())
            assertEquals("\u001B[?47;1\$y\u001B[?1047;1\$y\u001B[?1049;1\$y", drain(buffer))
            parser.accept(("\u001B[?$entry" + "l\u001B[?47\$p\u001B[?1047\$p\u001B[?1049\$p").encodeToByteArray())
            assertEquals("\u001B[?47;2\$y\u001B[?1047;2\$y\u001B[?1049;2\$y", drain(buffer))
        }
    }

    @Test
    fun allowedAndDeniedQueriesPreserveStateAndRenderGenerationsAcrossEverySplit() {
        val stream =
            (
                "\u001B[4\$p\u001B[?4\$p\u001B[?2004\$p\u001B[?1048\$p" +
                    "\u001B[?2031\$p\u001B[?2147483647\$p"
            ).encodeToByteArray()
        for (permission in HostControlPolicy.entries) {
            for (split in 0..stream.size) {
                val buffer = TerminalBuffers.create(10, 3)
                val parser =
                    TerminalParsers.create(
                        HostCommandAdapter(buffer, hostPolicy = HostPolicy(terminalResponsePolicy = permission)),
                    )
                parser.accept("hello\u001B[2;3H\u001B7\u001B[?2004h".encodeToByteArray())
                val modes = buffer.getModeBitsSnapshot()
                var generation = -1L
                (buffer as TerminalRenderFrameReader).readRenderFrame { generation = it.frameGeneration }
                parser.accept(stream, 0, split)
                parser.accept(stream, split, stream.size - split)
                val expected =
                    if (permission == HostControlPolicy.ALLOW) {
                        "\u001B[4;2\$y\u001B[?4;0\$y\u001B[?2004;1\$y\u001B[?1048;0\$y" +
                            "\u001B[?2031;0\$y\u001B[?2147483647;0\$y"
                    } else {
                        ""
                    }
                assertEquals(expected, drain(buffer))
                assertEquals(modes, buffer.getModeBitsSnapshot())
                buffer.readRenderFrame { assertEquals(generation, it.frameGeneration) }
                assertEquals("hello", buffer.getLineAsString(0))
                parser.accept("\u001B[H\u001B8X".encodeToByteArray())
                parser.endOfInput()
                assertEquals("  X", buffer.getLineAsString(1))
            }
        }
    }

    @Test
    fun rejectedColumnSwitchesPreserveReportedSelectionAndQueriesDoNotCallHost() {
        val buffer = TerminalBuffers.create(80, 3)
        var accepted = true
        var requests = 0
        val events =
            object : HostEventSink by HostEventSink.NONE {
                override fun requestColumnMode(
                    rows: Int,
                    columns: Int,
                ): Boolean {
                    requests++
                    return accepted
                }
            }
        val adapter = HostCommandAdapter(buffer, events, modeReportCapabilities = TerminalHostModeCapability.COLUMN_MODE)
        val parser = TerminalParsers.create(adapter)
        parser.accept("\u001B[?3h\u001B[?3\$p".encodeToByteArray())
        assertEquals("\u001B[?3;1\$y", drain(buffer))
        assertEquals(1, requests)
        accepted = false
        parser.accept("\u001B[?3l\u001B[?3\$p".encodeToByteArray())
        assertEquals("\u001B[?3;1\$y", drain(buffer))
        assertEquals(2, requests)
        adapter.setHostPolicy(HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY))
        parser.accept("\u001B[?3l\u001B[?3\$p".encodeToByteArray())
        assertEquals("\u001B[?3;1\$y", drain(buffer))
        assertEquals(2, requests)
        assertEquals(132, buffer.width)
    }

    @Test
    fun malformedQueriesRecoverIntoPrintableTextAndValidQuery() {
        val buffer = TerminalBuffers.create(10, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        parser.accept(
            (
                "\u001B[?2004;7\$p\u001B[?7:1\$p\u001B[?999999999999\$p" +
                    "\u001B[?" + "1;".repeat(32) + "7\$pX\u001B[?7\$p"
            ).encodeToByteArray(),
        )
        parser.endOfInput()
        assertEquals("\u001B[?7;1\$y", drain(buffer))
        assertEquals("X", buffer.getLineAsString(0))
    }

    private fun drain(buffer: TerminalBuffer): String {
        val bytes = ByteArray(buffer.pendingResponseBytes)
        buffer.readResponseBytes(bytes)
        return bytes.decodeToString()
    }
}
