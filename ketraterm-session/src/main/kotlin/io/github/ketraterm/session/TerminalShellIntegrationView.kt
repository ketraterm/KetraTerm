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

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only, live projection of the selected shell producer.
 *
 * All reads and registrations are thread-safe; separate calls need not observe the same
 * revision. Primitive copies write only caller-owned destinations. Metadata snapshots
 * allocate only when requested. The producer owns records and clearing; consumers own
 * their listener registrations. This role conveys no publication or producer lifetime authority.
 */
public interface TerminalShellIntegrationView {
    /**
     * Invalidation signal for prompt, command, and current-directory metadata.
     *
     * The initial revision is zero. Changed metadata advances the revision;
     * equal directory/anchor updates and diagnostic observations do not. Values
     * are opaque and updates may be conflated. Read the model after an update;
     * use [addCommandFinishedListener] when every completion must be observed.
     */
    public val revision: StateFlow<Long>

    /**
     * Observes future explicit command completions without replay or conflation.
     *
     * The immutable metadata is captured with the completed record. Callbacks
     * run synchronously on the producer thread, in registration order, outside
     * the storage lock. They must be nonblocking and must not publish lifecycle
     * events reentrantly. Ordinary listener exceptions are logged and isolated;
     * cancellation is rethrown after the remaining listeners are notified.
     * Abandoned commands and unmatched finishes do not produce notifications.
     *
     * Closing the registration is idempotent and prevents future dispatches;
     * an already dispatched callback may finish. Clearing records does not remove
     * listeners. The registration owner must close it when observation ends.
     */
    public fun addCommandFinishedListener(listener: (TerminalShellIntegrationCommandMetadata) -> Unit): AutoCloseable

    /**
     * Observes future changed directory URIs synchronously, without replay.
     *
     * Equal reports do not notify. Callbacks run on the producer thread outside
     * the storage lock, after the new directory is recorded. Delivery and
     * registration lifetime follow [addCommandFinishedListener]: observers must
     * not block or publish lifecycle events reentrantly, ordinary exceptions are
     * isolated, and cancellation is rethrown after the remaining observers run.
     * Use this when the final directory must be captured before session closure;
     * [revision] remains a conflated invalidation signal.
     */
    public fun addCurrentWorkingDirectoryListener(listener: (String) -> Unit): AutoCloseable

    /**
     * Returns the latest recorded directory URI, or `null` before one is recorded.
     *
     * @return current working directory URI for the live shell session.
     */
    public fun currentWorkingDirectoryUri(): String?

    /**
     * Returns true while shell integration reports a command between
     * command-start and command-finished events.
     *
     * Idle prompts, sessions with no shell command information,
     * finished commands, and commands abandoned by a newer prompt all return
     * false. The query is allocation-free and safe for host close-policy checks.
     *
     * @return true when an active foreground command is known to be running.
     */
    public fun hasRunningCommand(): Boolean

    /**
     * Returns the current number of retained shell command records.
     *
     * Records are retained in chronological order from oldest to newest until
     * bounded eviction removes the oldest entries.
     *
     * @return number of retained command timeline records.
     */
    public fun recordCount(): Int

    /**
     * Returns the retained command text for [recordId].
     *
     * Command text is optional metadata captured from the render frame at the
     * command start. `null` means the text was unavailable, ambiguous,
     * too large for the configured bound, or the record has been evicted.
     *
     * @param recordId stable command record id previously exposed by viewport
     *   or record projection APIs.
     * @return captured command text, or `null` when no safe text is retained.
     */
    public fun commandText(recordId: Int): String?

    /**
     * Returns the working-directory URI snapshotted when [recordId] began.
     *
     * @param recordId stable retained command record id.
     * @return command working-directory URI, or `null` when unknown or evicted.
     */
    public fun commandWorkingDirectoryUri(recordId: Int): String?

