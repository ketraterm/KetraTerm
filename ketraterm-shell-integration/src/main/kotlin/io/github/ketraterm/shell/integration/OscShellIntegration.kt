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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderRowSnapshot
import io.github.ketraterm.session.*
import kotlinx.coroutines.flow.*

private const val MAX_SHELL_INTEGRATION_PROMPT_COLUMNS = 4096
private const val SHELL_COMMAND_LINE_CONTEXT_LONGS = 4
private const val SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX = 0
private const val SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX = 1
private const val SHELL_COMMAND_LINE_CONTEXT_ACTIVE_INDEX = 2
private const val SHELL_COMMAND_LINE_CONTEXT_CONFIRMATION_INDEX = 3

/**
 * Optional OSC 7/133 shell integration for a terminal session.
 *
 * Select this factory when the host installs compatible shell hooks. Each
 * session receives its own bounded command timeline, prompt readiness, and
 * command-text extractor. Render observations are tracked while collected;
 * conditional edits start synchronous observation tracking at their first capture.
 * Both paths reuse primitive scratch storage.
 */
public object OscShellIntegration : TerminalShellIntegrationFactory {
    override fun create(context: TerminalShellIntegrationContext): TerminalShellIntegration = OscShellIntegrationSession(context, false)

    /**
     * Selects OSC integration with the host's immutable startup expectation.
     * Set [promptMarkersExpected] only when compatible prompt hooks were configured.
     * False still accepts OSC metadata, including manually installed or nested-shell hooks.
     * The expectation neither enables startup input nor changes protocol handling.
     * Supply [commandInventoryInput] for independently transported PATH snapshots;
     * bind it to one session and let the launching host own transport and cleanup.
     */
    @JvmStatic
    @JvmOverloads
    public fun configured(
        promptMarkersExpected: Boolean,
        commandInventoryInput: ShellCommandInventoryInput? = null,
    ): TerminalShellIntegrationFactory =
        if (commandInventoryInput != null) {
            TerminalShellIntegrationFactory { OscShellIntegrationSession(it, promptMarkersExpected, commandInventoryInput) }
        } else if (promptMarkersExpected) {
            configuredPromptFactory
        } else {
            this
        }

    private val configuredPromptFactory = TerminalShellIntegrationFactory { OscShellIntegrationSession(it, true) }
}

