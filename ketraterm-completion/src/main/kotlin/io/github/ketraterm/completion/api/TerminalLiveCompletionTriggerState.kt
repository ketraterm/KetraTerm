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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCommandSpec

/** Outcome of evaluating one live-completion trigger snapshot. */
enum class TerminalLiveCompletionTriggerDecision {
    /** Hide any visible suggestion surface because completion is not eligible. */
    HIDE,

    /** Keep the current surface because this exact snapshot was already requested. */
    KEEP,

    /** Request a new completion evaluation for the supplied snapshot. */
    REQUEST,
}

/**
 * Pure, host-neutral de-duplication state for debounced live completion refreshes.
 *
 * Hosts own scheduling, UI-thread dispatch, and popup visibility. This state machine
 * only decides whether a stable snapshot should request, retain, or hide completion.
 * It is intended for one host-owned terminal session and is not thread-safe.
 *
 * @param minimumNonWhitespaceCharacters minimum typed non-whitespace command characters
 * required before a popup can open.
 * @param commandSpecs immutable command specifications used by trigger policy.
 * @param shellCapabilities explicit shell lexical and replacement policy.
 */
class TerminalLiveCompletionTriggerState(
    private val minimumNonWhitespaceCharacters: Int,
    commandSpecs: List<TerminalCommandSpec>,
    private val shellCapabilities: TerminalShellCapabilities,
) {
    private val commandSpecs = commandSpecs.toList()
    private var lastRequestedCommandLine: String? = null
    private var lastRequestedCursorOffset: Int = -1
    private var lastRequestedCursorColumn: Int = -1
    private var lastRequestedCursorRow: Int = -1
    private var lastRequestedRankingContextKey: String? = null

    init {
        require(minimumNonWhitespaceCharacters >= 0) {
            "minimumNonWhitespaceCharacters must be >= 0, was $minimumNonWhitespaceCharacters"
        }
    }

    /**
     * Evaluates the active command state against current enablement and records a newly requested state.
     *
     * @param commandLine active shell text, or `null` when the host cannot prove one.
     * @param cursorOffset UTF-16 cursor offset in [commandLine] when it is non-null.
     * @param cursorColumn terminal-grid popup anchor column.
     * @param cursorRow terminal-grid popup anchor row.
     * @param rankingContextKey host-owned ranking context such as a working directory.
     * @return the host action appropriate for the current state.
     */
    fun evaluate(
        commandLine: String?,
        cursorOffset: Int,
        cursorColumn: Int,
        cursorRow: Int,
        rankingContextKey: String?,
    ): TerminalLiveCompletionTriggerDecision {
        if (commandLine == null) {
            invalidate()
            return TerminalLiveCompletionTriggerDecision.HIDE
        }
        require(cursorOffset in 0..commandLine.length) {
            "cursorOffset must be in 0..${commandLine.length}, was $cursorOffset"
        }
        require(cursorColumn >= 0) { "cursorColumn must be >= 0, was $cursorColumn" }
        require(cursorRow >= 0) { "cursorRow must be >= 0, was $cursorRow" }
        if (
            !TerminalCompletionTriggerEvaluator.shouldTrigger(
                commandLine = commandLine,
                cursorOffset = cursorOffset,
                minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
                commandSpecs = commandSpecs,
                shellCapabilities = shellCapabilities,
            )
        ) {
            invalidate()
            return TerminalLiveCompletionTriggerDecision.HIDE
        }
        if (commandLine == lastRequestedCommandLine &&
            cursorOffset == lastRequestedCursorOffset &&
            cursorColumn == lastRequestedCursorColumn &&
            cursorRow == lastRequestedCursorRow &&
            rankingContextKey == lastRequestedRankingContextKey
        ) {
            return TerminalLiveCompletionTriggerDecision.KEEP
        }
        lastRequestedCommandLine = commandLine
        lastRequestedCursorOffset = cursorOffset
        lastRequestedCursorColumn = cursorColumn
        lastRequestedCursorRow = cursorRow
        lastRequestedRankingContextKey = rankingContextKey
        return TerminalLiveCompletionTriggerDecision.REQUEST
    }

    /** Clears de-duplication state after source or ranking-context invalidation. */
    fun invalidate() {
        lastRequestedCommandLine = null
        lastRequestedCursorOffset = -1
        lastRequestedCursorColumn = -1
        lastRequestedCursorRow = -1
        lastRequestedRankingContextKey = null
    }
}
