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
package io.github.ketraterm.session

/**
 * Primitive command-record id vocabulary used by shell-integration viewport projections.
 */
object TerminalShellIntegrationCommandRecord {
    /**
     * No command record is associated with the projected row.
     */
    const val NONE: Int = 0

    /**
     * No command exit code is known for the record.
     */
    const val UNKNOWN_EXIT_CODE: Int = Int.MIN_VALUE
}

/**
 * Primitive column layout for command-output range copies.
 */
object TerminalShellIntegrationCommandOutputRange {
    /**
     * Required number of `Long` slots for one copied command-output range.
     */
    const val REQUIRED_LONGS: Int = 3

    /**
     * Destination index containing the command-start line id.
     */
    const val START_LINE_ID_INDEX: Int = 0

    /**
     * Destination index containing the command-end line id.
     */
    const val END_LINE_ID_INDEX: Int = 1

    /**
     * Destination index containing `1` when the start line is command output,
     * or `0` when the start line is prompt/input and should be excluded.
     */
    const val START_INCLUSIVE_INDEX: Int = 2
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
 * Immutable metadata snapshot for one retained shell command.
 *
 * Snapshots are intended for event-driven host features such as history
 * persistence. Rendering continues to consume the primitive projection APIs.
 *
 * @property recordId session-local command record identifier.
 * @property lifecycle primitive [TerminalShellIntegrationCommandLifecycle] value.
 * @property commandText captured command text, or `null` when unavailable.
 * @property workingDirectoryUri OSC 7 directory captured at command start, or `null`.
 * @property exitCode shell exit code, or `null` when unknown.
 * @property startedAtEpochMillis wall-clock command-start time.
 * @property finishedAtEpochMillis wall-clock completion time, or `null` while unfinished.
 */
data class TerminalShellIntegrationCommandMetadata(
    val recordId: Int,
    val lifecycle: Int,
    val commandText: String?,
    val workingDirectoryUri: String?,
    val exitCode: Int?,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
)

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
 *
 * @param capacity maximum retained command records before oldest-record eviction.
 * @param maxCommandTextLength maximum retained UTF-16 command-text length per
 *   record; longer extracted text is stored as unknown.
 * @param epochMillis wall-clock source used for command metadata timestamps.
 */
class TerminalShellIntegrationState(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxCommandTextLength: Int = DEFAULT_SHELL_INTEGRATION_COMMAND_TEXT_LENGTH,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        require(maxCommandTextLength >= 0) { "maxCommandTextLength must be >= 0, was $maxCommandTextLength" }
    }

    private val lock = Any()
    private val promptStartLineIds = LongArray(capacity) { NO_LINE_ID }
    private val promptEndLineIds = LongArray(capacity) { NO_LINE_ID }
    private val commandStartLineIds = LongArray(capacity) { NO_LINE_ID }
    private val commandEndLineIds = LongArray(capacity) { NO_LINE_ID }
    private val exitCodes = IntArray(capacity) { TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE }
    private val recordIds = IntArray(capacity)
    private val lifecycles = IntArray(capacity)
    private val flags = IntArray(capacity)
    private val commandStartedAtEpochMillis = LongArray(capacity) { UNKNOWN_TIMESTAMP }
    private val commandFinishedAtEpochMillis = LongArray(capacity) { UNKNOWN_TIMESTAMP }
    private val commandTexts = arrayOfNulls<String>(capacity)
    private val commandWorkingDirectoryUris = arrayOfNulls<String>(capacity)

    private var count = 0
    private var activePromptIndex = NO_INDEX
    private var activeCommandIndex = NO_INDEX
    private var nextRecordId = 1
    private var lastObservedBottomRow = NO_OBSERVED_ROW
    private var currentWorkingDirectory: String? = null

    /**
     * Records the latest host-validated OSC 7 current-working-directory URI.
     *
     * The value is session metadata and remains available when command history
     * is cleared. Command starts snapshot it into their bounded record.
     *
     * @param uri accepted absolute `file://` URI.
     */
    fun recordCurrentWorkingDirectory(uri: String) {
        require(uri.isNotEmpty()) { "uri must not be empty" }
        synchronized(lock) {
            currentWorkingDirectory = uri
        }
    }

