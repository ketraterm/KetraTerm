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

/**
 * Specification- and declared-compatibility-policy-derived full-pipeline corpus.
 *
 * Each test states terminal semantics explicitly, then replays the same logical
 * stream through every bounded chunk partition. The single-chunk result is used
 * only as the chunk-invariance reference after those independent semantic
 * assertions pass.
 */
class TerminalSpecificationConformanceCorpusTest {
    @Test
    fun `pending wrap honors horizontal and vertical margins in origin mode`() {
        val bytes =
            ("\u001B[?69h" + "\u001B[3;6s" + "\u001B[2;4r" + "\u001B[?6h" + "\u001B[1;1H" + "ABCDE")
                .encodeToByteArray()

        assertEveryChunking(columns = 8, rows = 4, bytes = bytes) { snapshot ->
            val live = snapshot.liveRowStart
            assertCodepoints(snapshot, live + 1, 2, "ABCD")
            assertCodepoints(snapshot, live + 2, 2, "E")
            assertTrue(snapshot.retainedRows[live + 1].wrapped)
            assertEquals(3, snapshot.cursor.column)
            assertEquals(2, snapshot.cursor.row)
            assertTrue(snapshot.modes.originMode)
            assertTrue(snapshot.modes.leftRightMarginMode)
        }
    }

    @Test
    fun `DCH and ECH repair intersected wide and cluster spans without orphan cells`() {
        val bytes =
            (
                "A\uD83D\uDE00B" +
                    "\u001B[1;2H\u001B[P" +
                    "\u001B[2;1He\u0301X" +
                    "\u001B[2;1H\u001B[X"
            ).encodeToByteArray()

        assertEveryChunking(columns = 8, rows = 3, bytes = bytes) { snapshot ->
            val live = snapshot.liveRowStart
            val first = snapshot.retainedRows[live].cells
            val second = snapshot.retainedRows[live + 1].cells
            assertEquals('A'.code, first[0].codepoint)
            assertTrue(first[1].flags and TerminalRenderCellFlags.EMPTY != 0)
            assertEquals('B'.code, first[2].codepoint)
            assertTrue(second[0].flags and TerminalRenderCellFlags.EMPTY != 0)
            assertEquals('X'.code, second[1].codepoint)
            assertEquals(0, snapshot.cursor.column)
            assertEquals(1, snapshot.cursor.row)
            assertNoOrphanWideCells(snapshot)
            assertFalse(snapshot.retainedRows.flatMap { it.cells }.any { it.cluster != null })
        }
    }

    @Test
    fun `primary reflow remains bottom anchored behind a resized alternate screen`() {
        val beforeResize = "abcdefghi\u001B[?1049hALT".encodeToByteArray()
        val suffix =
            listOf<TerminalReplayEvent>(
                TerminalReplayEvent.Resize(columns = 3, rows = 3),
                TerminalReplayEvent.Input.utf8("\u001B[?1049l"),
                TerminalReplayEvent.EndOfInput,
            )
        val variants = TerminalReplayChunkings.exhaustive(beforeResize, endOfInput = false)
        val reference = replay(columns = 5, rows = 2, maxHistory = 4, variants.first().transcript.events + suffix)

        assertEquals(TerminalSnapshotBufferKind.PRIMARY, reference.activeBuffer)
        assertEquals(1, reference.historyRows)
        assertCodepoints(reference, 0, 0, "abc")
        assertCodepoints(reference, reference.liveRowStart, 0, "def")
        assertCodepoints(reference, reference.liveRowStart + 1, 0, "ghi")
        assertTrue(reference.retainedRows[0].wrapped)
        assertTrue(reference.retainedRows[reference.liveRowStart].wrapped)
        assertTrue(reference.retainedRows[reference.liveRowStart + 1].wrapped)
        assertFalse(reference.retainedRows[reference.liveRowStart + 2].wrapped)
        assertEquals(2, reference.cursor.column)
        assertEquals(1, reference.cursor.row)

        assertVariantsMatch(reference, columns = 5, rows = 2, maxHistory = 4, variants = variants, suffix = suffix)
    }

    @Test
    fun `DECSTR preserves content while restoring soft modes and pen state`() {
        val bytes =
            (
                "\u001B[?1;6;7;12;25;66;1004;1006;2004h" +
                    "\u001B[4hAB" +
                    "\u001B[1;38;5;196m" +
                    "\u001B[?7l" +
                    "\u001B[1\"q" +
                    "\u001B[!pC"
            ).encodeToByteArray()

        assertEveryChunking(columns = 8, rows = 3, bytes = bytes) { snapshot ->
            val live = snapshot.liveRowStart
            assertCodepoints(snapshot, live, 0, "ABC")
            assertFalse(snapshot.modes.insertMode)
            assertFalse(snapshot.modes.applicationCursorKeys)
            assertFalse(snapshot.modes.applicationKeypad)
            assertFalse(snapshot.modes.originMode)
            assertTrue(snapshot.modes.autoWrap)
            assertTrue(snapshot.modes.cursorVisible)
            assertTrue(snapshot.modes.cursorBlinking)
            assertFalse(snapshot.modes.focusReporting)
            assertFalse(snapshot.modes.bracketedPaste)
            assertEquals("SGR", snapshot.modes.mouseEncodingMode)
            assertEquals(3, snapshot.cursor.column)
            assertEquals(0, snapshot.cursor.row)
        }
    }

