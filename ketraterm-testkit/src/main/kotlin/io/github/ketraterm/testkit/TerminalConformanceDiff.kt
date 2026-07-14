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

/**
 * One field-level mismatch between two terminal conformance snapshots.
 *
 * @property path stable structural path to the mismatching value.
 * @property expected readable expected value.
 * @property actual readable actual value.
 * @property context optional bounded row or response context.
 */
data class TerminalConformanceDifference(
    val path: String,
    val expected: String,
    val actual: String,
    val context: String? = null,
)

/**
 * Bounded comparison result for two terminal conformance snapshots.
 *
 * @property differences ordered mismatches, up to the requested comparison limit.
 * @property truncated whether more mismatches existed than were retained.
 */
data class TerminalConformanceDiff(
    val differences: List<TerminalConformanceDifference>,
    val truncated: Boolean,
) {
    /** Whether the compared snapshots are observably equivalent. */
    val isEmpty: Boolean
        get() = differences.isEmpty()

    /**
     * Formats a deterministic multiline failure message.
     *
     * @return readable field paths, values, and available local context.
     */
    fun format(): String {
        if (isEmpty) {
            return "No conformance differences"
        }
        val builder = StringBuilder()
        for ((index, difference) in differences.withIndex()) {
            if (index > 0) {
                builder.append('\n')
            }
            builder
                .append(difference.path)
                .append(": expected=")
                .append(difference.expected)
                .append(" actual=")
                .append(difference.actual)
            if (difference.context != null) {
                builder.append('\n').append("  ").append(difference.context)
            }
        }
        if (truncated) {
            builder.append('\n').append("Additional differences omitted")
        }
        return builder.toString()
    }
}

/** Field-level comparer for canonical terminal conformance snapshots. */
object TerminalConformanceDiffer {
    /**
     * Compares every observable snapshot field in deterministic order.
     *
     * The result is bounded so a badly diverged grid cannot create an
     * unmanageable assertion message. A non-positive [maxDifferences] is
     * rejected rather than silently hiding all diagnostics.
     *
     * @param expected specification or oracle snapshot.
     * @param actual snapshot produced by the terminal under test.
     * @param maxDifferences maximum mismatches retained in the result.
     * @return structured bounded diff.
     */
    @JvmStatic
    @JvmOverloads
    fun compare(
        expected: TerminalConformanceSnapshot,
        actual: TerminalConformanceSnapshot,
        maxDifferences: Int = DEFAULT_MAX_DIFFERENCES,
    ): TerminalConformanceDiff {
        require(maxDifferences > 0) { "maxDifferences must be > 0, was $maxDifferences" }
        val accumulator = DifferenceAccumulator(maxDifferences)

        accumulator.add("columns", expected.columns, actual.columns)
        accumulator.add("visibleRows", expected.visibleRows, actual.visibleRows)
        accumulator.add("historyRows", expected.historyRows, actual.historyRows)
        accumulator.add("discardedRows", expected.discardedRows, actual.discardedRows)
        accumulator.add("liveRowStart", expected.liveRowStart, actual.liveRowStart)
        accumulator.add("activeBuffer", expected.activeBuffer, actual.activeBuffer)
        compareCursor(expected.cursor, actual.cursor, accumulator)
        compareModes(expected.modes, actual.modes, accumulator)
        accumulator.add("windowTitle", expected.windowTitle.quoted(), actual.windowTitle.quoted())
        accumulator.add("iconTitle", expected.iconTitle.quoted(), actual.iconTitle.quoted())
        accumulator.add("activeHyperlinkUri", expected.activeHyperlinkUri.quoted(), actual.activeHyperlinkUri.quoted())
        accumulator.add("activeHyperlinkId", expected.activeHyperlinkId.quoted(), actual.activeHyperlinkId.quoted())
        accumulator.add(
            path = "outboundBytes",
            expected = expected.outboundBytes.toHexString(),
            actual = actual.outboundBytes.toHexString(),
            context = "expectedBytes=${expected.outboundBytes.size} actualBytes=${actual.outboundBytes.size}",
        )
        compareRows(expected.retainedRows, actual.retainedRows, accumulator)

        return TerminalConformanceDiff(accumulator.differences.toList(), accumulator.truncated)
    }

    private fun compareCursor(
        expected: TerminalCursorSnapshot,
        actual: TerminalCursorSnapshot,
        accumulator: DifferenceAccumulator,
    ) {
        accumulator.add("cursor.column", expected.column, actual.column)
        accumulator.add("cursor.row", expected.row, actual.row)
        accumulator.add("cursor.visible", expected.visible, actual.visible)
        accumulator.add("cursor.blinking", expected.blinking, actual.blinking)
        accumulator.add("cursor.shape", expected.shape, actual.shape)
    }

