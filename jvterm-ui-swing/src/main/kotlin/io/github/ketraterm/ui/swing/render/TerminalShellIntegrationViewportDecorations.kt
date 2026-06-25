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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.session.TerminalShellIntegrationState

/**
 * Renderer-local snapshot of shell integration decorations for one viewport.
 *
 * The shared [TerminalShellIntegrationState] is a session-owned command
 * timeline. Swing projects the visible rows into these primitive arrays before
 * painting so the row paint loop performs only array lookups.
 */
internal class TerminalShellIntegrationViewportDecorations {
    private var promptStarts = BooleanArray(0)
    private var failedCommandRails = BooleanArray(0)
    private var commandStarts = BooleanArray(0)
    private var commandEnds = BooleanArray(0)
    private var commandRecordIds = IntArray(0)
    private var commandLifecycleStates = IntArray(0)
    private var nextPromptStarts = BooleanArray(0)
    private var nextFailedCommandRails = BooleanArray(0)
    private var nextCommandStarts = BooleanArray(0)
    private var nextCommandEnds = BooleanArray(0)
    private var nextCommandRecordIds = IntArray(0)
    private var nextCommandLifecycleStates = IntArray(0)
    private var rowCount = 0

    /**
     * Copies visible shell integration decorations from [state] for [cache].
     *
     * @return true when any visible decoration flag changed.
     */
    fun updateFrom(
        state: TerminalShellIntegrationState,
        cache: TerminalRenderCache,
    ): Boolean {
        ensureCapacity(cache.rows)
        if (cache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            clearNext(cache.rows)
        } else {
            state.copyViewport(
                lineIds = cache.lineIds,
                rowCount = cache.rows,
                promptStarts = nextPromptStarts,
                commandStarts = nextCommandStarts,
                commandEnds = nextCommandEnds,
                commandRecordIds = nextCommandRecordIds,
                commandLifecycleStates = nextCommandLifecycleStates,
                failedCommandRails = nextFailedCommandRails,
            )
        }
        val changed = decorationsChanged(cache.rows)
        swapBuffers()
        rowCount = cache.rows
        return changed
    }

    /**
     * Clears this viewport snapshot.
     */
    fun reset() {
        rowCount = 0
    }

    /**
     * Returns whether visible [row] starts a shell prompt.
     */
    fun hasPromptStartAt(row: Int): Boolean = row in 0 until rowCount && promptStarts[row]

    /**
     * Returns whether visible [row] belongs to failed-command output.
     */
    fun hasFailedCommandRailAt(row: Int): Boolean = row in 0 until rowCount && failedCommandRails[row]

    /**
     * Returns whether visible [row] is the command-output start row.
     */
    fun hasCommandStartAt(row: Int): Boolean = row in 0 until rowCount && commandStarts[row]

    /**
     * Returns whether visible [row] is the command-output end row.
     */
    fun hasCommandEndAt(row: Int): Boolean = row in 0 until rowCount && commandEnds[row]

    /**
     * Returns the stable command record id associated with visible [row], or zero.
     */
    fun commandRecordIdAt(row: Int): Int = if (row in 0 until rowCount) commandRecordIds[row] else 0

    /**
     * Returns the command lifecycle associated with visible [row], or zero.
     */
    fun commandLifecycleAt(row: Int): Int = if (row in 0 until rowCount) commandLifecycleStates[row] else 0

    private fun ensureCapacity(rows: Int) {
        if (promptStarts.size >= rows) return
        promptStarts = BooleanArray(rows)
        failedCommandRails = BooleanArray(rows)
        commandStarts = BooleanArray(rows)
        commandEnds = BooleanArray(rows)
        commandRecordIds = IntArray(rows)
        commandLifecycleStates = IntArray(rows)
        nextPromptStarts = BooleanArray(rows)
        nextFailedCommandRails = BooleanArray(rows)
        nextCommandStarts = BooleanArray(rows)
        nextCommandEnds = BooleanArray(rows)
        nextCommandRecordIds = IntArray(rows)
        nextCommandLifecycleStates = IntArray(rows)
    }

    private fun decorationsChanged(nextRowCount: Int): Boolean {
        if (rowCount != nextRowCount) return true

        var row = 0
        while (row < nextRowCount) {
            if (promptStarts[row] != nextPromptStarts[row]) return true
            if (failedCommandRails[row] != nextFailedCommandRails[row]) return true
            if (commandStarts[row] != nextCommandStarts[row]) return true
            if (commandEnds[row] != nextCommandEnds[row]) return true
            if (commandRecordIds[row] != nextCommandRecordIds[row]) return true
            if (commandLifecycleStates[row] != nextCommandLifecycleStates[row]) return true
            row++
        }
        return false
    }

    private fun swapBuffers() {
        var flags = promptStarts
        promptStarts = nextPromptStarts
        nextPromptStarts = flags

        flags = failedCommandRails
        failedCommandRails = nextFailedCommandRails
        nextFailedCommandRails = flags

        flags = commandStarts
        commandStarts = nextCommandStarts
        nextCommandStarts = flags

        flags = commandEnds
        commandEnds = nextCommandEnds
        nextCommandEnds = flags

        var ids = commandRecordIds
        commandRecordIds = nextCommandRecordIds
        nextCommandRecordIds = ids

        ids = commandLifecycleStates
        commandLifecycleStates = nextCommandLifecycleStates
        nextCommandLifecycleStates = ids
    }

    private fun clearNext(rows: Int) {
        nextPromptStarts.fill(false, 0, rows)
        nextFailedCommandRails.fill(false, 0, rows)
        nextCommandStarts.fill(false, 0, rows)
        nextCommandEnds.fill(false, 0, rows)
        nextCommandRecordIds.fill(TerminalShellIntegrationCommandRecord.NONE, 0, rows)
        nextCommandLifecycleStates.fill(TerminalShellIntegrationCommandLifecycle.NONE, 0, rows)
    }
}