    /**
     * Returns the latest accepted OSC 7 URI, or `null` before one is received.
     *
     * @return current working directory URI for the live shell session.
     */
    fun currentWorkingDirectoryUri(): String? =
        synchronized(lock) {
            currentWorkingDirectory
        }

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

    /** Replaces the active prompt's visual start anchor with a proven rendered prompt line. */
    internal fun reanchorActivePromptStart(lineId: Long) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val index = activePromptIndex
            if (index == NO_INDEX) return
            promptStartLineIds[index] = lineId
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
     * @param commandText bounded command text captured between prompt end and command start, or `null` when unknown.
     * @param workingDirectoryUri current-working-directory URI to snapshot for this command, or `null` when unknown.
     */
    fun recordCommandStart(
        lineId: Long,
        includeLine: Boolean,
        commandText: String? = null,
        workingDirectoryUri: String? = null,
    ) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val index = attachablePromptIndexLocked()
            if (activeCommandIndex != NO_INDEX && activeCommandIndex != index) {
                abandonActiveCommandLocked()
            }
            commandStartLineIds[index] = lineId
            commandEndLineIds[index] = NO_LINE_ID
            exitCodes[index] = TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE
            lifecycles[index] = TerminalShellIntegrationCommandLifecycle.RUNNING
            commandTexts[index] = boundedCommandText(commandText)
            commandWorkingDirectoryUris[index] = workingDirectoryUri
            commandStartedAtEpochMillis[index] = epochMillis()
            commandFinishedAtEpochMillis[index] = UNKNOWN_TIMESTAMP
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
            exitCodes[index] = exitCode ?: TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE
            lifecycles[index] = lifecycleForExitCode(exitCode)
            commandFinishedAtEpochMillis[index] = epochMillis()
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
     * Returns whether [lineId] has a prompt-start marker.
     *
     * @param lineId stable render line identity to query.
     * @return true when a prompt-start marker is anchored to the line.
     */
    fun hasPromptStartAtLine(lineId: Long): Boolean {
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
    fun hasFailedCommandOutputAtLine(lineId: Long): Boolean {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            return failedCommandIndexAtLocked(lineId) != NO_INDEX
        }
    }

    /**
     * Returns the current number of retained shell command records.
     *
     * Records are retained in chronological order from oldest to newest until
     * bounded eviction removes the oldest entries.
     *
     * @return number of retained command timeline records.
     */
    fun recordCount(): Int =
        synchronized(lock) {
            count
        }

    /**
     * Returns the retained command text for [recordId].
     *
     * Command text is optional metadata captured from the render frame at the
     * command-start marker. `null` means the text was unavailable, ambiguous,
     * too large for the configured bound, or the record has been evicted.
     *
     * @param recordId stable command record id previously exposed by viewport
     *   or record projection APIs.
     * @return captured command text, or `null` when no safe text is retained.
     */
    fun commandText(recordId: Int): String? {
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return null
        synchronized(lock) {
            var index = 0
            while (index < count) {
                if (recordIds[index] == recordId) return commandTexts[index]
                index++
            }
            return null
        }
    }

    /**
     * Returns the OSC 7 working-directory URI snapshotted when [recordId] began.
     *
     * @param recordId stable retained command record id.
     * @return command working-directory URI, or `null` when unknown or evicted.
     */
    fun commandWorkingDirectoryUri(recordId: Int): String? {
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return null
        synchronized(lock) {
            var index = 0
            while (index < count) {
                if (recordIds[index] == recordId) return commandWorkingDirectoryUris[index]
                index++
            }
            return null
        }
    }

