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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalConformanceDiffTest {
    @Test
    fun `equal snapshots produce an empty diff`() {
        val snapshot = emptySnapshot()

        val diff = TerminalConformanceDiffer.compare(snapshot, snapshot)

        assertTrue(diff.isEmpty)
        assertFalse(diff.truncated)
        assertEquals("No conformance differences", diff.format())
    }

    @Test
    fun `cell mismatches report an exact path and bounded row context`() {
        val expected = emptySnapshot()
        val row = expected.retainedRows[0]
        val cells = row.cells.toMutableList()
        cells[2] = cells[2].copy(codepoint = 'X'.code)
        val rows = expected.retainedRows.toMutableList()
        rows[0] = row.copy(cells = cells)
        val actual = expected.copy(retainedRows = rows)

        val diff = TerminalConformanceDiffer.compare(expected, actual)

        assertFalse(diff.isEmpty)
        assertEquals("retainedRows[0].cells[2].codepoint", diff.differences.single().path)
        assertEquals("EMPTY", diff.differences.single().expected)
        assertEquals("U+0058", diff.differences.single().actual)
        assertTrue(
            diff.differences
                .single()
                .context
                ?.contains("expectedRow=hard:") == true,
        )
        assertTrue(diff.format().contains("retainedRows[0].cells[2].codepoint"))
    }

    @Test
    fun `diff limit retains deterministic leading paths and marks truncation`() {
        val expected = emptySnapshot()
        val actual =
            expected.copy(
                cursor = expected.cursor.copy(column = 2, row = 1),
                modes = expected.modes.copy(autoWrap = !expected.modes.autoWrap),
            )

        val diff = TerminalConformanceDiffer.compare(expected, actual, maxDifferences = 2)

        assertEquals(listOf("cursor.column", "cursor.row"), diff.differences.map { it.path })
        assertTrue(diff.truncated)
        assertTrue(diff.format().endsWith("Additional differences omitted"))
    }

    @Test
    fun `non-positive diff limits are rejected`() {
        val snapshot = emptySnapshot()

        assertThrows(IllegalArgumentException::class.java) {
            TerminalConformanceDiffer.compare(snapshot, snapshot, maxDifferences = 0)
        }
    }

    private fun emptySnapshot(): TerminalConformanceSnapshot =
        TerminalConformanceHarness(columns = 4, rows = 2)
            .replay(TerminalReplayTranscript.of(TerminalReplayEvent.EndOfInput))
}
