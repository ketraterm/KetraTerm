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
package io.github.jvterm.session

/**
 * Primitive command-record id vocabulary used by shell-integration viewport projections.
 */
object TerminalShellIntegrationCommandRecord {
    /**
     * No command record is associated with the projected row.
     */
    const val NONE: Int = 0
}

/**
 * Primitive lifecycle vocabulary for session-owned shell command records.
 *
 * These values are intentionally `Int` constants rather than enum instances so
 * viewport projections can use reusable primitive arrays with no per-row
 * allocation.
 */
object TerminalShellIntegrationCommandLifecycle {
    /**
     * No command lifecycle is associated with the projected row.
     */
    const val NONE: Int = 0

    /**
     * A prompt marker was observed, but no command start has attached to it.
     */
    const val PROMPT_ONLY: Int = 1

    /**
     * A command start marker was observed and no command finish has arrived yet.
     */
    const val RUNNING: Int = 2

    /**
     * A command finished with exit code zero.
     */
    const val SUCCEEDED: Int = 3

    /**
     * A command finished with a non-zero exit code.
     */
    const val FAILED: Int = 4

    /**
     * A command finished without a known exit code.
     */
    const val FINISHED_UNKNOWN: Int = 5

    /**
     * A newer prompt or command marker superseded an unfinished command.
     */
    const val ABANDONED: Int = 6
}

/**
 * Session-owned OSC 133 shell command timeline.
 *
 * This model intentionally lives outside terminal core. Core owns bytes already
 * committed to the terminal grid; shell integration markers are host metadata
 * about prompt and command lifecycle. Records are anchored to stable core line
 * identities, not absolute row numbers, so scrollback movement and resize
 * reflow do not detach decorations from the content they describe.
 *
 * Storage is data-oriented: each command field is a primitive column, records
 * are append-only until bounded eviction, and paint paths consume only projected
 * primitive arrays. All methods are thread-safe. Writers are normally invoked
 * from the serialized session parser path; readers are UI threads that snapshot
 * visible rows before painting.
 */