    /**
     * Returns an immutable metadata snapshot for [recordId].
     *
     * This allocates only when explicitly queried and is not used by viewport
     * projection or painting.
     *
     * @param recordId retained command record id.
     * @return command metadata, or `null` for an unknown, prompt-only, or evicted record.
     */
    public fun commandMetadata(recordId: Int): TerminalShellIntegrationCommandMetadata?

    /**
     * Returns the newest retained command record id, skipping prompt-only records.
     *
     * @return newest command record id, or `0` when no command is retained.
     */
    public fun latestCommandRecordId(): Int

    /**
     * Returns the preferred navigation anchor line for [recordId].
     *
     * Commands with a prompt navigate to the prompt start. Orphan
     * command records navigate to the command-output start. Prompt-only records
     * and evicted records return `0`.
     *
     * @param recordId retained command record id.
     * @return stable line id to reveal, or `0` when unavailable.
     */
    public fun commandAnchorLineId(recordId: Int): Long

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
     * @throws IllegalArgumentException if the destination slice is invalid,
     *   before writing any slots, even when the record has no selectable output.
     */
    public fun copyCommandOutputRange(
        recordId: Int,
        destination: LongArray,
        destinationOffset: Int = 0,
    ): Boolean

    /**
     * Copies the selectable prompt/input-and-output line range for [recordId].
     *
     * The destination layout is defined by
     * [TerminalShellIntegrationCommandBlockRange]. A completed command with a
     * prompt selects from that prompt's visible anchor through its output. A
     * silent command selects its prompt/input only. The next prompt is always
     * excluded. Unfinished, prompt-only, orphan-silent, unknown, and evicted
     * records return `false` and leave the destination unchanged.
     *
     * @param recordId retained command record id.
     * @param destination destination `Long` columns.
     * @param destinationOffset first destination slot.
     * @return true when a complete command block was copied.
     * @throws IllegalArgumentException if the destination slice is invalid,
     *   before writing any slots, even when the record has no selectable block.
     */
    public fun copyCommandBlockRange(
        recordId: Int,
        destination: LongArray,
        destinationOffset: Int = 0,
    ): Boolean

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
    public fun commandRecordIdAtLine(lineId: Long): Int

    /**
     * Returns the previous retained command record before [recordId].
     *
     * Prompt-only records are skipped. If [recordId] is not retained, no
     * neighbor is inferred.
     *
     * @param recordId retained command record id.
     * @return previous command record id, or `0` when none exists.
     */
    public fun previousCommandRecordId(recordId: Int): Int

    /**
     * Returns the next retained command record after [recordId].
     *
     * Prompt-only records are skipped. If [recordId] is not retained, no
     * neighbor is inferred.
     *
     * @param recordId retained command record id.
     * @return next command record id, or `0` when none exists.
     */
    public fun nextCommandRecordId(recordId: Int): Int

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
    public fun previousCommandRecordIdBeforeLine(lineId: Long): Int

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
    public fun nextCommandRecordIdAfterLine(lineId: Long): Int

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
     * @throws IllegalArgumentException if the offset, count, or any destination
     *   slice is invalid, before clearing or writing any destination. Zero-count
     *   slices may start at the end of every destination array.
     */
    public fun copyRecords(
        recordIds: IntArray,
        lifecycleStates: IntArray,
        promptStartLineIds: LongArray,
        promptEndLineIds: LongArray,
        commandStartLineIds: LongArray,
        commandEndLineIds: LongArray,
        exitCodes: IntArray,
        destinationOffset: Int,
        maxRecords: Int,
    ): Int

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
     * @throws IllegalArgumentException if the offset, row count, or any array
     *   slice is invalid, before clearing or writing any destination. Zero-row
     *   slices may start at the end of every destination array.
     */
    public fun copyViewport(
        lineIds: LongArray,
        rowCount: Int,
        promptStarts: BooleanArray,
        commandStarts: BooleanArray,
        commandEnds: BooleanArray,
        commandRecordIds: IntArray,
        commandLifecycleStates: IntArray,
        failedCommandRails: BooleanArray? = null,
        destinationOffset: Int = 0,
    )
}
