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

/** Result of comparing KetraTerm with an independent terminal implementation. */
data class TerminalDifferentialResult(
    /** Exact oracle implementation used for the comparison. */
    val oracle: TerminalOracleIdentity,
    /** Structured differences within the explicitly supported intersection. */
    val differences: List<TerminalConformanceDifference>,
    /** Whether additional differences were omitted after reaching the configured bound. */
    val truncated: Boolean,
) {
    /** Whether both implementations agree on every compared semantic field. */
    val isEmpty: Boolean
        get() = differences.isEmpty()

    /** Formats a deterministic diagnostic suitable for CI failure output. */
    fun format(): String {
        if (differences.isEmpty()) return "No differential mismatches."
        return buildString {
            append("Oracle ")
                .append(oracle.name)
                .append(' ')
                .append(oracle.version)
                .append(':')
                .append('\n')
            for (difference in differences) {
                append("- ")
                    .append(difference.path)
                    .append(": KetraTerm=")
                    .append(difference.expected)
                    .append(", oracle=")
                    .append(difference.actual)
                difference.context?.let { append(" [").append(it).append(']') }
                append('\n')
            }
            if (truncated) append("- additional differences omitted\n")
        }.trimEnd()
    }
}

/**
 * Compares only semantics exposed by both KetraTerm and the public xterm.js API.
 *
 * This deliberately excludes cursor shape/visibility, discarded-history
 * counters, hyperlinks, icon title, and KetraTerm-specific modes. It also
 * captures—but does not yet compare—cell colors and styles until the packed
 * KetraTerm attribute ABI has a dedicated semantic decoder. Missing oracle
 * state is never guessed or replaced with a default.
 */
object TerminalDifferentialComparator {
    /**
     * Compares a KetraTerm snapshot with one independent-oracle snapshot.
     *
     * @param ketraTerm canonical KetraTerm state.
     * @param oracle normalized independent implementation state.
     * @param maxDifferences maximum number of differences retained in the result.
     * @return bounded structural comparison result.
     */
    @JvmStatic
    @JvmOverloads
    fun compare(
        ketraTerm: TerminalConformanceSnapshot,
        oracle: TerminalOracleSnapshot,
        maxDifferences: Int = 32,
    ): TerminalDifferentialResult {
        require(maxDifferences > 0) { "maxDifferences must be > 0, was $maxDifferences" }
        val collector = DifferenceCollector(maxDifferences)
        collector.add("columns", ketraTerm.columns, oracle.columns)
        collector.add("visibleRows", ketraTerm.visibleRows, oracle.visibleRows)
        collector.add("historyRows", ketraTerm.historyRows, oracle.historyRows)
        collector.add("liveRowStart", ketraTerm.liveRowStart, oracle.liveRowStart)
        collector.add("activeBuffer", ketraTerm.activeBuffer.name, oracle.activeBuffer.toBufferName())
        // xterm.js exposes a pending-wrap sentinel at column == columns;
        // KetraTerm exposes the occupied rightmost grid cell for the same state.
        collector.add("cursor.column", ketraTerm.cursor.column, minOf(oracle.cursorColumn, oracle.columns - 1))
        collector.add("cursor.row", ketraTerm.cursor.row, oracle.cursorRow)
        compareModes(ketraTerm.modes, oracle.modes, collector)
        collector.add("windowTitle", ketraTerm.windowTitle, oracle.windowTitle)
        collector.add("outboundBytes", ketraTerm.outboundBytes.toHexString(), oracle.outboundBytes.toHexString())
        compareRows(ketraTerm.retainedRows, oracle.retainedRows, collector)
        return TerminalDifferentialResult(oracle.oracle, collector.differences, collector.truncated)
    }

    private fun compareModes(
        ketraTerm: TerminalModeStateSnapshot,
        oracle: TerminalOracleModeSnapshot,
        collector: DifferenceCollector,
    ) {
        collector.add("modes.insertMode", ketraTerm.insertMode, oracle.insertMode)
        collector.add("modes.autoWrap", ketraTerm.autoWrap, oracle.autoWrap)
        collector.add("modes.applicationCursorKeys", ketraTerm.applicationCursorKeys, oracle.applicationCursorKeys)
        collector.add("modes.applicationKeypad", ketraTerm.applicationKeypad, oracle.applicationKeypad)
        collector.add("modes.originMode", ketraTerm.originMode, oracle.originMode)
        collector.add("modes.bracketedPaste", ketraTerm.bracketedPaste, oracle.bracketedPaste)
        collector.add("modes.focusReporting", ketraTerm.focusReporting, oracle.focusReporting)
        collector.add("modes.mouseTrackingMode", ketraTerm.mouseTrackingMode, oracle.mouseTrackingMode.toModeName())
        collector.add("modes.synchronizedOutput", ketraTerm.synchronizedOutput, oracle.synchronizedOutput)
    }

    private fun compareRows(
        ketraTerm: List<TerminalRowSnapshot>,
        oracle: List<TerminalOracleRowSnapshot>,
        collector: DifferenceCollector,
    ) {
        collector.add("retainedRows.size", ketraTerm.size, oracle.size)
        val rowCount = minOf(ketraTerm.size, oracle.size)
        for (rowIndex in 0 until rowCount) {
            val expectedRow = ketraTerm[rowIndex]
            val actualRow = oracle[rowIndex]
            val context = "row=$rowIndex"
            collector.add("retainedRows[$rowIndex].wrapped", expectedRow.wrapped, actualRow.wrapsToNext, context)
            val cellCount = minOf(expectedRow.cells.size, actualRow.cells.size)
            for (column in 0 until cellCount) {
                val expectedCell = expectedRow.cells[column]
                val actualCell = actualRow.cells[column]
                collector.add(
                    "retainedRows[$rowIndex].cells[$column].text",
                    expectedCell.normalizedText(),
                    actualCell.text,
                    context,
                )
                collector.add(
                    "retainedRows[$rowIndex].cells[$column].width",
                    expectedCell.normalizedWidth(),
                    actualCell.width,
                    context,
                )
            }
        }
    }

    private fun TerminalCellSnapshot.normalizedText(): String =
        when {
            flags and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> ""
            cluster != null -> cluster
            codepoint == 0 -> ""
            else -> Character.toChars(codepoint).concatToString()
        }

    private fun TerminalCellSnapshot.normalizedWidth(): Int =
        when {
            flags and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> 0
            flags and TerminalRenderCellFlags.WIDE_LEADING != 0 -> 2
            else -> 1
        }

    private fun String.toBufferName(): String =
        when (this) {
            "normal" -> TerminalSnapshotBufferKind.PRIMARY.name
            "alternate" -> TerminalSnapshotBufferKind.ALTERNATE.name
            else -> this
        }

    private fun String.toModeName(): String =
        when (this) {
            "none" -> "OFF"
            "x10" -> "X10"
            "vt200" -> "NORMAL"
            "drag" -> "BUTTON_EVENT"
            "any" -> "ANY_EVENT"
            else -> this
        }

    private class DifferenceCollector(
        private val limit: Int,
    ) {
        val differences = ArrayList<TerminalConformanceDifference>(limit)
        var truncated: Boolean = false
            private set

        fun add(
            path: String,
            expected: Any?,
            actual: Any?,
            context: String? = null,
        ) {
            if (expected == actual) return
            if (differences.size == limit) {
                truncated = true
                return
            }
            differences += TerminalConformanceDifference(path, expected.toString(), actual.toString(), context)
        }
    }
}
