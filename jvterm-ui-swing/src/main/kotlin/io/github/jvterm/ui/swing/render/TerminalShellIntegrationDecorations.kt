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
package io.github.jvterm.ui.swing.render

/**
 * EDT-confined shell-integration decoration rows consumed by the Swing renderer.
 *
 * The model stores only primitive absolute terminal rows. Hosts translate
 * parsed shell integration events before calling Swing APIs; this class does
 * not parse terminal output and does not retain command text.
 */
internal class TerminalShellIntegrationDecorations(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val promptRows = LongArray(capacity)
    private var promptCount = 0

    private val failedStartRows = LongArray(capacity)
    private val failedEndRows = LongArray(capacity)
    private var failedCount = 0

    private var activeCommandStartRow = NO_ROW

    /**
     * Records a prompt-start marker at [absoluteRow].
     */
    fun recordPromptStart(absoluteRow: Long) {
        addSortedUnique(promptRows, promptCount, absoluteRow)
            .also { promptCount = it }
    }

    /**
     * Records a command-start marker at [absoluteRow].
     */
    fun recordCommandStart(absoluteRow: Long) {
        activeCommandStartRow = absoluteRow
    }

    /**
     * Records command completion and stores a failed-command range when needed.
     */
    fun recordCommandFinished(
        absoluteRow: Long,
        exitCode: Int?,
    ) {
        val startRow = activeCommandStartRow
        activeCommandStartRow = NO_ROW
        if (startRow == NO_ROW || exitCode == null || exitCode == 0) return

        val start = minOf(startRow, absoluteRow)
        val end = maxOf(startRow, absoluteRow)
        addFailedRange(start, end)
    }

    /**
     * Returns `true` when [absoluteRow] should receive a prompt divider.
     */
    fun hasPromptDividerAt(absoluteRow: Long): Boolean = binarySearch(promptRows, promptCount, absoluteRow) >= 0

    /**
     * Returns `true` when [absoluteRow] belongs to a failed command range.
     */
    fun hasFailedCommandRailAt(absoluteRow: Long): Boolean {
        var low = 0
        var high = failedCount - 1
        var candidate = NO_INDEX
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = failedStartRows[middle]
            if (start <= absoluteRow) {
                candidate = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return candidate != NO_INDEX && absoluteRow <= failedEndRows[candidate]
    }

    /**
     * Clears all stored prompt and command decorations.
     */
    fun reset() {
        promptCount = 0
        failedCount = 0
        activeCommandStartRow = NO_ROW
    }

    private fun addFailedRange(
        start: Long,
        end: Long,
    ) {
        if (failedCount > 0 && failedStartRows[failedCount - 1] == start && failedEndRows[failedCount - 1] == end) {
            return
        }
        if (failedCount == capacity) {
            failedStartRows.copyInto(failedStartRows, destinationOffset = 0, startIndex = 1, endIndex = failedCount)
            failedEndRows.copyInto(failedEndRows, destinationOffset = 0, startIndex = 1, endIndex = failedCount)
            failedCount--
        }
        val insertAt = insertionPoint(failedStartRows, failedCount, start)
        if (insertAt < failedCount) {
            failedStartRows.copyInto(failedStartRows, destinationOffset = insertAt + 1, startIndex = insertAt, endIndex = failedCount)
            failedEndRows.copyInto(failedEndRows, destinationOffset = insertAt + 1, startIndex = insertAt, endIndex = failedCount)
        }
        failedStartRows[insertAt] = start
        failedEndRows[insertAt] = end
        failedCount++
    }

    private fun addSortedUnique(
        rows: LongArray,
        count: Int,
        row: Long,
    ): Int {
        val existing = binarySearch(rows, count, row)
        if (existing >= 0) return count

        var nextCount = count
        if (nextCount == capacity) {
            rows.copyInto(rows, destinationOffset = 0, startIndex = 1, endIndex = nextCount)
            nextCount--
        }
        val insertAt = insertionPoint(rows, nextCount, row)
        if (insertAt < nextCount) {
            rows.copyInto(rows, destinationOffset = insertAt + 1, startIndex = insertAt, endIndex = nextCount)
        }
        rows[insertAt] = row
        return nextCount + 1
    }

    private fun binarySearch(
        rows: LongArray,
        count: Int,
        row: Long,
    ): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val value = rows[middle]
            when {
                value < row -> low = middle + 1
                value > row -> high = middle - 1
                else -> return middle
            }
        }
        return NO_INDEX
    }

    private fun insertionPoint(
        rows: LongArray,
        count: Int,
        row: Long,
    ): Int {
        var low = 0
        var high = count
        while (low < high) {
            val middle = (low + high) ushr 1
            if (rows[middle] < row) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 4096
        private const val NO_INDEX = -1
        private const val NO_ROW = Long.MIN_VALUE
    }
}