    /**
     * Returns an immutable metadata snapshot for [recordId].
     *
     * This allocates only when explicitly queried and is not used by viewport
     * projection or painting.
     *
     * @param recordId retained command record id.
     * @return command metadata, or `null` for an unknown, prompt-only, or evicted record.
     */
    fun commandMetadata(recordId: Int): TerminalShellIntegrationCommandMetadata? {
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return null
        synchronized(lock) {
            val index = indexForRecordIdLocked(recordId)
            if (index == NO_INDEX || !isCommandRecordLocked(index)) return null
            val startedAt = commandStartedAtEpochMillis[index]
            if (startedAt == UNKNOWN_TIMESTAMP) return null
            val storedExitCode = exitCodes[index]
            val finishedAt = commandFinishedAtEpochMillis[index]
            return TerminalShellIntegrationCommandMetadata(
                recordId = recordIds[index],
                lifecycle = lifecycles[index],
                commandText = commandTexts[index],
                workingDirectoryUri = commandWorkingDirectoryUris[index],
                exitCode =
                    storedExitCode.takeUnless {
                        it == TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE
                    },
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = finishedAt.takeUnless { it == UNKNOWN_TIMESTAMP },
            )
        }
    }

    /**
     * Returns the newest retained command record id, skipping prompt-only records.
     *
     * @return newest command record id, or `0` when no command is retained.
     */
    fun latestCommandRecordId(): Int =
        synchronized(lock) {
            var index = count - 1
            while (index >= 0) {
                if (isCommandRecordLocked(index)) return@synchronized recordIds[index]
                index--
            }
            TerminalShellIntegrationCommandRecord.NONE
        }

    /**
     * Returns the preferred navigation anchor line for [recordId].
     *
     * Commands with a prompt marker navigate to the prompt start. Orphan
     * command records navigate to the command-output start. Prompt-only records
     * and evicted records return `0`.
     *
     * @param recordId retained command record id.
     * @return stable line id to reveal, or `0` when unavailable.
     */
    fun commandAnchorLineId(recordId: Int): Long {
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return NO_LINE_ID
        synchronized(lock) {
            val index = indexForRecordIdLocked(recordId)
            if (index == NO_INDEX || !isCommandRecordLocked(index)) return NO_LINE_ID

            val promptStart = promptStartLineIds[index]
            if (promptStart != NO_LINE_ID) return promptStart
            return commandStartLineIds[index]
        }
    }

    /**
     * Copies the command-output line range for [recordId].
     *
     * The destination layout is defined by
     * [TerminalShellIntegrationCommandOutputRange]. Unfinished commands,
     * prompt-only records, unknown records, and records with no selectable
     * output return `false` and leave the destination unchanged.
     *
     * @param recordId retained command record id.
     * @param destination destination `Long` columns.
     * @param destinationOffset first destination slot.
     * @return true when a complete output range was copied.
     */
    fun copyCommandOutputRange(
        recordId: Int,
        destination: LongArray,
        destinationOffset: Int = 0,
    ): Boolean {
        require(destinationOffset >= 0) { "destinationOffset must be >= 0, was $destinationOffset" }
        require(destinationOffset + TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS <= destination.size) {
            "destination is too small for offset=$destinationOffset size=${destination.size}"
        }
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false

        synchronized(lock) {
            val index = indexForRecordIdLocked(recordId)
            if (index == NO_INDEX || !isCommandRecordLocked(index)) return false

            val start = commandStartLineIds[index]
            val end = commandEndLineIds[index]
            if (start == NO_LINE_ID || end == NO_LINE_ID) return false
            if (!hasFlag(index, FLAG_COMMAND_START_INCLUSIVE) && start == end) return false

            destination[destinationOffset + TerminalShellIntegrationCommandOutputRange.START_LINE_ID_INDEX] = start
            destination[destinationOffset + TerminalShellIntegrationCommandOutputRange.END_LINE_ID_INDEX] = end
            destination[destinationOffset + TerminalShellIntegrationCommandOutputRange.START_INCLUSIVE_INDEX] =
                if (hasFlag(index, FLAG_COMMAND_START_INCLUSIVE)) 1L else 0L
            return true
        }
    }