class TerminalShellIntegrationState(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val lock = Any()
    private val promptStartLineIds = LongArray(capacity) { NO_LINE_ID }
    private val promptEndLineIds = LongArray(capacity) { NO_LINE_ID }
    private val commandStartLineIds = LongArray(capacity) { NO_LINE_ID }
    private val commandEndLineIds = LongArray(capacity) { NO_LINE_ID }
    private val exitCodes = IntArray(capacity) { NO_EXIT_CODE }
    private val recordIds = IntArray(capacity)
    private val lifecycles = IntArray(capacity)
    private val flags = IntArray(capacity)

    private var count = 0
    private var activePromptIndex = NO_INDEX
    private var activeCommandIndex = NO_INDEX
    private var nextRecordId = 1
    private var lastObservedBottomRow = NO_OBSERVED_ROW

    /**
     * Records the start of a shell prompt.
     *
     * @param lineId stable render line identity where the prompt begins.
     */
    fun recordPromptStart(lineId: Long) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            abandonActiveCommandLocked()
            activeCommandIndex = NO_INDEX
            val index = appendCommandLocked()
            promptStartLineIds[index] = lineId
            lifecycles[index] = TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY
            activePromptIndex = index
        }
    }

    /**
     * Records the end of the active shell prompt.
     *
     * @param lineId stable render line identity where prompt printing ended.
     */
    fun recordPromptEnd(lineId: Long) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val index = activePromptIndex
            if (index == NO_INDEX) return

            promptEndLineIds[index] = lineId
        }
    }

    /**
     * Records command execution start.
     *
     * If no prompt record is active, an orphan command record is created so
     * the lifecycle remains represented without inventing a prompt marker.
     *
     * @param lineId stable render line identity where command output begins.
     * @param includeLine whether [lineId] itself belongs to command output.
     */
    fun recordCommandStart(
        lineId: Long,
        includeLine: Boolean,
    ) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val index = attachablePromptIndexLocked()
            if (activeCommandIndex != NO_INDEX && activeCommandIndex != index) {
                abandonActiveCommandLocked()
            }
            commandStartLineIds[index] = lineId
            commandEndLineIds[index] = NO_LINE_ID
            exitCodes[index] = NO_EXIT_CODE
            lifecycles[index] = TerminalShellIntegrationCommandLifecycle.RUNNING
            flags[index] =
                if (includeLine) flags[index] or FLAG_COMMAND_START_INCLUSIVE else flags[index] and FLAG_COMMAND_START_INCLUSIVE.inv()
            activeCommandIndex = index
        }
    }

    /**
     * Records command completion.
     *
     * A non-zero [exitCode] becomes a failed command range only when a
     * matching command-start marker was observed. Omitted or malformed exit
     * status remains `null` at the protocol layer and is stored as unknown.
     *
     * @param lineId stable render line identity where command completion was observed.
     * @param exitCode shell-reported exit code, or null if omitted/malformed.
     */
    fun recordCommandFinished(
        lineId: Long,
        exitCode: Int?,
    ) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val index = activeCommandIndex
            activeCommandIndex = NO_INDEX
            if (index == NO_INDEX || commandStartLineIds[index] == NO_LINE_ID) return

            commandEndLineIds[index] = lineId
            exitCodes[index] = exitCode ?: NO_EXIT_CODE
            lifecycles[index] = lifecycleForExitCode(exitCode)
        }
    }

    /**
     * Observes the newest live viewport bottom row.
     *
     * Shell decorations are anchored by line identity, so resize/reflow and
     * history rewrites naturally stop projecting stale records when their
     * source lines disappear. The bottom row is retained only as diagnostic
     * session state and deliberately does not clear records on row-number
     * regressions.
     *
     * @param bottomAbsoluteRow absolute row of the live viewport bottom.
     */
    fun observeLiveBottomRow(bottomAbsoluteRow: Long) {
        require(bottomAbsoluteRow >= 0) { "bottomAbsoluteRow must be >= 0, was $bottomAbsoluteRow" }
        synchronized(lock) {
            lastObservedBottomRow = bottomAbsoluteRow
        }
    }

    /**
     * Returns whether [lineId] has a prompt divider.
     *
     * @param lineId stable render line identity to query.
     * @return true when a prompt-start marker is anchored to the line.
     */
    fun hasPromptDividerAtLine(lineId: Long): Boolean {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            var index = 0
            while (index < count) {
                if (promptStartLineIds[index] == lineId) return true
                index++
            }
            return false
        }
    }

    /**
     * Returns whether [lineId] belongs to a failed command range.
     *
     * @param lineId stable render line identity to query.
     * @return true when the line is within a completed non-zero command range.
     */
    fun hasFailedCommandRailAtLine(lineId: Long): Boolean {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            return failedCommandIndexAtLocked(lineId) != NO_INDEX
        }
    }

    /**
     * Copies projected shell decorations for a visible viewport.
     *
     * Existing values in [promptDividers], [failedCommandRails],
     * [commandStarts], [commandEnds], [commandRecordIds], and
     * [commandLifecycleStates] are
     * overwritten for exactly [rowCount] rows starting at [destinationOffset].
     *
     * @param lineIds stable line identities for visible viewport rows.
     * @param rowCount number of viewport rows to copy.
     * @param promptDividers destination flags for prompt-start dividers.
     * @param failedCommandRails destination flags for failed-command rails.
     * @param commandStarts destination flags for command-output start rows.
     * @param commandEnds destination flags for command-output end rows.
     * @param commandRecordIds destination command-record ids for rows owned by
     *   a projected prompt or command range.
     * @param commandLifecycleStates destination lifecycle states for rows with
     *   a projected command record.
     * @param destinationOffset first destination index in all destination arrays.
     */
    fun copyViewport(
        lineIds: LongArray,
        rowCount: Int,
        promptDividers: BooleanArray,
        failedCommandRails: BooleanArray,
        commandStarts: BooleanArray,
        commandEnds: BooleanArray,
        commandRecordIds: IntArray,
        commandLifecycleStates: IntArray,
        destinationOffset: Int = 0,
    ) {
        require(rowCount >= 0) { "rowCount must be >= 0, was $rowCount" }
        require(rowCount <= lineIds.size) {
            "lineIds is too small for rowCount=$rowCount size=${lineIds.size}"
        }
        require(destinationOffset >= 0) { "destinationOffset must be >= 0, was $destinationOffset" }
        require(destinationOffset + rowCount <= promptDividers.size) {
            "promptDividers is too small for offset=$destinationOffset rowCount=$rowCount size=${promptDividers.size}"
        }
        require(destinationOffset + rowCount <= failedCommandRails.size) {
            "failedCommandRails is too small for offset=$destinationOffset rowCount=$rowCount size=${failedCommandRails.size}"
        }
        require(destinationOffset + rowCount <= commandStarts.size) {
            "commandStarts is too small for offset=$destinationOffset rowCount=$rowCount size=${commandStarts.size}"
        }
        require(destinationOffset + rowCount <= commandEnds.size) {
            "commandEnds is too small for offset=$destinationOffset rowCount=$rowCount size=${commandEnds.size}"
        }
        require(destinationOffset + rowCount <= commandRecordIds.size) {
            "commandRecordIds is too small for offset=$destinationOffset rowCount=$rowCount size=${commandRecordIds.size}"
        }
        require(destinationOffset + rowCount <= commandLifecycleStates.size) {
            "commandLifecycleStates is too small for offset=$destinationOffset rowCount=$rowCount size=${commandLifecycleStates.size}"
        }

        clearViewport(
            promptDividers,
            failedCommandRails,
            commandStarts,
            commandEnds,
            commandRecordIds,
            commandLifecycleStates,
            destinationOffset,
            rowCount,
        )
        if (rowCount == 0) return

        synchronized(lock) {
            var index = 0
            while (index < count) {
                projectPromptDividerLocked(index, lineIds, rowCount, promptDividers, destinationOffset)
                projectFailedCommandRailLocked(index, lineIds, rowCount, failedCommandRails, destinationOffset)
                projectCommandBoundaryLocked(index, lineIds, rowCount, commandStarts, commandEnds, destinationOffset)
                projectCommandRecordLocked(
                    index,
                    lineIds,
                    rowCount,
                    commandRecordIds,
                    commandLifecycleStates,
                    destinationOffset,
                )
                index++
            }
        }
    }

    /**
     * Clears all stored prompt and command timeline records.
     */
    fun clear() {
        synchronized(lock) {
            clearLocked()
            lastObservedBottomRow = NO_OBSERVED_ROW
        }
    }

    private fun appendCommandLocked(): Int {
        if (count == capacity) {
            evictOldestLocked()
        }
        val index = count
        count++
        promptStartLineIds[index] = NO_LINE_ID
        promptEndLineIds[index] = NO_LINE_ID
        commandStartLineIds[index] = NO_LINE_ID
        commandEndLineIds[index] = NO_LINE_ID
        exitCodes[index] = NO_EXIT_CODE
        recordIds[index] = nextRecordIdLocked()
        lifecycles[index] = TerminalShellIntegrationCommandLifecycle.NONE
        flags[index] = 0
        return index
    }

    private fun attachablePromptIndexLocked(): Int {
        val prompt = activePromptIndex
        if (prompt != NO_INDEX && commandStartLineIds[prompt] == NO_LINE_ID) return prompt
        return appendCommandLocked()
    }

    private fun evictOldestLocked() {
        promptStartLineIds.copyInto(promptStartLineIds, destinationOffset = 0, startIndex = 1, endIndex = count)
        promptEndLineIds.copyInto(promptEndLineIds, destinationOffset = 0, startIndex = 1, endIndex = count)
        commandStartLineIds.copyInto(commandStartLineIds, destinationOffset = 0, startIndex = 1, endIndex = count)
        commandEndLineIds.copyInto(commandEndLineIds, destinationOffset = 0, startIndex = 1, endIndex = count)
        exitCodes.copyInto(exitCodes, destinationOffset = 0, startIndex = 1, endIndex = count)
        recordIds.copyInto(recordIds, destinationOffset = 0, startIndex = 1, endIndex = count)
        lifecycles.copyInto(lifecycles, destinationOffset = 0, startIndex = 1, endIndex = count)
        flags.copyInto(flags, destinationOffset = 0, startIndex = 1, endIndex = count)
        count--
        activePromptIndex = shiftIndexAfterEviction(activePromptIndex)
        activeCommandIndex = shiftIndexAfterEviction(activeCommandIndex)
    }

    private fun nextRecordIdLocked(): Int {
        val id = nextRecordId
        nextRecordId = if (nextRecordId == Int.MAX_VALUE) 1 else nextRecordId + 1
        return id
    }

    private fun abandonActiveCommandLocked() {
        val index = activeCommandIndex
        if (index != NO_INDEX && commandEndLineIds[index] == NO_LINE_ID) {
            lifecycles[index] = TerminalShellIntegrationCommandLifecycle.ABANDONED
        }
        activeCommandIndex = NO_INDEX
    }

    private fun shiftIndexAfterEviction(index: Int): Int =
        when (index) {
            NO_INDEX, 0 -> NO_INDEX
            else -> index - 1
        }

    private fun failedCommandIndexAtLocked(lineId: Long): Int {
        var index = 0
        while (index < count) {
            if (isFailedCommandAtLocked(index, lineId)) return index
            index++
        }
        return NO_INDEX
    }

    private fun isFailedCommandAtLocked(
        index: Int,
        lineId: Long,
    ): Boolean {
        if (lifecycles[index] != TerminalShellIntegrationCommandLifecycle.FAILED) return false
        val start = commandStartLineIds[index]
        val end = commandEndLineIds[index]
        if (start == NO_LINE_ID || end == NO_LINE_ID) return false
        return isLineInCommandOutputRange(index, lineId, start, end)
    }

    private fun projectPromptDividerLocked(
        index: Int,
        lineIds: LongArray,
        rowCount: Int,
        promptDividers: BooleanArray,
        destinationOffset: Int,
    ) {
        val promptStart = promptStartLineIds[index]
        if (promptStart == NO_LINE_ID) return
        var row = 0
        while (row < rowCount) {
            if (lineIds[row] == promptStart) {
                promptDividers[destinationOffset + row] = true
                return
            }
            row++
        }
    }

    private fun projectFailedCommandRailLocked(
        index: Int,
        lineIds: LongArray,
        rowCount: Int,
        failedCommandRails: BooleanArray,
        destinationOffset: Int,
    ) {
        if (lifecycles[index] != TerminalShellIntegrationCommandLifecycle.FAILED) return
        val start = commandStartLineIds[index]
        val end = commandEndLineIds[index]
        if (start == NO_LINE_ID || end == NO_LINE_ID) return

        var row = 0
        while (row < rowCount) {
            val lineId = lineIds[row]
            if (isLineInCommandOutputRange(index, lineId, start, end)) {
                failedCommandRails[destinationOffset + row] = true
            }
            row++
        }
    }

    private fun projectCommandBoundaryLocked(
        index: Int,
        lineIds: LongArray,
        rowCount: Int,
        commandStarts: BooleanArray,
        commandEnds: BooleanArray,
        destinationOffset: Int,
    ) {
        val start = commandStartLineIds[index]
        if (start != NO_LINE_ID) {
            projectFirstMatchingRow(lineIds, rowCount, commandStarts, destinationOffset, start)
        }
        val end = commandEndLineIds[index]
        if (end != NO_LINE_ID) {
            projectFirstMatchingRow(lineIds, rowCount, commandEnds, destinationOffset, end)
        }
    }

    private fun projectCommandRecordLocked(
        index: Int,
        lineIds: LongArray,
        rowCount: Int,
        commandRecordIds: IntArray,
        commandLifecycleStates: IntArray,
        destinationOffset: Int,
    ) {
        val lifecycle = lifecycles[index]
        if (lifecycle == TerminalShellIntegrationCommandLifecycle.NONE) return
        val id = recordIds[index]
        if (id == TerminalShellIntegrationCommandRecord.NONE) return

        val promptStart = promptStartLineIds[index]
        if (promptStart != NO_LINE_ID) {
            val promptEnd = promptEndLineIds[index]
            if (promptEnd != NO_LINE_ID) {
                projectLineRange(
                    lineIds,
                    rowCount,
                    commandRecordIds,
                    commandLifecycleStates,
                    destinationOffset,
                    promptStart,
                    promptEnd,
                    id,
                    lifecycle,
                )
            } else {
                projectRecordLine(
                    lineIds,
                    rowCount,
                    commandRecordIds,
                    commandLifecycleStates,
                    destinationOffset,
                    promptStart,
                    id,
                    lifecycle,
                )
            }
        }

        val start = commandStartLineIds[index]
        if (start == NO_LINE_ID) return
        projectRecordLine(lineIds, rowCount, commandRecordIds, commandLifecycleStates, destinationOffset, start, id, lifecycle)
        val end = commandEndLineIds[index]
        if (end == NO_LINE_ID) {
            return
        }

        var row = 0
        while (row < rowCount) {
            val lineId = lineIds[row]
            if (isLineInCommandOutputRange(index, lineId, start, end)) {
                val destinationIndex = destinationOffset + row
                commandRecordIds[destinationIndex] = id
                commandLifecycleStates[destinationIndex] = lifecycle
            }
            row++
        }
    }

    private fun isLineInCommandOutputRange(
        index: Int,
        lineId: Long,
        start: Long,
        end: Long,
    ): Boolean {
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (lineId < first || lineId > last) return false
        return hasFlag(index, FLAG_COMMAND_START_INCLUSIVE) || lineId != start
    }

    private fun hasFlag(
        index: Int,
        flag: Int,
    ): Boolean = flags[index] and flag != 0

    private fun lifecycleForExitCode(exitCode: Int?): Int =
        when {
            exitCode == null -> TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN
            exitCode == 0 -> TerminalShellIntegrationCommandLifecycle.SUCCEEDED
            else -> TerminalShellIntegrationCommandLifecycle.FAILED
        }

    private fun clearLocked() {
        count = 0
        activePromptIndex = NO_INDEX
        activeCommandIndex = NO_INDEX
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 4096
        private const val NO_INDEX = -1
        private const val NO_LINE_ID = 0L
        private const val NO_OBSERVED_ROW = Long.MIN_VALUE
        private const val NO_EXIT_CODE = Int.MIN_VALUE

        private const val FLAG_COMMAND_START_INCLUSIVE = 1 shl 0

        private fun clearViewport(
            promptDividers: BooleanArray,
            failedCommandRails: BooleanArray,
            commandStarts: BooleanArray,
            commandEnds: BooleanArray,
            commandRecordIds: IntArray,
            commandLifecycleStates: IntArray,
            destinationOffset: Int,
            rowCount: Int,
        ) {
            val end = destinationOffset + rowCount
            var index = destinationOffset
            while (index < end) {
                promptDividers[index] = false
                failedCommandRails[index] = false
                commandStarts[index] = false
                commandEnds[index] = false
                commandRecordIds[index] = TerminalShellIntegrationCommandRecord.NONE
                commandLifecycleStates[index] = TerminalShellIntegrationCommandLifecycle.NONE
                index++
            }
        }

        private fun projectFirstMatchingRow(
            lineIds: LongArray,
            rowCount: Int,
            destination: BooleanArray,
            destinationOffset: Int,
            lineId: Long,
        ) {
            var row = 0
            while (row < rowCount) {
                if (lineIds[row] == lineId) {
                    destination[destinationOffset + row] = true
                    return
                }
                row++
            }
        }

        private fun projectRecordLine(
            lineIds: LongArray,
            rowCount: Int,
            commandRecordIds: IntArray,
            commandLifecycleStates: IntArray,
            destinationOffset: Int,
            lineId: Long,
            commandRecordId: Int,
            lifecycle: Int,
        ) {
            var row = 0
            while (row < rowCount) {
                if (lineIds[row] == lineId) {
                    val destinationIndex = destinationOffset + row
                    commandRecordIds[destinationIndex] = commandRecordId
                    commandLifecycleStates[destinationIndex] = lifecycle
                    return
                }
                row++
            }
        }

        private fun projectLineRange(
            lineIds: LongArray,
            rowCount: Int,
            commandRecordIds: IntArray,
            commandLifecycleStates: IntArray,
            destinationOffset: Int,
            startLineId: Long,
            endLineId: Long,
            commandRecordId: Int,
            lifecycle: Int,
        ) {
            val first = minOf(startLineId, endLineId)
            val last = maxOf(startLineId, endLineId)
            var row = 0
            while (row < rowCount) {
                val lineId = lineIds[row]
                if (lineId >= first && lineId <= last) {
                    val destinationIndex = destinationOffset + row
                    commandRecordIds[destinationIndex] = commandRecordId
                    commandLifecycleStates[destinationIndex] = lifecycle
                }
                row++
            }
        }
    }
}
