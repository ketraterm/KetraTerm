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

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.internal.BoundedStatsRowIndex
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackContext
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats

/**
 * Bounded source-specific feedback stats index.
 *
 * Rows are keyed by displayed-candidate metadata rather than command text so
 * path, spec, IDE, and history providers can learn independently.
 */
internal class CompletionFeedbackStatsIndex(
    capacity: Int,
) {
    private val rows =
        BoundedStatsRowIndex(
            capacity = capacity,
            order = TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER,
            keySelector = { it.key() },
            shouldReplace = { current, candidate -> candidate.lastUsedEpochMillis >= current.lastUsedEpochMillis },
        )

    fun replaceAll(records: List<TerminalCompletionFeedbackStats>) = rows.replaceAll(records.map(::canonicalizeContext))

    fun mergeAll(records: List<TerminalCompletionFeedbackStats>) = rows.mergeAll(records.map(::canonicalizeContext), ::mergeStats)

    fun snapshot(): List<TerminalCompletionFeedbackStats> = rows.snapshot()

    fun recordSuggestionFeedback(
        context: TerminalCompletionFeedbackContext,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ) {
        if (feedbackAtEpochMillis < 0L) return
        val learningContext = CompletionLearningContextKey.of(profileId, workingDirectoryUri)
        rows.mutate(
            key = context.key(learningContext),
            initialRow = {
                TerminalCompletionFeedbackStats(
                    source = context.source,
                    candidateKind = context.candidateKind,
                    profileId = learningContext.profileId,
                    workingDirectoryUri = learningContext.workingDirectoryUri,
                )
            },
            update = { previous ->
                previous.copy(
                    acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                    dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                    lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
                )
            },
        )
    }

    private data class CompletionFeedbackStatsKey(
        val source: String,
        val candidateKind: TerminalCompletionCandidateKind,
        val context: CompletionLearningContextKey,
    )

    private fun TerminalCompletionFeedbackStats.key(): CompletionFeedbackStatsKey =
        CompletionFeedbackStatsKey(
            source = source,
            candidateKind = candidateKind,
            context = CompletionLearningContextKey.of(profileId, workingDirectoryUri),
        )

    private fun canonicalizeContext(record: TerminalCompletionFeedbackStats): TerminalCompletionFeedbackStats {
        val context = CompletionLearningContextKey.of(record.profileId, record.workingDirectoryUri)
        return record.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
        )
    }

    private fun TerminalCompletionFeedbackContext.key(context: CompletionLearningContextKey): CompletionFeedbackStatsKey =
        CompletionFeedbackStatsKey(
            source = source,
            candidateKind = candidateKind,
            context = context,
        )

    private fun mergeStats(
        current: TerminalCompletionFeedbackStats,
        incoming: TerminalCompletionFeedbackStats,
    ): TerminalCompletionFeedbackStats {
        val newest = if (incoming.lastUsedEpochMillis >= current.lastUsedEpochMillis) incoming else current
        val context = CompletionLearningContextKey.of(current.profileId, current.workingDirectoryUri)
        return newest.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
            acceptedCount = saturatedCounterSum(current.acceptedCount, incoming.acceptedCount),
            dismissedCount = saturatedCounterSum(current.dismissedCount, incoming.dismissedCount),
            lastUsedEpochMillis = maxOf(current.lastUsedEpochMillis, incoming.lastUsedEpochMillis),
        )
    }
}