    /**
     * Returns the retained command record that owns [lineId].
     *
     * Ownership uses the same prompt and command range rules as viewport
     * projection. Prompt-only records are not considered commands and return
     * [TerminalShellIntegrationCommandRecord.NONE].
     *
     * @param lineId stable render line identity to query.
     * @return owning command record id, or `0` when no command owns the line.
     */
    fun commandRecordIdAtLine(lineId: Long): Int {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val index = commandIndexAtLineLocked(lineId)
            return if (index == NO_INDEX) TerminalShellIntegrationCommandRecord.NONE else recordIds[index]
        }
    }

    /**
     * Returns the previous retained command record before [recordId].
     *
     * Prompt-only records are skipped. If [recordId] is not retained, no
     * neighbor is inferred.
     *
     * @param recordId retained command record id.
     * @return previous command record id, or `0` when none exists.
     */
    fun previousCommandRecordId(recordId: Int): Int {
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return TerminalShellIntegrationCommandRecord.NONE
        synchronized(lock) {
            val index = indexForRecordIdLocked(recordId)
            if (index == NO_INDEX) return TerminalShellIntegrationCommandRecord.NONE

            var candidate = index - 1
            while (candidate >= 0) {
                if (isCommandRecordLocked(candidate)) return recordIds[candidate]
                candidate--
            }
            return TerminalShellIntegrationCommandRecord.NONE
        }
    }

    /**
     * Returns the next retained command record after [recordId].
     *
     * Prompt-only records are skipped. If [recordId] is not retained, no
     * neighbor is inferred.
     *
     * @param recordId retained command record id.
     * @return next command record id, or `0` when none exists.
     */
    fun nextCommandRecordId(recordId: Int): Int {
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return TerminalShellIntegrationCommandRecord.NONE
        synchronized(lock) {
            val index = indexForRecordIdLocked(recordId)
            if (index == NO_INDEX) return TerminalShellIntegrationCommandRecord.NONE

            var candidate = index + 1
            while (candidate < count) {
                if (isCommandRecordLocked(candidate)) return recordIds[candidate]
                candidate++
            }
            return TerminalShellIntegrationCommandRecord.NONE
        }
    }

    /**
     * Returns the nearest retained command record before [lineId].
     *
     * If [lineId] is inside a command record, the previous command before that
     * record is returned. If no command owns [lineId], the newest command whose
     * start line is before [lineId] is returned.
     *
     * @param lineId stable render line identity used as the navigation anchor.
     * @return previous command record id, or `0` when none exists.
     */
    fun previousCommandRecordIdBeforeLine(lineId: Long): Int {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val owner = commandIndexAtLineLocked(lineId)
            var index = if (owner == NO_INDEX) count - 1 else owner - 1
            while (index >= 0) {
                if (isCommandRecordLocked(index) && commandStartLineIds[index] < lineId) return recordIds[index]
                index--
            }
            return TerminalShellIntegrationCommandRecord.NONE
        }
    }

    /**
     * Returns the nearest retained command record after [lineId].
     *
     * If [lineId] is inside a command record, the next command after that
     * record is returned. If no command owns [lineId], the oldest command whose
     * start line is after [lineId] is returned.
     *
     * @param lineId stable render line identity used as the navigation anchor.
     * @return next command record id, or `0` when none exists.
     */
    fun nextCommandRecordIdAfterLine(lineId: Long): Int {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        synchronized(lock) {
            val owner = commandIndexAtLineLocked(lineId)
            var index = if (owner == NO_INDEX) 0 else owner + 1
            while (index < count) {
                if (isCommandRecordLocked(index) && commandStartLineIds[index] > lineId) return recordIds[index]
                index++
            }
            return TerminalShellIntegrationCommandRecord.NONE
        }
    }

    /**
     * Copies retained shell command records into caller-owned primitive arrays.
     *
     * Records are copied in chronological order, oldest first. This method
     * clears exactly [maxRecords] destination slots starting at
     * [destinationOffset] before copying, so callers can safely reuse
     * destination buffers across calls without retaining stale records. Exit
     * codes use [TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE] when
     * omitted, malformed, not finished, or otherwise unknown.
     *
     * @param recordIds destination record-id column.
     * @param lifecycleStates destination lifecycle-state column.
     * @param promptStartLineIds destination prompt-start line-id column.
     * @param promptEndLineIds destination prompt-end line-id column.
     * @param commandStartLineIds destination command-start line-id column.
     * @param commandEndLineIds destination command-end line-id column.
     * @param exitCodes destination exit-code column.
     * @param destinationOffset first destination index in all destination arrays.
     * @param maxRecords maximum number of destination records to clear and copy.
     * @return number of actual records copied.
     */
    fun copyRecords(
        recordIds: IntArray,
        lifecycleStates: IntArray,
        promptStartLineIds: LongArray,
        promptEndLineIds: LongArray,
        commandStartLineIds: LongArray,
        commandEndLineIds: LongArray,
        exitCodes: IntArray,
        destinationOffset: Int,
        maxRecords: Int,
    ): Int {
        require(destinationOffset >= 0) { "destinationOffset must be >= 0, was $destinationOffset" }
        require(maxRecords >= 0) { "maxRecords must be >= 0, was $maxRecords" }
        require(destinationOffset + maxRecords <= recordIds.size) {
            "recordIds is too small for offset=$destinationOffset maxRecords=$maxRecords size=${recordIds.size}"
        }
        require(destinationOffset + maxRecords <= lifecycleStates.size) {
            "lifecycleStates is too small for offset=$destinationOffset maxRecords=$maxRecords size=${lifecycleStates.size}"
        }
        require(destinationOffset + maxRecords <= promptStartLineIds.size) {
            "promptStartLineIds is too small for offset=$destinationOffset maxRecords=$maxRecords size=${promptStartLineIds.size}"
        }
        require(destinationOffset + maxRecords <= promptEndLineIds.size) {
            "promptEndLineIds is too small for offset=$destinationOffset maxRecords=$maxRecords size=${promptEndLineIds.size}"
        }
        require(destinationOffset + maxRecords <= commandStartLineIds.size) {
            "commandStartLineIds is too small for offset=$destinationOffset maxRecords=$maxRecords size=${commandStartLineIds.size}"
        }
        require(destinationOffset + maxRecords <= commandEndLineIds.size) {
            "commandEndLineIds is too small for offset=$destinationOffset maxRecords=$maxRecords size=${commandEndLineIds.size}"
        }
        require(destinationOffset + maxRecords <= exitCodes.size) {
            "exitCodes is too small for offset=$destinationOffset maxRecords=$maxRecords size=${exitCodes.size}"
        }

        clearRecords(
            recordIds,
            lifecycleStates,
            promptStartLineIds,
            promptEndLineIds,
            commandStartLineIds,
            commandEndLineIds,
            exitCodes,
            destinationOffset,
            maxRecords,
        )
        if (maxRecords == 0) return 0

        synchronized(lock) {
            val copied = minOf(count, maxRecords)
            var index = 0
            while (index < copied) {
                val destinationIndex = destinationOffset + index
                recordIds[destinationIndex] = this.recordIds[index]
                lifecycleStates[destinationIndex] = lifecycles[index]
                promptStartLineIds[destinationIndex] = this.promptStartLineIds[index]
                promptEndLineIds[destinationIndex] = this.promptEndLineIds[index]
                commandStartLineIds[destinationIndex] = this.commandStartLineIds[index]
                commandEndLineIds[destinationIndex] = this.commandEndLineIds[index]
                exitCodes[destinationIndex] = this.exitCodes[index]
                index++
            }
            return copied
        }
    }

    /**
     * Copies projected shell decorations for a visible viewport.
     *
     * Existing values in [promptStarts], [commandStarts], [commandEnds],
     * [commandRecordIds], and [commandLifecycleStates] are
     * overwritten for exactly [rowCount] rows starting at [destinationOffset].
     *
     * @param lineIds stable line identities for visible viewport rows.
     * @param rowCount number of viewport rows to copy.
     * @param promptStarts destination flags for prompt-start rows.
     * @param commandStarts destination flags for command-output start rows.
     * @param commandEnds destination flags for command-output end rows.
     * @param commandRecordIds destination command-record ids for rows owned by
     *   a projected prompt or command range.
     * @param commandLifecycleStates destination lifecycle states for rows with
     *   a projected command record.
     * @param failedCommandRails optional destination flags for failed-command output rows.
     * @param destinationOffset first destination index in all destination arrays.
     */
    fun copyViewport(
        lineIds: LongArray,
        rowCount: Int,
        promptStarts: BooleanArray,
        commandStarts: BooleanArray,
        commandEnds: BooleanArray,
        commandRecordIds: IntArray,
        commandLifecycleStates: IntArray,
        failedCommandRails: BooleanArray? = null,
        destinationOffset: Int = 0,
    ) {
        require(rowCount >= 0) { "rowCount must be >= 0, was $rowCount" }
        require(rowCount <= lineIds.size) {
            "lineIds is too small for rowCount=$rowCount size=${lineIds.size}"
        }
        require(destinationOffset >= 0) { "destinationOffset must be >= 0, was $destinationOffset" }
        require(destinationOffset + rowCount <= promptStarts.size) {
            "promptStarts is too small for offset=$destinationOffset rowCount=$rowCount size=${promptStarts.size}"
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
        require(failedCommandRails == null || destinationOffset + rowCount <= failedCommandRails.size) {
            "failedCommandRails is too small for offset=$destinationOffset rowCount=$rowCount size=${failedCommandRails?.size}"
        }

        clearViewport(
            promptStarts,
            commandStarts,
            commandEnds,
            commandRecordIds,
            commandLifecycleStates,
            failedCommandRails,
            destinationOffset,
            rowCount,
        )
        if (rowCount == 0) return

        synchronized(lock) {
            var index = 0
            while (index < count) {
                projectPromptStartLocked(index, lineIds, rowCount, promptStarts, destinationOffset)
                if (failedCommandRails != null) {
                    projectFailedCommandRailLocked(index, lineIds, rowCount, failedCommandRails, destinationOffset)
                }
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
        exitCodes[index] = TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE
        recordIds[index] = nextRecordIdLocked()
        lifecycles[index] = TerminalShellIntegrationCommandLifecycle.NONE
        flags[index] = 0
        commandStartedAtEpochMillis[index] = UNKNOWN_TIMESTAMP
        commandFinishedAtEpochMillis[index] = UNKNOWN_TIMESTAMP
        commandTexts[index] = null
        commandWorkingDirectoryUris[index] = null
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
        commandStartedAtEpochMillis.copyInto(commandStartedAtEpochMillis, destinationOffset = 0, startIndex = 1, endIndex = count)
        commandFinishedAtEpochMillis.copyInto(commandFinishedAtEpochMillis, destinationOffset = 0, startIndex = 1, endIndex = count)
        commandTexts.copyInto(commandTexts, destinationOffset = 0, startIndex = 1, endIndex = count)
        commandWorkingDirectoryUris.copyInto(
            commandWorkingDirectoryUris,
            destinationOffset = 0,
            startIndex = 1,
            endIndex = count,
        )
        count--
        commandTexts[count] = null
        commandWorkingDirectoryUris[count] = null
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
            commandFinishedAtEpochMillis[index] = epochMillis()
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

    private fun commandIndexAtLineLocked(lineId: Long): Int {
        var index = 0
        while (index < count) {
            if (isCommandRecordAtLineLocked(index, lineId)) return index
            index++
        }
        return NO_INDEX
    }

    private fun isCommandRecordAtLineLocked(
        index: Int,
        lineId: Long,
    ): Boolean {
        if (!isCommandRecordLocked(index)) return false

        val promptStart = promptStartLineIds[index]
        if (promptStart != NO_LINE_ID) {
            val promptEnd = promptEndLineIds[index]
            if (promptEnd != NO_LINE_ID) {
                val first = minOf(promptStart, promptEnd)
                val last = maxOf(promptStart, promptEnd)
                if (lineId >= first && lineId <= last) return true
            } else if (lineId == promptStart) {
                return true
            }
        }

        val start = commandStartLineIds[index]
        if (lineId == start) return true
        val end = commandEndLineIds[index]
        return end != NO_LINE_ID && isLineInCommandOutputRange(index, lineId, start, end)
    }

    private fun indexForRecordIdLocked(recordId: Int): Int {
        var index = 0
        while (index < count) {
            if (recordIds[index] == recordId) return index
            index++
        }
        return NO_INDEX
    }

    private fun isCommandRecordLocked(index: Int): Boolean = commandStartLineIds[index] != NO_LINE_ID

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

    private fun projectPromptStartLocked(
        index: Int,
        lineIds: LongArray,
        rowCount: Int,
        promptStarts: BooleanArray,
        destinationOffset: Int,
    ) {
        val promptStart = promptStartLineIds[index]
        if (promptStart == NO_LINE_ID) return
        var row = 0
        while (row < rowCount) {
            if (lineIds[row] == promptStart) {
                promptStarts[destinationOffset + row] = true
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
        if (end == NO_LINE_ID) return

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
        var i = 0
        while (i < count) {
            if (promptStartLineIds[i] == lineId) return false
            i++
        }
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

    private fun boundedCommandText(commandText: String?): String? =
        when {
            commandText == null -> null
            commandText.length <= maxCommandTextLength -> commandText
            else -> null
        }

    private fun clearLocked() {
        var index = 0
        while (index < count) {
            commandTexts[index] = null
            commandWorkingDirectoryUris[index] = null
            index++
        }
        count = 0
        activePromptIndex = NO_INDEX
        activeCommandIndex = NO_INDEX
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 4096
        private const val NO_INDEX = -1
        private const val NO_LINE_ID = 0L
        private const val NO_OBSERVED_ROW = Long.MIN_VALUE
        private const val UNKNOWN_TIMESTAMP = Long.MIN_VALUE
        private const val FLAG_COMMAND_START_INCLUSIVE = 1 shl 0

        private fun clearViewport(
            promptStarts: BooleanArray,
            commandStarts: BooleanArray,
            commandEnds: BooleanArray,
            commandRecordIds: IntArray,
            commandLifecycleStates: IntArray,
            failedCommandRails: BooleanArray?,
            destinationOffset: Int,
            rowCount: Int,
        ) {
            val end = destinationOffset + rowCount
            var index = destinationOffset
            while (index < end) {
                promptStarts[index] = false
                commandStarts[index] = false
                commandEnds[index] = false
                commandRecordIds[index] = TerminalShellIntegrationCommandRecord.NONE
                commandLifecycleStates[index] = TerminalShellIntegrationCommandLifecycle.NONE
                if (failedCommandRails != null) failedCommandRails[index] = false
                index++
            }
        }

        private fun clearRecords(
            recordIds: IntArray,
            lifecycleStates: IntArray,
            promptStartLineIds: LongArray,
            promptEndLineIds: LongArray,
            commandStartLineIds: LongArray,
            commandEndLineIds: LongArray,
            exitCodes: IntArray,
            destinationOffset: Int,
            maxRecords: Int,
        ) {
            val end = destinationOffset + maxRecords
            var index = destinationOffset
            while (index < end) {
                recordIds[index] = TerminalShellIntegrationCommandRecord.NONE
                lifecycleStates[index] = TerminalShellIntegrationCommandLifecycle.NONE
                promptStartLineIds[index] = NO_LINE_ID
                promptEndLineIds[index] = NO_LINE_ID
                commandStartLineIds[index] = NO_LINE_ID
                commandEndLineIds[index] = NO_LINE_ID
                exitCodes[index] = TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE
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
