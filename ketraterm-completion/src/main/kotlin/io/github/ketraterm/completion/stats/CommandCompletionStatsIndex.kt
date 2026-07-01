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

import io.github.ketraterm.completion.internal.BoundedStatsRowIndex
import io.github.ketraterm.completion.internal.TERMINAL_COMMAND_COMPLETION_STATS_ORDER
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind

/**
 * Bounded exact-command stats index.
 *
 * This class owns exact command row creation and counter mutation. Shared
 * bounded indexing mechanics live in [BoundedStatsRowIndex].
 */
internal class CommandCompletionStatsIndex(
    capacity: Int,
) {
    private val rows =
        BoundedStatsRowIndex(
            capacity = capacity,
            order = TERMINAL_COMMAND_COMPLETION_STATS_ORDER,
            keySelector = { it.key() },
            shouldReplace = { current, candidate -> candidate.lastUsedEpochMillis >= current.lastUsedEpochMillis },
        )

    fun replaceAll(records: List<TerminalCommandCompletionStats>) = rows.replaceAll(records)

    fun snapshot(): List<TerminalCommandCompletionStats> = rows.snapshot()

    fun recordCommandResult(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        if (!isRecordableStatsEvent(commandLine, usedAtEpochMillis)) return
        mutate(commandLine, profileId, workingDirectoryUri) { previous, canonical ->
            previous.copy(
                commandLine = canonical,
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
    }

    fun recordSuggestionFeedback(
        commandLine: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ) {
        if (!isRecordableStatsEvent(commandLine, feedbackAtEpochMillis)) return
        mutate(commandLine, profileId, workingDirectoryUri) { previous, canonical ->
            previous.copy(
                commandLine = canonical,
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }
    }

    private fun mutate(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCommandCompletionStats, String) -> TerminalCommandCompletionStats,
    ) {
        val canonical = commandLine.trim()
        val normalized = TerminalCommandCompletionStats.normalizeCommandLine(canonical)
        rows.mutate(
            key = CommandCompletionStatsKey(normalized, profileId, workingDirectoryUri),
            initialRow = {
                TerminalCommandCompletionStats(
                    commandLine = canonical,
                    normalizedCommandLine = normalized,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                )
            },
            update = { update(it, canonical) },
        )
    }

    private data class CommandCompletionStatsKey(
        val normalizedCommandLine: String,
        val profileId: String?,
        val workingDirectoryUri: String?,
    )

    private fun TerminalCommandCompletionStats.key(): CommandCompletionStatsKey =
        CommandCompletionStatsKey(
            normalizedCommandLine = normalizedCommandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )
}