    @Test
    fun `RIS clears content and restores default cursor and mode state`() {
        val bytes = "ABC\u001B[?7l\u001B[2;3H\u001BcX".encodeToByteArray()

        assertEveryChunking(columns = 6, rows = 2, maxHistory = 4, bytes = bytes) { snapshot ->
            assertEquals(0, snapshot.historyRows)
            assertCodepoints(snapshot, snapshot.liveRowStart, 0, "X")
            assertTrue(
                snapshot.retainedRows
                    .drop(1)
                    .flatMap { it.cells }
                    .all { it.codepoint == 0 },
            )
            assertEquals(1, snapshot.cursor.column)
            assertEquals(0, snapshot.cursor.row)
            assertTrue(snapshot.modes.autoWrap)
            assertFalse(snapshot.modes.originMode)
            assertEquals(TerminalSnapshotBufferKind.PRIMARY, snapshot.activeBuffer)
        }
    }

    @Test
    fun `malformed UTF-8 recovers before CSI and OSC without leaking structural bytes`() {
        val bytes =
            byteArrayOf(
                'A'.code.toByte(),
                0xE2.toByte(),
                0x1B,
                '['.code.toByte(),
                '2'.code.toByte(),
                'B'.code.toByte(),
                0x1B,
                ']'.code.toByte(),
                '2'.code.toByte(),
                ';'.code.toByte(),
                't'.code.toByte(),
                'i'.code.toByte(),
                't'.code.toByte(),
                'l'.code.toByte(),
                'e'.code.toByte(),
                0x07,
                'Z'.code.toByte(),
            )

        assertEveryChunking(columns = 8, rows = 4, bytes = bytes) { snapshot ->
            val live = snapshot.liveRowStart
            assertEquals('A'.code, snapshot.retainedRows[live].cells[0].codepoint)
            assertEquals(0xFFFD, snapshot.retainedRows[live].cells[1].codepoint)
            assertEquals('Z'.code, snapshot.retainedRows[live + 2].cells[2].codepoint)
            assertEquals("title", snapshot.windowTitle)
            assertEquals(3, snapshot.cursor.column)
            assertEquals(2, snapshot.cursor.row)
            assertFalse(snapshot.retainedRows.flatMap { it.cells }.any { it.codepoint == 0x1B })
        }
    }

    private fun assertEveryChunking(
        columns: Int,
        rows: Int,
        maxHistory: Int = 8,
        bytes: ByteArray,
        semanticAssertions: (TerminalConformanceSnapshot) -> Unit,
    ) {
        val variants = TerminalReplayChunkings.exhaustive(bytes)
        val reference = replay(columns, rows, maxHistory, variants.first().transcript.events)
        semanticAssertions(reference)
        assertVariantsMatch(reference, columns, rows, maxHistory, variants, emptyList())
    }

    private fun assertVariantsMatch(
        reference: TerminalConformanceSnapshot,
        columns: Int,
        rows: Int,
        maxHistory: Int,
        variants: List<TerminalReplayChunking>,
        suffix: List<TerminalReplayEvent>,
    ) {
        for (variant in variants) {
            val actual = replay(columns, rows, maxHistory, variant.transcript.events + suffix)
            val diff = TerminalConformanceDiffer.compare(reference, actual)
            assertTrue(diff.isEmpty, "chunking=${variant.name}\n${diff.format()}")
        }
    }

    private fun replay(
        columns: Int,
        rows: Int,
        maxHistory: Int,
        events: List<TerminalReplayEvent>,
    ): TerminalConformanceSnapshot =
        TerminalConformanceHarness(columns, rows, maxHistory)
            .replay(TerminalReplayTranscript(events))

    private fun assertCodepoints(
        snapshot: TerminalConformanceSnapshot,
        retainedRow: Int,
        startColumn: Int,
        expected: String,
    ) {
        var column = startColumn
        expected.codePoints().forEach { codepoint ->
            assertEquals(codepoint, snapshot.retainedRows[retainedRow].cells[column].codepoint)
            column++
        }
    }

    private fun assertNoOrphanWideCells(snapshot: TerminalConformanceSnapshot) {
        for ((rowIndex, row) in snapshot.retainedRows.withIndex()) {
            for ((column, cell) in row.cells.withIndex()) {
                if (cell.flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
                    assertTrue(column > 0, "wide trailing cell at row=$rowIndex column=0")
                    assertTrue(
                        row.cells[column - 1].flags and TerminalRenderCellFlags.WIDE_LEADING != 0,
                        "wide trailing cell lacks leader at row=$rowIndex column=$column",
                    )
                }
                if (cell.flags and TerminalRenderCellFlags.WIDE_LEADING != 0) {
                    assertTrue(column + 1 < row.cells.size, "wide leader at right edge row=$rowIndex")
                    assertTrue(
                        row.cells[column + 1].flags and TerminalRenderCellFlags.WIDE_TRAILING != 0,
                        "wide leader lacks trailing cell at row=$rowIndex column=$column",
                    )
                }
            }
        }
    }
}
