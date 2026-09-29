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

import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.*
import kotlinx.coroutines.flow.*

private const val SHELL_COMMAND_LINE_CONTEXT_LONGS = 3
private const val SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX = 0
private const val SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX = 1
private const val SHELL_COMMAND_LINE_CONTEXT_ACTIVE_INDEX = 2

/**
 * Optional OSC 7/133 shell integration for a terminal session.
 *
 * Select this factory when the host installs compatible shell hooks. Each
 * session receives its own bounded command timeline, prompt readiness, and
 * command-text extractor. Active editing revisions are tracked only while
 * collected and reuse primitive scratch storage between render publications.
 */
object OscShellIntegration : TerminalShellIntegrationFactory {
    override fun create(context: TerminalShellIntegrationContext): TerminalShellIntegration = OscShellIntegrationSession(context)
}

private class OscShellIntegrationSession(
    private val context: TerminalShellIntegrationContext,
) : TerminalShellIntegration {
    override val state = TerminalShellIntegrationState()
    private val mutablePromptReady = MutableStateFlow(false)
    override val promptReady: StateFlow<Boolean> = mutablePromptReady.asStateFlow()
    private var readinessPromptStarted = false
    private val readinessFrameConsumer =
        TerminalRenderFrameConsumer { frame ->
            if (frame.activeBuffer != TerminalRenderBufferKind.PRIMARY) {
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

    override fun outputProcessed() {
        if (readinessPromptStarted) context.readRenderFrame(readinessFrameConsumer)
    }

    private fun observeReadiness(
        marker: ShellIntegrationMarker,
        primary: Boolean,
    ) {
        if (!primary) {
            readinessPromptStarted = false
            mutablePromptReady.value = false
            return
        }
        when (marker) {
            ShellIntegrationMarker.PROMPT_START -> {
                readinessPromptStarted = true
                mutablePromptReady.value = false
            }
            ShellIntegrationMarker.PROMPT_END -> mutablePromptReady.value = readinessPromptStarted
            ShellIntegrationMarker.COMMAND_START, ShellIntegrationMarker.COMMAND_FINISHED -> {
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

    override fun observeShellMarker(event: ShellIntegrationEvent) {
        var cursorLineId = NO_LINE_ID
        var previousLineId = NO_LINE_ID
        var cursorColumn = 0
        var bottomAbsoluteRow = 0L
        var commandText: String? = null
        var visiblePromptStartLineId = NO_LINE_ID
        var historySize = 0
        var liveRows = 0
        context.readRenderFrame(scrollbackOffset = 0) { frame ->
            observeReadiness(event.marker, frame.activeBuffer == TerminalRenderBufferKind.PRIMARY)
            historySize = frame.historySize
            liveRows = frame.rows
            val firstVisibleRow = frame.discardedCount + frame.historySize
            val cursor = frame.cursor
            cursorColumn = cursor.column
            if (cursor.row in 0 until frame.rows) {
                cursorLineId = frame.lineId(cursor.row)
            }
            if (cursor.row > 0 && cursor.row - 1 < frame.rows) {
                previousLineId = frame.lineId(cursor.row - 1)
            }
            bottomAbsoluteRow = firstVisibleRow + frame.rows - 1
            if (event.marker == ShellIntegrationMarker.COMMAND_START) {
                commandText =
                    commandTextExtractor.extract(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursor.row,
                        cursorColumn = cursor.column,
                    )
            }
            if (event.marker == ShellIntegrationMarker.PROMPT_END && promptStartedForCommandText) {
                visiblePromptStartLineId =
                    firstRenderedPromptLineId(
                        frame = frame,
                        startLineId = promptStartLineId,
                        startColumn = promptStartColumn,
                        endRow = cursor.row,
                        endColumn = cursor.column,
                    )
            }
        }

        if (event.marker == ShellIntegrationMarker.COMMAND_START && commandText == null && historySize > 0) {
            val historyRows = minOf(historySize, MAX_SHELL_INTEGRATION_COMMAND_ROWS)
            context.readRenderFrame(
                scrollbackOffset = historyRows,
                viewportRows = liveRows + historyRows,
            ) { frame ->
                val cursor = frame.cursor
                commandText =
                    commandTextExtractor.extract(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursor.row,
                        cursorColumn = cursor.column,
                    )
            }
        }

        state.observeLiveBottomRow(bottomAbsoluteRow)
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

    private fun copyActiveCommandLineContext(destination: LongArray): Long {
        require(destination.size >= SHELL_COMMAND_LINE_CONTEXT_LONGS) {
            "destination must contain at least $SHELL_COMMAND_LINE_CONTEXT_LONGS longs"
        }
        destination[SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX] = promptEndLineId
        destination[SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX] = promptEndColumn.toLong()
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
     * only returns a snapshot when the cursor is at the visible end of the
     * command; middle-of-line editing is intentionally deferred until the
     * session can expose the full logical shell-editor buffer.
     *
     * @return active command-line snapshot, or `null` when unavailable.
     */
    override fun activeCommandLine(): TerminalShellCommandLineSnapshot? = context.withTerminalState { activeCommandLineLocked() }

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
        if (!commandTextExtractor.isCursorAtVisibleLineEnd(frame, cursor.row, cursor.column)) return null
        val commandText =
            commandTextExtractor.extract(
                frame = frame,
                promptEndLineId = promptEndLineId,
                promptEndColumn = promptEndColumn,
                cursorRow = cursor.row,
                cursorColumn = cursor.column,
            ) ?: return null
        return TerminalShellCommandLineSnapshot(
            commandText = commandText,
            cursorOffset = commandText.length,
            cursorColumn = cursor.column,
            cursorRow = cursor.row,
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

    /** Collector-confined primitive scratch and callbacks are reused between frame checks. */
    private inner class ShellCommandLineRevisionTracker {
        private val contextScratch = LongArray(SHELL_COMMAND_LINE_CONTEXT_LONGS)
        private val fingerprintScratch = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
        private val previousFingerprint = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
        private val extractor = ShellIntegrationCommandTextExtractor()
        private var previousFingerprintAvailable = false
        private var previousCursorRow = 0
        private var previousCursorColumn = 0
        private var contextRevision = 0L
        private var promptEndLineId = NO_LINE_ID
        private var promptEndColumn = 0
        private var active = false
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
        }
        private val checkContext: () -> Unit = { contextUnchanged = contextMatches() }
        private val publishedFrameConsumer: (TerminalRenderCache) -> Unit = { frame ->
            liveViewport = frame.scrollbackOffset == 0
            if (liveViewport) {
                historySize = frame.historySize
                liveRows = frame.rows
                cursorRow = frame.cursorRow
                cursorColumn = frame.cursorColumn
                status =
                    extractor.fingerprint(
                        cache = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursorRow,
                        cursorColumn = cursorColumn,
                        destination = fingerprintScratch,
                    )
            }
        }
        private val historyFrameConsumer =
            TerminalRenderFrameConsumer { frame ->
                cursorRow = frame.cursor.row
                cursorColumn = frame.cursor.column
                status =
                    extractor.fingerprint(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursorRow,
                        cursorColumn = cursorColumn,
                        destination = fingerprintScratch,
                    )
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
            return contextUnchanged && recordFingerprint(active && status == TerminalShellCommandFingerprintStatus.COMPLETE)
        }

        private fun contextMatches(): Boolean {
            val revision = copyActiveCommandLineContext(contextScratch)
            return revision == contextRevision &&
                contextScratch[SHELL_COMMAND_LINE_CONTEXT_LINE_ID_INDEX] == promptEndLineId &&
                contextScratch[SHELL_COMMAND_LINE_CONTEXT_COLUMN_INDEX].toInt() == promptEndColumn &&
                (contextScratch[SHELL_COMMAND_LINE_CONTEXT_ACTIVE_INDEX] != 0L) == active
        }

        private fun recordFingerprint(available: Boolean): Boolean {
            val changed =
                if (!available) {
                    previousFingerprintAvailable
                } else {
                    !previousFingerprintAvailable ||
                        previousCursorRow != cursorRow ||
                        previousCursorColumn != cursorColumn ||
                        !previousFingerprint.contentEquals(fingerprintScratch)
                }
            if (!changed) return false
            previousFingerprintAvailable = available
            if (available) {
                fingerprintScratch.copyInto(previousFingerprint)
                previousCursorRow = cursorRow
                previousCursorColumn = cursorColumn
            }
            return true
        }
    }
}
