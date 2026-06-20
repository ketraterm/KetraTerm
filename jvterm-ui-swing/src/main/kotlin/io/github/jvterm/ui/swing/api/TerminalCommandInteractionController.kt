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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.session.TerminalShellIntegrationCommandOutputRange
import io.github.jvterm.session.TerminalShellIntegrationCommandRecord

/**
 * EDT-owned command navigation, hit-testing, and output selection controller.
 */
internal class TerminalCommandInteractionController(
    private val host: TerminalCommandInteractionHost,
) {
    private val commandOutputRangeScratch = LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS)
    private val textExtractor = TerminalSelectionTextExtractor()

    fun scrollToCommand(previous: Boolean): Boolean {
        val boundSession = host.session ?: return false
        host.refreshRenderCacheFromSession(boundSession)
        host.refreshShellIntegrationDecorations(boundSession)

        val anchorLineId = currentCommandNavigationLineId() ?: return false
        val shellState = boundSession.shellIntegrationState
        val ownerRecordId = shellState.commandRecordIdAtLine(anchorLineId)
        val targetRecordId =
            if (previous) {
                if (ownerRecordId != TerminalShellIntegrationCommandRecord.NONE) {
                    ownerRecordId
                } else {
                    shellState.previousCommandRecordIdBeforeLine(anchorLineId)
                }
            } else {
                if (ownerRecordId != TerminalShellIntegrationCommandRecord.NONE) {
                    shellState.nextCommandRecordId(ownerRecordId)
                } else {
                    shellState.nextCommandRecordIdAfterLine(anchorLineId)
                }
            }
        if (targetRecordId == TerminalShellIntegrationCommandRecord.NONE) return false

        val targetLineId = shellState.commandAnchorLineId(targetRecordId)
        if (targetLineId == NO_LINE_ID) return false

        refreshCommandNavigationCache(boundSession)
        val targetAbsoluteRow = absoluteRowForLineId(host.searchCache, targetLineId)
        if (targetAbsoluteRow == NO_COMMAND_ABSOLUTE_ROW) return false

        val desiredOffset = host.searchCache.discardedCount + host.searchCache.historySize - targetAbsoluteRow
        return host.scrollViewportTo(desiredOffset.toDouble(), historySize = host.searchCache.historySize, boundSession)
    }

    fun commandRecordAt(
        x: Int,
        y: Int,
    ): Int {
        val boundSession = host.session ?: return TerminalShellIntegrationCommandRecord.NONE
        val cell = host.cellAt(x, y, host.renderCache)
        val row = unpackCellRow(cell)
        if (row !in 0 until host.renderCache.rows) return TerminalShellIntegrationCommandRecord.NONE
        val lineId = host.renderCache.lineIds[row]
        if (lineId == NO_LINE_ID) return TerminalShellIntegrationCommandRecord.NONE
        return boundSession.shellIntegrationState.commandRecordIdAtLine(lineId)
    }

    fun selectCommandOutputAt(
        x: Int,
        y: Int,
    ): Boolean = selectCommandOutput(commandRecordAt(x, y))

    fun selectCommandOutput(recordId: Int): Boolean {
        val boundSession = host.session ?: return false
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false
        val shellState = boundSession.shellIntegrationState
        if (!shellState.copyCommandOutputRange(recordId, commandOutputRangeScratch)) return false

        refreshCommandNavigationCache(boundSession)
        val startLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_LINE_ID_INDEX]
        val endLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.END_LINE_ID_INDEX]
        val includeStart = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_INCLUSIVE_INDEX] != 0L
        val startAbsoluteRow = firstCommandOutputAbsoluteRow(host.searchCache, startLineId, endLineId, includeStart)
        if (startAbsoluteRow == NO_COMMAND_ABSOLUTE_ROW) return false
        val endAbsoluteRow = lastLineAbsoluteRow(host.searchCache, startLineId, endLineId)
        if (endAbsoluteRow == NO_COMMAND_ABSOLUTE_ROW || endAbsoluteRow < startAbsoluteRow) return false

        host.selectAbsoluteRows(startAbsoluteRow, endAbsoluteRow, host.searchCache.columns)
        val desiredOffset = host.searchCache.discardedCount + host.searchCache.historySize - startAbsoluteRow
        host.scrollViewportTo(desiredOffset.toDouble(), historySize = host.searchCache.historySize, boundSession)
        host.repaint()
        return true
    }

    fun commandOutputText(recordId: Int): String? {
        val boundSession = host.session ?: return null
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return null
        val shellState = boundSession.shellIntegrationState
        if (!shellState.copyCommandOutputRange(recordId, commandOutputRangeScratch)) return null

        refreshCommandNavigationCache(boundSession)
        val startLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_LINE_ID_INDEX]
        val endLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.END_LINE_ID_INDEX]
        val includeStart = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_INCLUSIVE_INDEX] != 0L
        val startAbsoluteRow = firstCommandOutputAbsoluteRow(host.searchCache, startLineId, endLineId, includeStart)
        val endAbsoluteRow = lastLineAbsoluteRow(host.searchCache, startLineId, endLineId)
        if (startAbsoluteRow == NO_COMMAND_ABSOLUTE_ROW || endAbsoluteRow < startAbsoluteRow) return null

        val firstCacheAbsoluteRow =
            host.searchCache.discardedCount + host.searchCache.historySize - host.searchCache.scrollbackOffset
        val startRow = (startAbsoluteRow - firstCacheAbsoluteRow).toInt()
        val endRow = (endAbsoluteRow - firstCacheAbsoluteRow).toInt()
        if (startRow !in 0 until host.searchCache.rows || endRow !in startRow until host.searchCache.rows) return null
        val selection = CellSelection(0, startRow, host.searchCache.columns, endRow)
        return textExtractor.selectedText(host.searchCache, selection, joinSoftWrappedRows = true)
    }

    private fun currentCommandNavigationLineId(): Long? {
        val row = currentCommandNavigationRow()
        if (row !in 0 until host.renderCache.rows) return null
        val lineId = host.renderCache.lineIds[row]
        return if (lineId == NO_LINE_ID) null else lineId
    }

    private fun currentCommandNavigationRow(): Int = host.commandNavigationAnchorRow()

    private fun refreshCommandNavigationCache(boundSession: TerminalSession) {
        val historySize = host.renderCache.historySize
        host.searchCache.updateFrom(
            reader = boundSession,
            scrollbackOffset = historySize,
            viewportRows = (historySize + host.visibleGridRows()).coerceAtLeast(1),
        )
    }

    private fun absoluteRowForLineId(
        cache: TerminalRenderCache,
        lineId: Long,
    ): Long {
        val firstAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        var row = 0
        while (row < cache.rows) {
            if (cache.lineIds[row] == lineId) return firstAbsoluteRow + row
            row++
        }
        return NO_COMMAND_ABSOLUTE_ROW
    }

    private fun firstCommandOutputAbsoluteRow(
        cache: TerminalRenderCache,
        startLineId: Long,
        endLineId: Long,
        includeStart: Boolean,
    ): Long {
        val firstLineId = minOf(startLineId, endLineId)
        val lastLineId = maxOf(startLineId, endLineId)
        val firstAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        var row = 0
        while (row < cache.rows) {
            val lineId = cache.lineIds[row]
            if (lineId >= firstLineId && lineId <= lastLineId && (includeStart || lineId != startLineId)) {
                return firstAbsoluteRow + row
            }
            row++
        }
        return NO_COMMAND_ABSOLUTE_ROW
    }

    private fun lastLineAbsoluteRow(
        cache: TerminalRenderCache,
        startLineId: Long,
        endLineId: Long,
    ): Long {
        val firstLineId = minOf(startLineId, endLineId)
        val lastLineId = maxOf(startLineId, endLineId)
        val firstAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        var row = cache.rows - 1
        while (row >= 0) {
            val lineId = cache.lineIds[row]
            if (lineId >= firstLineId && lineId <= lastLineId) return firstAbsoluteRow + row
            row--
        }
        return NO_COMMAND_ABSOLUTE_ROW
    }

    private companion object {
        private const val NO_COMMAND_ABSOLUTE_ROW = Long.MIN_VALUE
        private const val NO_LINE_ID = 0L

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}