    private fun compareModes(
        expected: TerminalModeStateSnapshot,
        actual: TerminalModeStateSnapshot,
        accumulator: DifferenceAccumulator,
    ) {
        accumulator.add("modes.insertMode", expected.insertMode, actual.insertMode)
        accumulator.add("modes.autoWrap", expected.autoWrap, actual.autoWrap)
        accumulator.add("modes.applicationCursorKeys", expected.applicationCursorKeys, actual.applicationCursorKeys)
        accumulator.add("modes.applicationKeypad", expected.applicationKeypad, actual.applicationKeypad)
        accumulator.add("modes.backarrowKeyModeExplicit", expected.backarrowKeyModeExplicit, actual.backarrowKeyModeExplicit)
        accumulator.add("modes.backarrowKeySendsBackspace", expected.backarrowKeySendsBackspace, actual.backarrowKeySendsBackspace)
        accumulator.add("modes.originMode", expected.originMode, actual.originMode)
        accumulator.add("modes.newLineMode", expected.newLineMode, actual.newLineMode)
        accumulator.add("modes.leftRightMarginMode", expected.leftRightMarginMode, actual.leftRightMarginMode)
        accumulator.add("modes.reverseVideo", expected.reverseVideo, actual.reverseVideo)
        accumulator.add("modes.cursorVisible", expected.cursorVisible, actual.cursorVisible)
        accumulator.add("modes.cursorBlinking", expected.cursorBlinking, actual.cursorBlinking)
        accumulator.add("modes.bracketedPaste", expected.bracketedPaste, actual.bracketedPaste)
        accumulator.add("modes.focusReporting", expected.focusReporting, actual.focusReporting)
        accumulator.add("modes.ambiguousWidthIsWide", expected.ambiguousWidthIsWide, actual.ambiguousWidthIsWide)
        accumulator.add("modes.mouseTrackingMode", expected.mouseTrackingMode, actual.mouseTrackingMode)
        accumulator.add("modes.mouseEncodingMode", expected.mouseEncodingMode, actual.mouseEncodingMode)
        accumulator.add("modes.modifyOtherKeysMode", expected.modifyOtherKeysMode, actual.modifyOtherKeysMode)
        accumulator.add("modes.formatOtherKeysMode", expected.formatOtherKeysMode, actual.formatOtherKeysMode)
        accumulator.add("modes.kittyKeyboardFlags", expected.kittyKeyboardFlags, actual.kittyKeyboardFlags)
        accumulator.add("modes.synchronizedOutput", expected.synchronizedOutput, actual.synchronizedOutput)
        accumulator.add("modes.bellIsUrgent", expected.bellIsUrgent, actual.bellIsUrgent)
        accumulator.add("modes.popOnBell", expected.popOnBell, actual.popOnBell)
    }

    private fun compareRows(
        expected: List<TerminalRowSnapshot>,
        actual: List<TerminalRowSnapshot>,
        accumulator: DifferenceAccumulator,
    ) {
        accumulator.add("retainedRows.size", expected.size, actual.size)
        val commonRows = minOf(expected.size, actual.size)
        for (row in 0 until commonRows) {
            val expectedRow = expected[row]
            val actualRow = actual[row]
            val rowContext =
                "expectedRow=${expectedRow.summary()} actualRow=${actualRow.summary()}"
            accumulator.add(
                "retainedRows[$row].wrapped",
                expectedRow.wrapped,
                actualRow.wrapped,
                rowContext,
            )
            accumulator.add(
                "retainedRows[$row].cells.size",
                expectedRow.cells.size,
                actualRow.cells.size,
                rowContext,
            )
            val commonColumns = minOf(expectedRow.cells.size, actualRow.cells.size)
            for (column in 0 until commonColumns) {
                compareCell(
                    row = row,
                    column = column,
                    expected = expectedRow.cells[column],
                    actual = actualRow.cells[column],
                    context = rowContext,
                    accumulator = accumulator,
                )
            }
        }
    }

    private fun compareCell(
        row: Int,
        column: Int,
        expected: TerminalCellSnapshot,
        actual: TerminalCellSnapshot,
        context: String,
        accumulator: DifferenceAccumulator,
    ) {
        val path = "retainedRows[$row].cells[$column]"
        accumulator.add("$path.codepoint", expected.codepoint.codepointString(), actual.codepoint.codepointString(), context)
        accumulator.add("$path.cluster", expected.cluster.quoted(), actual.cluster.quoted(), context)
        accumulator.add("$path.attributes", expected.attributes.hexWord(), actual.attributes.hexWord(), context)
        accumulator.add("$path.extraAttributes", expected.extraAttributes.hexWord(), actual.extraAttributes.hexWord(), context)
        accumulator.add("$path.flags", expected.flags.hexWord(), actual.flags.hexWord(), context)
        accumulator.add("$path.hyperlinkId", expected.hyperlinkId, actual.hyperlinkId, context)
    }

    private class DifferenceAccumulator(
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
            if (expected == actual) {
                return
            }
            if (differences.size < limit) {
                differences +=
                    TerminalConformanceDifference(
                        path = path,
                        expected = expected.toString(),
                        actual = actual.toString(),
                        context = context,
                    )
            } else {
                truncated = true
            }
        }
    }

    private fun TerminalRowSnapshot.summary(): String {
        val builder = StringBuilder(cells.size + 2)
        builder.append(if (wrapped) "wrap:\"" else "hard:\"")
        for (cell in cells) {
            when {
                cell.cluster != null -> builder.append(cell.cluster)
                cell.codepoint > 0 -> builder.appendCodePoint(cell.codepoint)
                else -> builder.append('.')
            }
        }
        return builder.append('\"').toString()
    }

    private fun Int.codepointString(): String =
        when {
            this == 0 -> "EMPTY"
            this < 0 -> toString()
            else -> "U+${toString(16).uppercase().padStart(4, '0')}"
        }

    private fun Int.hexWord(): String = "0x${toUInt().toString(16).uppercase()}"

    private fun Long.hexWord(): String = "0x${toULong().toString(16).uppercase()}"

    private fun String?.quoted(): String = this?.let { "\"$it\"" } ?: "null"

    private const val DEFAULT_MAX_DIFFERENCES = 32
}
