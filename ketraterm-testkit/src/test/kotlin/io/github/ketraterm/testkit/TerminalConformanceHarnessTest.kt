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
package io.github.ketraterm.testkit

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalConformanceHarnessTest {
    @Test
    fun `single and bytewise parser chunks produce identical complete snapshots`() {
        val bytes =
            (
                "\u001B[?69h\u001B[2;7s\u001B[2;4r" +
                    "\u001B[?6h\u001B[1;1HAB\u001B[?6l" +
                    "\u001B[?1049hfull\u001B[?1049l" +
                    "\u001B[1;1He\u0301\u001B[5n"
            ).encodeToByteArray()
        val single =
            TerminalConformanceHarness(columns = 8, rows = 4, maxHistory = 8)
                .replay(
                    TerminalReplayTranscript.of(
                        TerminalReplayEvent.Input(bytes),
                        TerminalReplayEvent.EndOfInput,
                    ),
                )
        val bytewiseHarness = TerminalConformanceHarness(columns = 8, rows = 4, maxHistory = 8)
        val bytewiseEvents =
            TerminalReplayTranscript.bytewise(bytes).events + TerminalReplayEvent.EndOfInput
        val bytewise = bytewiseHarness.replay(TerminalReplayTranscript(bytewiseEvents))

        assertEquals(single, bytewise)
        assertEquals("1B5B306E", single.outboundBytes.toHexString())
    }

    @Test
    fun `snapshot includes retained history before the complete live viewport`() {
        val snapshot =
            TerminalConformanceHarness(columns = 4, rows = 2, maxHistory = 3)
                .replay(
                    TerminalReplayTranscript.of(
                        TerminalReplayEvent.Input.utf8(
                            "\u001B[1;1HABCD\u001B[2;1HEFGH\u001B[2;1H\r\nI",
                        ),
                        TerminalReplayEvent.EndOfInput,
                    ),
                )

        assertEquals(1, snapshot.historyRows)
        assertEquals(1, snapshot.liveRowStart)
        assertEquals(3, snapshot.retainedRows.size)
        assertEquals("ABCD", snapshot.retainedRows[0].text())
        assertEquals("EFGH", snapshot.retainedRows[1].text())
        assertEquals("I", snapshot.retainedRows[2].text())
        assertEquals(1, snapshot.cursor.column)
        assertEquals(1, snapshot.cursor.row)
    }

    @Test
    fun `resize events reflow Unicode clusters and preserve soft wrap metadata`() {
        val snapshot =
            TerminalConformanceHarness(columns = 6, rows = 2, maxHistory = 4)
                .replay(
                    TerminalReplayTranscript.of(
                        TerminalReplayEvent.Input.utf8("A😀e\u0301BC"),
                        TerminalReplayEvent.Resize(columns = 3, rows = 3),
                        TerminalReplayEvent.EndOfInput,
                    ),
                )

        assertEquals(3, snapshot.columns)
        assertEquals(3, snapshot.visibleRows)
        assertTrue(snapshot.retainedRows.any { it.wrapped })
        assertTrue(
            snapshot.retainedRows
                .flatMap { it.cells }
                .any { it.cluster == "e\u0301" && it.flags and TerminalRenderCellFlags.CLUSTER != 0 },
        )
    }

    @Test
    fun `response bytes accumulate in event order without array identity semantics`() {
        val snapshot =
            TerminalConformanceHarness(columns = 10, rows = 3)
                .replay(
                    TerminalReplayTranscript.of(
                        TerminalReplayEvent.Input.utf8("\u001B[5n"),
                        TerminalReplayEvent.Input.utf8("\u001B[6n"),
                    ),
                )
        val expected = TerminalByteSequence.of("\u001B[0n\u001B[1;1R".encodeToByteArray())

        assertEquals(expected, snapshot.outboundBytes)
        assertEquals(expected.hashCode(), snapshot.outboundBytes.hashCode())
        assertNotSame(expected.copyBytes(), expected.copyBytes())
    }

    @Test
    fun `input events and transcripts detach caller-owned collections`() {
        val bytes = "ABC".encodeToByteArray()
        val event = TerminalReplayEvent.Input(bytes)
        val events = mutableListOf<TerminalReplayEvent>(event)
        val transcript = TerminalReplayTranscript(events)
        bytes[0] = 'Z'.code.toByte()
        events.clear()

        val snapshot = TerminalConformanceHarness(columns = 4, rows = 2).replay(transcript)

        assertEquals("ABC", snapshot.retainedRows[snapshot.liveRowStart].text())
        assertEquals('A'.code.toByte(), event.copyBytes()[0])
    }

    @Test
    fun `chunk helpers reject incomplete and overflowing partitions`() {
        val bytes = ByteArray(4)

        assertThrows(IllegalArgumentException::class.java) {
            TerminalReplayTranscript.chunked(bytes, 2, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalReplayTranscript.chunked(bytes, 3, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalReplayTranscript.chunked(bytes, 2, 0, 2)
        }
    }

    private fun TerminalRowSnapshot.text(): String {
        val builder = StringBuilder()
        for (cell in cells) {
            when {
                cell.cluster != null -> builder.append(cell.cluster)
                cell.codepoint > 0 -> builder.appendCodePoint(cell.codepoint)
            }
        }
        return builder.toString().trimEnd()
    }
}
