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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationCommandBlockRange
import io.github.ketraterm.session.TerminalShellIntegrationCommandOutputRange
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord

/**
 * EDT-owned command navigation, hit-testing, and output selection controller.
 */
internal class TerminalCommandInteractionController(
    private val host: TerminalCommandInteractionHost,
) {
    private val commandOutputRangeScratch = LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS)
    private val commandBlockRangeScratch = LongArray(TerminalShellIntegrationCommandBlockRange.REQUIRED_LONGS)
    private val textReader = TerminalSelectionTextReader()
    private var rangeStart = NO_COMMAND_ABSOLUTE_ROW
    private var rangeEnd = NO_COMMAND_ABSOLUTE_ROW
    private var rangeColumns = 0
    private var rangeContentGeneration = 0L

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
        return targetRecordId != TerminalShellIntegrationCommandRecord.NONE && revealCommand(targetRecordId)
    }

    fun revealCommand(recordId: Int): Boolean {
        val boundSession = host.session ?: return false
        val targetLineId = boundSession.shellIntegrationState.commandAnchorLineId(recordId)
        if (targetLineId == NO_LINE_ID) return false

        var targetAbsoluteRow = NO_COMMAND_ABSOLUTE_ROW
        boundSession.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
            targetAbsoluteRow = absoluteRowForLineId(frame, targetLineId)
        }
        return targetAbsoluteRow != NO_COMMAND_ABSOLUTE_ROW && host.scrollToAbsoluteRow(targetAbsoluteRow, center = false)
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

        val startLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_LINE_ID_INDEX]
        val endLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.END_LINE_ID_INDEX]
        val includeStart = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_INCLUSIVE_INDEX] != 0L
        if (!resolveRange(boundSession, startLineId, endLineId, includeStart)) return false

        host.selectAbsoluteRows(rangeStart, rangeEnd, rangeColumns)
        host.scrollToAbsoluteRow(rangeStart, center = false)
        host.repaint()
        return true
    }

    fun selectCommandBlock(recordId: Int): Boolean {
        val boundSession = host.session ?: return false
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false
        val shellState = boundSession.shellIntegrationState
        if (!shellState.copyCommandBlockRange(recordId, commandBlockRangeScratch)) return false

        val startLineId = commandBlockRangeScratch[TerminalShellIntegrationCommandBlockRange.START_LINE_ID_INDEX]
        val endLineId = commandBlockRangeScratch[TerminalShellIntegrationCommandBlockRange.END_LINE_ID_INDEX]
        if (!resolveRange(boundSession, startLineId, endLineId, includeStart = true)) return false

        host.selectAbsoluteRows(rangeStart, rangeEnd, rangeColumns)
        host.scrollToAbsoluteRow(rangeStart, center = false)
        host.repaint()
        return true
    }

    fun commandOutputText(recordId: Int): String? {
        val boundSession = host.session ?: return null
        if (recordId == TerminalShellIntegrationCommandRecord.NONE) return null
        val shellState = boundSession.shellIntegrationState
        if (!shellState.copyCommandOutputRange(recordId, commandOutputRangeScratch)) return null

        val startLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_LINE_ID_INDEX]
        val endLineId = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.END_LINE_ID_INDEX]
        val includeStart = commandOutputRangeScratch[TerminalShellIntegrationCommandOutputRange.START_INCLUSIVE_INDEX] != 0L
        if (!resolveRange(boundSession, startLineId, endLineId, includeStart)) return null
        return textReader.read(boundSession, rangeStart, 0, rangeEnd, rangeColumns, expectedContentGeneration = rangeContentGeneration)
    }

    private fun currentCommandNavigationLineId(): Long? {
        val row = currentCommandNavigationRow()
        if (row !in 0 until host.renderCache.rows) return null
        val lineId = host.renderCache.lineIds[row]
        return if (lineId == NO_LINE_ID) null else lineId
    }

    private fun currentCommandNavigationRow(): Int = host.commandNavigationAnchorRow()

    private fun resolveRange(
        boundSession: TerminalSession,
        startLineId: Long,
        endLineId: Long,
        includeStart: Boolean,
    ): Boolean {
        rangeStart = NO_COMMAND_ABSOLUTE_ROW
        rangeEnd = NO_COMMAND_ABSOLUTE_ROW
        boundSession.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
            rangeStart = firstCommandOutputAbsoluteRow(frame, startLineId, endLineId, includeStart)
            rangeEnd = lastLineAbsoluteRow(frame, startLineId, endLineId)
            rangeColumns = frame.columns
            rangeContentGeneration = frame.contentGeneration
        }
        return rangeStart != NO_COMMAND_ABSOLUTE_ROW && rangeEnd != NO_COMMAND_ABSOLUTE_ROW && rangeEnd >= rangeStart
    }

    private fun absoluteRowForLineId(
        frame: TerminalRenderFrame,
        lineId: Long,
        last: Boolean = false,
    ): Long {
        val firstAbsoluteRow = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        var row = if (last) frame.rows - 1 else 0
        while (row in 0 until frame.rows) {
            if (frame.lineId(row) == lineId) return firstAbsoluteRow + row
            row += if (last) -1 else 1
        }
        return NO_COMMAND_ABSOLUTE_ROW
    }

    private fun firstCommandOutputAbsoluteRow(
        frame: TerminalRenderFrame,
        startLineId: Long,
        endLineId: Long,
        includeStart: Boolean,
    ): Long {
        val start = absoluteRowForLineId(frame, startLineId, last = !includeStart)
        val end = absoluteRowForLineId(frame, endLineId, last = true)
        if (start != NO_COMMAND_ABSOLUTE_ROW && end != NO_COMMAND_ABSOLUTE_ROW) {
            val first = start + if (includeStart) 0 else 1
            return if (first <= end) first else NO_COMMAND_ABSOLUTE_ROW
        }
        if (!hasMonotonicLineIds(frame)) return NO_COMMAND_ABSOLUTE_ROW
        val firstLineId = minOf(startLineId, endLineId)
        val lastLineId = maxOf(startLineId, endLineId)
        val firstAbsoluteRow = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        var row = 0
        while (row < frame.rows) {
            val lineId = frame.lineId(row)
            if (lineId in firstLineId..lastLineId && (includeStart || lineId != startLineId)) {
                return firstAbsoluteRow + row
            }
            row++
        }
        return NO_COMMAND_ABSOLUTE_ROW
    }

    private fun lastLineAbsoluteRow(
        frame: TerminalRenderFrame,
        startLineId: Long,
        endLineId: Long,
    ): Long {
        val start = absoluteRowForLineId(frame, startLineId)
        val end = absoluteRowForLineId(frame, endLineId, last = true)
        if (start != NO_COMMAND_ABSOLUTE_ROW && end != NO_COMMAND_ABSOLUTE_ROW) {
            return if (start <= end) end else NO_COMMAND_ABSOLUTE_ROW
        }
        if (!hasMonotonicLineIds(frame)) return NO_COMMAND_ABSOLUTE_ROW
        val firstLineId = minOf(startLineId, endLineId)
        val lastLineId = maxOf(startLineId, endLineId)
        val firstAbsoluteRow = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        var row = frame.rows - 1
        while (row >= 0) {
            val lineId = frame.lineId(row)
            if (lineId in firstLineId..lastLineId) return firstAbsoluteRow + row
            row--
        }
        return NO_COMMAND_ABSOLUTE_ROW
    }

    private fun hasMonotonicLineIds(frame: TerminalRenderFrame): Boolean {
        var previous = 0L
        for (row in 0 until frame.rows) {
            val id = frame.lineId(row)
            if (id <= 0 || id < previous) return false
            previous = id
        }
        return true
    }

    private companion object {
        private const val NO_COMMAND_ABSOLUTE_ROW = Long.MIN_VALUE
        private const val NO_LINE_ID = 0L

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}
