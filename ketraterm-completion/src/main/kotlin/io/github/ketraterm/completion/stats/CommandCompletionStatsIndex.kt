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
package io.github.ketraterm.completion.stats

import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.TERMINAL_COMMAND_COMPLETION_STATS_ORDER
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind

/** Bounded stats index keyed by trailing-whitespace-normalized exact command text and context. */
internal class CommandCompletionStatsIndex(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val rowsByKey = HashMap<CommandCompletionStatsKey, TerminalCommandCompletionStats>(capacity)
    private val orderedRows = ArrayList<TerminalCommandCompletionStats>(capacity)

    fun mergeAll(records: List<TerminalCommandCompletionStats>) {
        for (record in records) {
            val incoming = canonicalizeContext(record)
            val key = incoming.key()
            rowsByKey[key] = rowsByKey[key]?.let { current -> mergeStats(current, incoming) } ?: incoming
        }
        rebuildOrder()
    }

    fun snapshot(): List<TerminalCommandCompletionStats> = orderedRows.toList()

    fun recordCommandResult(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ): Boolean =
        mutate(commandLine, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                useCount = saturatedCompletionCounterIncrement(previous.useCount),
                successCount =
                    if (successful) {
                        saturatedCompletionCounterIncrement(previous.successCount)
                    } else {
                        previous.successCount
                    },
                failureCount =
                    if (successful) {
                        previous.failureCount
                    } else {
                        saturatedCompletionCounterIncrement(previous.failureCount)
                    },
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, usedAtEpochMillis),
            )
        }

    fun recordSuggestionFeedback(
        commandLine: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ): Boolean =
        mutate(commandLine, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }

    private fun mutate(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCommandCompletionStats) -> TerminalCommandCompletionStats,
    ): Boolean {
        val canonical = commandLine.trimEnd()
        val context = CompletionLearningContextKey.of(profileId, workingDirectoryUri)
        val key = CommandCompletionStatsKey(canonical, context)
        val current = rowsByKey[key]
        if (current != null) {
            val updated = update(current)
            if (updated == current) return false
            check(orderedRows.remove(current)) { "indexed completion statistics row is missing from sorted storage" }
            rowsByKey[key] = updated
            insertOrdered(updated)
            return true
        }

        val created =
            update(
                TerminalCommandCompletionStats(
                    commandLine = canonical,
                    profileId = context.profileId,
                    workingDirectoryUri = context.workingDirectoryUri,
                ),
            )
        rowsByKey[key] = created
        insertOrdered(created)
        if (orderedRows.size <= capacity) return true

        val evicted = orderedRows.removeAt(orderedRows.lastIndex)
        rowsByKey.remove(evicted.key())
        return evicted !== created
    }

    private fun rebuildOrder() {
        orderedRows.clear()
        orderedRows.addAll(rowsByKey.values)
        orderedRows.sortWith(TERMINAL_COMMAND_COMPLETION_STATS_ORDER)
        if (orderedRows.size > capacity) {
            orderedRows.subList(capacity, orderedRows.size).clear()
        }
        rowsByKey.clear()
        for (row in orderedRows) rowsByKey[row.key()] = row
    }

    private fun insertOrdered(row: TerminalCommandCompletionStats) {
        var low = 0
        var high = orderedRows.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (TERMINAL_COMMAND_COMPLETION_STATS_ORDER.compare(orderedRows[middle], row) <= 0) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        orderedRows.add(low, row)
    }

    private data class CommandCompletionStatsKey(
        val commandLine: String,
        val context: CompletionLearningContextKey,
    )

    private fun TerminalCommandCompletionStats.key(): CommandCompletionStatsKey =
        CommandCompletionStatsKey(
            commandLine = commandLine,
            context = CompletionLearningContextKey.of(profileId, workingDirectoryUri),
        )

    private fun canonicalizeContext(record: TerminalCommandCompletionStats): TerminalCommandCompletionStats {
        val context = CompletionLearningContextKey.of(record.profileId, record.workingDirectoryUri)
        return record.copy(
            commandLine = record.commandLine.trimEnd(),
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
        )
    }

    private fun mergeStats(
        current: TerminalCommandCompletionStats,
        incoming: TerminalCommandCompletionStats,
    ): TerminalCommandCompletionStats {
        val newest = if (incoming.lastUsedEpochMillis >= current.lastUsedEpochMillis) incoming else current
        val context = CompletionLearningContextKey.of(current.profileId, current.workingDirectoryUri)
        return newest.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
            useCount = saturatedCounterSum(current.useCount, incoming.useCount),
            successCount = saturatedCounterSum(current.successCount, incoming.successCount),
            failureCount = saturatedCounterSum(current.failureCount, incoming.failureCount),
            acceptedCount = saturatedCounterSum(current.acceptedCount, incoming.acceptedCount),
            dismissedCount = saturatedCounterSum(current.dismissedCount, incoming.dismissedCount),
            lastUsedEpochMillis = maxOf(current.lastUsedEpochMillis, incoming.lastUsedEpochMillis),
        )
    }
}