private class OscShellIntegrationSession(
    private val context: TerminalShellIntegrationContext,
    override val promptMarkersExpected: Boolean,
    private val commandInventoryInput: ShellCommandInventoryInput? = null,
) : TerminalShellIntegration {
    private val shellCompletion = NativeCompletionProtocol(context.shellCompletion)

    override val commandLinePrefixResetKeys: List<TerminalKeyEvent> get() = shellCompletion.commandLinePrefixResetKeys

    init {
        commandInventoryInput?.bind(context, shellCompletion)
    }

    override val customOscPayloadLimitBytes: Int get() = NativeCompletionProtocol.MAX_PAYLOAD_BYTES

    override fun observeCustomOsc(
        command: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (shellCompletion.observe(command, payload, offset, length, promptActive = readinessPromptStarted)) {
            activeCommandLineContextRevision++
        }
    }

    override val state = TerminalShellIntegrationState()
    private val mutablePromptReady = MutableStateFlow(false)
    override val promptReady: StateFlow<Boolean> = mutablePromptReady.asStateFlow()
    private var readinessPromptStarted = false
    private val readinessFrameConsumer =
        TerminalRenderFrameConsumer { frame ->
            if (frame.activeBuffer != TerminalRenderBufferKind.PRIMARY) {
                shellCompletion.clearPrompt()
                readinessPromptStarted = false
                mutablePromptReady.value = false
            }
        }

    override val commandLineChanges: Flow<Long> =
        flow {
            val tracker = ShellCommandLineRevisionTracker()
            context.renderGeneration.collect { generation ->
                if (generation >= 0L && tracker.update()) emit(generation)
            }
        }

    override fun observeWorkingDirectory(uri: String) {
        state.recordCurrentWorkingDirectory(uri)
    }

    override fun bufferCleared(buffer: TerminalRenderBufferKind) {
        if (buffer != TerminalRenderBufferKind.PRIMARY) return
        shellCompletion.clearPrompt()
        readinessPromptStarted = false
        mutablePromptReady.value = false
        promptEndLineId = NO_LINE_ID
        promptEndColumn = 0
        promptStartedForCommandText = false
        promptStartLineId = NO_LINE_ID
        promptStartColumn = 0
        activeCommandLineContextRevision++
        state.clear()
    }

    override fun outputProcessed() {
        shellCompletion.outputProcessed()
        if (readinessPromptStarted) context.readRenderFrame(readinessFrameConsumer)
        val tracker = observationTracker
        if (tracker != null) updateObservation(tracker)
    }

    override fun sessionClosed() {
        commandInventoryInput?.close()
        shellCompletion.clear()
    }

    private fun observeReadiness(
        marker: ShellIntegrationMarker,
        primary: Boolean,
    ) {
        if (!primary) {
            shellCompletion.clearPrompt()
            readinessPromptStarted = false
            mutablePromptReady.value = false
            return
        }
        when (marker) {
            ShellIntegrationMarker.PROMPT_START -> {
                shellCompletion.clearPrompt()
                readinessPromptStarted = true
                mutablePromptReady.value = false
            }
            ShellIntegrationMarker.PROMPT_END -> mutablePromptReady.value = readinessPromptStarted
            ShellIntegrationMarker.COMMAND_START, ShellIntegrationMarker.COMMAND_FINISHED -> {
                shellCompletion.clearPrompt()
                readinessPromptStarted = false
                mutablePromptReady.value = false
            }
        }
    }

    private val commandTextExtractor = ShellIntegrationCommandTextExtractor()
    private var promptEndLineId = NO_LINE_ID
    private var promptEndColumn = 0
    private var promptStartedForCommandText = false
    private var promptStartLineId = NO_LINE_ID
    private var promptStartColumn = 0
    private var promptScanCodeWords = IntArray(0)
    private var promptScanAttrWords = LongArray(0)
    private var promptScanFlags = IntArray(0)
    private var activeCommandLineContextRevision = 0L
    private var observationTracker: ShellCommandLineRevisionTracker? = null
    private var observationRevision = 0L

    override val commandLineObservationRevision: Long get() = observationRevision

    override fun observeShellMarker(event: ShellIntegrationEvent) {
        var cursorLineId = NO_LINE_ID
        var previousLineId = NO_LINE_ID
        var cursorColumn = 0
        var commandText: String? = null
        var commandPrompt: TerminalRenderRowSnapshot? = null
        var visiblePromptStartLineId = NO_LINE_ID
        var historySize = 0
        var liveRows = 0
        context.readRenderFrame(scrollbackOffset = 0) { frame ->
            observeReadiness(event.marker, frame.activeBuffer == TerminalRenderBufferKind.PRIMARY)
            historySize = frame.historySize
            liveRows = frame.rows
            val cursor = frame.cursor
            // The visible cursor stays on the last cell during delayed wrap;
            // text boundaries are already one column beyond that cell.
            cursorColumn = cursor.column + if (frame.cursorPendingWrap) 1 else 0
            if (cursor.row in 0 until frame.rows) {
                cursorLineId = frame.lineId(cursor.row)
            }
            if (cursor.row > 0 && cursor.row - 1 < frame.rows) {
                previousLineId = frame.lineId(cursor.row - 1)
            }
            if (event.marker == ShellIntegrationMarker.COMMAND_START) {
                commandPrompt = captureCommandPrompt(frame, cursor.row)
                commandText =
                    commandTextExtractor.extract(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursor.row,
                        cursorColumn = cursorColumn,
                    )
            }
            if (event.marker == ShellIntegrationMarker.PROMPT_END && promptStartedForCommandText) {
                visiblePromptStartLineId =
                    firstRenderedPromptLineId(
                        frame = frame,
                        startLineId = promptStartLineId,
                        startColumn = promptStartColumn,
                        endRow = cursor.row,
                        endColumn = cursorColumn,
                    )
            }
        }

        if (event.marker == ShellIntegrationMarker.COMMAND_START && (commandText == null || commandPrompt == null) && historySize > 0) {
            val historyRows = minOf(historySize, MAX_SHELL_INTEGRATION_COMMAND_ROWS)
            context.readRenderFrame(
                scrollbackOffset = historyRows,
                viewportRows = liveRows + historyRows,
            ) { frame ->
                val cursor = frame.cursor
                if (commandPrompt == null) commandPrompt = captureCommandPrompt(frame, cursor.row)
                commandText =
                    commandTextExtractor.extract(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursor.row,
                        cursorColumn = cursor.column + if (frame.cursorPendingWrap) 1 else 0,
                    )
            }
        }

        when (event.marker) {
            ShellIntegrationMarker.PROMPT_START -> {
                promptEndLineId = NO_LINE_ID
                promptEndColumn = 0
                promptStartedForCommandText = true
                promptStartLineId = cursorLineId
                promptStartColumn = cursorColumn
                recordIfAssigned(cursorLineId, state::recordPromptStart)
            }
            ShellIntegrationMarker.PROMPT_END -> {
                if (cursorLineId != NO_LINE_ID && promptStartedForCommandText) {
                    if (visiblePromptStartLineId != NO_LINE_ID && visiblePromptStartLineId != promptStartLineId) {
                        state.reanchorActivePromptStart(visiblePromptStartLineId)
                    }
                    promptEndLineId = cursorLineId
                    promptEndColumn = cursorColumn
                    state.recordPromptEnd(cursorLineId)
                }
            }
            ShellIntegrationMarker.COMMAND_START -> {
                if (cursorLineId != NO_LINE_ID) {
                    state.recordCommandStart(
                        lineId = cursorLineId,
                        includeLine = cursorColumn == 0,
                        commandText = commandText,
                        workingDirectoryUri = state.currentWorkingDirectoryUri(),
                        prompt = commandPrompt,
                    )
                }
                promptStartedForCommandText = false
            }
            ShellIntegrationMarker.COMMAND_FINISHED -> {
                val finishedLineId =
                    if (cursorColumn == 0 && previousLineId != NO_LINE_ID) {
                        previousLineId
                    } else {
                        cursorLineId
                    }
                if (finishedLineId != NO_LINE_ID) {
                    state.recordCommandFinished(finishedLineId, event.exitCode)
                }
            }
        }
        activeCommandLineContextRevision++
    }

    private fun captureCommandPrompt(
        frame: TerminalRenderFrame,
        cursorRow: Int,
    ): TerminalRenderRowSnapshot? {
        if (frame.activeBuffer != TerminalRenderBufferKind.PRIMARY ||
            promptEndLineId == NO_LINE_ID ||
            frame.columns > MAX_SHELL_INTEGRATION_PROMPT_COLUMNS
        ) {
            return null
        }
        for (row in 0..cursorRow.coerceAtMost(frame.rows - 1)) {
            if (frame.lineId(row) == promptEndLineId) return TerminalRenderRowSnapshot.copyOf(frame, row)
        }
        return null
    }

    private fun copyActiveCommandLineContext(destination: LongArray): Long {
        require(destination.size >= SHELL_COMMAND_LINE_CONTEXT_LONGS) {
            "destination must contain at least $SHELL_COMMAND_LINE_CONTEXT_LONGS longs"
        }
        destination[SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX] = promptEndLineId
        destination[SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX] = promptEndColumn.toLong()
        destination[SHELL_COMMAND_LINE_CONTEXT_CONFIRMATION_INDEX] = if (shellCompletion.canConfirmCommandLine) 1L else 0L
        destination[SHELL_COMMAND_LINE_CONTEXT_ACTIVE_INDEX] =
            if (promptStartedForCommandText && promptEndLineId != NO_LINE_ID) 1L else 0L
        return activeCommandLineContextRevision
    }

    /**
     * Returns the command line currently being edited after the latest prompt
     * end marker.
     *
     * The caller must already hold the session mutation lock because this
     * method reads the render frame directly through the raw render reader. It
     * returns a snapshot at the visible end of the command. With a native
     * completion hook, a visible suffix instead yields an unconfirmed prefix:
     * the shell must prove that suffix is decoration before suggestions or edits.
     *
     * @return active command-line snapshot, or `null` when unavailable.
     */
    override fun activeCommandLine(): TerminalShellCommandLineSnapshot? = context.withTerminalState { activeCommandLineLocked() }

    override fun <T> withCommandLine(action: (Long, TerminalShellCommandLineSnapshot?) -> T): T =
        context.withTerminalState {
            val tracker = observationTracker ?: ShellCommandLineRevisionTracker().also { observationTracker = it }
            updateObservation(tracker)
            action(activeCommandLineContextRevision, activeCommandLineLocked())
        }

    private fun updateObservation(tracker: ShellCommandLineRevisionTracker) {
        if (tracker.updateLive()) observationRevision++
    }

    private fun activeCommandLineLocked(): TerminalShellCommandLineSnapshot? {
        if (!promptStartedForCommandText || promptEndLineId == NO_LINE_ID) return null

        var snapshot: TerminalShellCommandLineSnapshot? = null
        var historySize = 0
        var liveRows = 0
        context.readRenderFrame(scrollbackOffset = 0) { frame ->
            historySize = frame.historySize
            liveRows = frame.rows
            snapshot = activeCommandLineFrom(frame)
        }

        if (snapshot == null && historySize > 0) {
            val historyRows = minOf(historySize, MAX_SHELL_INTEGRATION_COMMAND_ROWS)
            context.readRenderFrame(
                scrollbackOffset = historyRows,
                viewportRows = liveRows + historyRows,
            ) { frame ->
                snapshot = activeCommandLineFrom(frame)
            }
        }

        return snapshot
    }

    private fun activeCommandLineFrom(frame: TerminalRenderFrame): TerminalShellCommandLineSnapshot? {
        val cursor = frame.cursor
        if (cursor.row !in 0 until frame.rows) return null
        val insertionColumn = cursor.column + if (frame.cursorPendingWrap) 1 else 0
        val requiresShellConfirmation = !commandTextExtractor.isCursorAtVisibleLineEnd(frame, cursor.row, insertionColumn)
        if (requiresShellConfirmation && !shellCompletion.canConfirmCommandLine) return null
        val commandText =
            commandTextExtractor.extract(
                frame = frame,
                promptEndLineId = promptEndLineId,
                promptEndColumn = promptEndColumn,
                cursorRow = cursor.row,
                cursorColumn = insertionColumn,
            ) ?: return null
        return TerminalShellCommandLineSnapshot(
            commandText = commandText,
            cursorOffset = commandText.length,
            cursorColumn = cursor.column,
            cursorRow = cursor.row,
            requiresShellConfirmation = requiresShellConfirmation,
        )
    }

    /**
     * Returns the first line containing a rendered prompt cell between OSC 133
     * A and B. Leading structurally blank rows are layout, not useful gutter
     * anchors; when the bounded live frame cannot prove a better anchor, the
     * caller preserves the original marker line.
     */
    private fun firstRenderedPromptLineId(
        frame: TerminalRenderFrame,
        startLineId: Long,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    ): Long {
        if (startLineId == NO_LINE_ID || endRow !in 0 until frame.rows || frame.columns <= 0) return NO_LINE_ID

        var startRow = 0
        while (startRow < frame.rows && frame.lineId(startRow) != startLineId) {
            startRow++
        }
        if (startRow >= frame.rows || startRow > endRow) return NO_LINE_ID

        ensurePromptScanCapacity(frame.columns)
        var row = startRow
        while (row <= endRow) {
            frame.copyLine(
                row = row,
                codeWords = promptScanCodeWords,
                attrWords = promptScanAttrWords,
                flags = promptScanFlags,
            )
            val firstColumn = if (row == startRow) startColumn.coerceIn(0, frame.columns) else 0
            val lastColumn = if (row == endRow) endColumn.coerceIn(0, frame.columns) else frame.columns
            var column = firstColumn
            while (column < lastColumn) {
                val flags = promptScanFlags[column]
                if (flags and PROMPT_CONTENT_FLAGS != 0) return frame.lineId(row)
                column++
            }
            row++
        }
        return NO_LINE_ID
    }

    private fun ensurePromptScanCapacity(columns: Int) {
        if (promptScanCodeWords.size >= columns) return
        promptScanCodeWords = IntArray(columns)
        promptScanAttrWords = LongArray(columns)
        promptScanFlags = IntArray(columns)
    }

    private inline fun recordIfAssigned(
        lineId: Long,
        record: (Long) -> Unit,
    ) {
        if (lineId != NO_LINE_ID) {
            record(lineId)
        }
    }

    private companion object {
        private const val NO_LINE_ID = 0L
        private const val PROMPT_CONTENT_FLAGS = TerminalRenderCellFlags.CODEPOINT + TerminalRenderCellFlags.CLUSTER
    }

    /** Each tracker owns reusable primitive scratch, guarded by its collector or terminal serialization. */
    private inner class ShellCommandLineRevisionTracker {
        private val contextScratch = LongArray(SHELL_COMMAND_LINE_CONTEXT_LONGS)
        private val fingerprintScratch = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
        private val previousFingerprint = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
        private val extractor = ShellIntegrationCommandTextExtractor()
        private var previousFingerprintAvailable = false
        private var previousRequiresShellConfirmation = false
        private var previousCursorRow = 0
        private var previousCursorColumn = 0
        private var previousContextRevision = 0L
        private var contextRevision = 0L
        private var promptEndLineId = NO_LINE_ID
        private var promptEndColumn = 0
        private var active = false
        private var canConfirmCommandLine = false
        private var status = TerminalShellCommandFingerprintStatus.INVALID
        private var cursorRow = 0
        private var cursorColumn = 0
        private var historySize = 0
        private var liveRows = 0
        private var liveViewport = true
        private var contextUnchanged = false
        private val sampleContext: () -> Unit = {
            contextRevision = copyActiveCommandLineContext(contextScratch)
            promptEndLineId = contextScratch[SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX]
            promptEndColumn = contextScratch[SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX].toInt()
            active = contextScratch[SHELL_COMMAND_LINE_CONTEXT_ACTIVE_INDEX] != 0L
            canConfirmCommandLine = contextScratch[SHELL_COMMAND_LINE_CONTEXT_CONFIRMATION_INDEX] != 0L
        }
        private val checkContext: () -> Unit = { contextUnchanged = contextMatches() }
        private val publishedFrameConsumer: (TerminalRenderCache) -> Unit = { frame ->
            liveViewport = frame.scrollbackOffset == 0
            if (liveViewport) {
                historySize = frame.historySize
                liveRows = frame.rows
                cursorRow = frame.cursorRow
                cursorColumn = frame.cursorColumn + if (frame.cursorPendingWrap) 1 else 0
                status =
                    extractor.fingerprint(
                        cache = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursorRow,
                        cursorColumn = cursorColumn,
                        destination = fingerprintScratch,
                        allowVisibleSuffix = canConfirmCommandLine,
                    )
            }
        }
        private val historyFrameConsumer =
            TerminalRenderFrameConsumer { frame ->
                historySize = frame.historySize
                liveRows = frame.rows
                cursorRow = frame.cursor.row
                cursorColumn = frame.cursor.column + if (frame.cursorPendingWrap) 1 else 0
                status =
                    extractor.fingerprint(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursorRow,
                        cursorColumn = cursorColumn,
                        destination = fingerprintScratch,
                        allowVisibleSuffix = canConfirmCommandLine,
                    )
            }

        /** Records every observed transition even without a subscriber or render publication. */
        fun updateLive(): Boolean {
            sampleContext()
            status = TerminalShellCommandFingerprintStatus.INVALID
            cursorRow = 0
            cursorColumn = 0
            historySize = 0
            if (active) {
                context.readRenderFrame(historyFrameConsumer)
                if (status == TerminalShellCommandFingerprintStatus.MISSING_START_LINE && historySize > 0) {
                    val historyRows = minOf(historySize, MAX_SHELL_INTEGRATION_COMMAND_ROWS)
                    context.readRenderFrame(historyRows, liveRows + historyRows, historyFrameConsumer)
                }
            }
            val available =
                active &&
                    (
                        status == TerminalShellCommandFingerprintStatus.COMPLETE ||
                            status == TerminalShellCommandFingerprintStatus.REQUIRES_SHELL_CONFIRMATION
                    )
            return recordFingerprint(available)
        }

        private val readHistory: () -> Unit = {
            contextUnchanged = contextMatches()
            if (contextUnchanged) {
                val historyRows = minOf(historySize, MAX_SHELL_INTEGRATION_COMMAND_ROWS)
                context.readRenderFrame(historyRows, liveRows + historyRows, historyFrameConsumer)
            }
        }

        fun update(): Boolean {
            context.withTerminalState(sampleContext)
            status = TerminalShellCommandFingerprintStatus.INVALID
            cursorRow = 0
            cursorColumn = 0
            historySize = 0
            liveRows = 0
            liveViewport = true
            if (active) {
                context.readPublishedFrame(publishedFrameConsumer)
                if (!liveViewport) return false
                if (status == TerminalShellCommandFingerprintStatus.MISSING_START_LINE && historySize > 0) {
                    context.withTerminalState(readHistory)
                    if (!contextUnchanged) return false
                }
            }
            context.withTerminalState(checkContext)
            val available =
                active &&
                    (
                        status == TerminalShellCommandFingerprintStatus.COMPLETE ||
                            status == TerminalShellCommandFingerprintStatus.REQUIRES_SHELL_CONFIRMATION
                    )
            return contextUnchanged && recordFingerprint(available)
        }

        private fun contextMatches(): Boolean {
            val revision = copyActiveCommandLineContext(contextScratch)
            return revision == contextRevision &&
                contextScratch[SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX] == promptEndLineId &&
                contextScratch[SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX].toInt() == promptEndColumn &&
                (contextScratch[SHELL_COMMAND_LINE_CONTEXT_ACTIVE_INDEX] != 0L) == active &&
                (contextScratch[SHELL_COMMAND_LINE_CONTEXT_CONFIRMATION_INDEX] != 0L) == canConfirmCommandLine
        }

        private fun recordFingerprint(available: Boolean): Boolean {
            val requiresShellConfirmation = status == TerminalShellCommandFingerprintStatus.REQUIRES_SHELL_CONFIRMATION
            val changed =
                if (!available) {
                    previousFingerprintAvailable
                } else {
                    !previousFingerprintAvailable ||
                        previousContextRevision != contextRevision ||
                        previousRequiresShellConfirmation != requiresShellConfirmation ||
                        previousCursorRow != cursorRow ||
                        previousCursorColumn != cursorColumn ||
                        !previousFingerprint.contentEquals(fingerprintScratch)
                }
            if (!changed) return false
            previousFingerprintAvailable = available
            previousRequiresShellConfirmation = requiresShellConfirmation
            if (available) {
                previousContextRevision = contextRevision
                fingerprintScratch.copyInto(previousFingerprint)
                previousCursorRow = cursorRow
                previousCursorColumn = cursorColumn
            }
            return true
        }
    }
}
