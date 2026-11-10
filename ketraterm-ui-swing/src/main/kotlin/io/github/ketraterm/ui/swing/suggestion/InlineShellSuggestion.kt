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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalCommandEditContext
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout

/** EDT-owned detached preview; text runs are painted by the ordinary grid painter. */
internal class InlineShellSuggestion(
    val session: TerminalSession,
    val expected: TerminalCommandEditContext,
    val cells: TerminalRenderCache,
    val foreground: Int?,
) {
    private val bidi = TerminalBidiLayout()
    private val liveBidi = TerminalBidiLayout()
    private val ambiguousAsWide = session.modeSnapshot.treatAmbiguousAsWide
    private var cursorPendingWrap: Boolean? = null

    fun reconcile(live: TerminalRenderCache): Boolean {
        if (cursorPendingWrap != null && cursorPendingWrap != live.cursorPendingWrap) return false
        cursorPendingWrap = live.cursorPendingWrap
        val modes = session.modeSnapshot
        if (modes.treatAmbiguousAsWide != ambiguousAsWide) return false
        if (!modes.isAutoWrap || modes.isOriginMode || modes.isLeftRightMarginMode || !live.hasFullScreenMargins) return false
        if (expected.isCancelled || session.isClosed ||
            live.activeBuffer != TerminalRenderBufferKind.PRIMARY ||
            live.scrollbackOffset != 0 ||
            live.columns != cells.columns ||
            live.rows < cells.rows ||
            live.cursorRow != expected.commandLine.cursorRow ||
            live.cursorColumn != expected.commandLine.cursorColumn.coerceAtMost(live.columns - 1)
        ) {
            return false
        }
        val anchorRow = live.cursorRow
        val anchorColumn = live.cursorColumn + if (live.cursorPendingWrap) 1 else 0
        // Any visible suffix on the editing row is ambiguous shell-owned text, even if the
        // proposed suffix is shorter and would not immediately touch it.
        for (column in anchorColumn until live.columns) {
            if (!blank(live, anchorRow, column)) return false
        }
        var visible = false
        for (row in anchorRow until cells.rows) {
            var rowPrepared = false
            var layout: TerminalBidiLayout.Row? = null
            for (column in 0 until cells.columns) {
                val index = cells.rowOffset(row) + column
                val flags = cells.flags[index]
                if (flags and (TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER) == 0) continue
                if (!rowPrepared) {
                    if (liveBidi.row(live, row) != null) return false
                    layout = bidi.row(cells, row)
                    rowPrepared = true
                }
                val visual = layout?.visualColumn(column) ?: column
                val span = if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
                if (row == anchorRow && visual < anchorColumn) return false
                var collision = false
                for (cell in visual until visual + span) {
                    if (cell >= live.columns || !blank(live, row, cell) || imageAt(live, row, cell)) collision = true
                }
                if (collision) {
                    if (layout != null) return false
                    // Never leave half a wide cell or cluster. Stop at the first obstruction.
                    cells.flags.fill(TerminalRenderCellFlags.EMPTY, index, cells.rows * cells.columns)
                    cells.codeWords.fill(0, index, cells.rows * cells.columns)
                    cells.clusterRefs.fill(0L, index, cells.rows * cells.columns)
                    for (changed in row until cells.rows) cells.lineGenerations[changed]++
                    return visible
                }
                visible = true
            }
        }
        return visible
    }

    private fun blank(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): Boolean {
        val index = cache.rowOffset(row) + column
        return cache.flags[index] and TerminalRenderCellFlags.EMPTY != 0 ||
            cache.flags[index] and TerminalRenderCellFlags.CODEPOINT != 0 &&
            cache.codeWords[index] == ' '.code
    }

    private fun imageAt(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): Boolean {
        val images = cache.images
        for (index in 0 until images.size) {
            if (column + 1 > images.column(index) &&
                column < images.column(index) + images.width(index) &&
                row + 1 > images.row(index) &&
                row < images.row(index) + images.height(index)
            ) {
                return true
            }
        }
        return false
    }
}
