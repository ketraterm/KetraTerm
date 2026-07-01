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
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackContext
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition

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

    fun replaceAll(records: List<TerminalCompletionFeedbackStats>) = rows.replaceAll(records)

    fun snapshot(): List<TerminalCompletionFeedbackStats> = rows.snapshot()

    fun recordSuggestionFeedback(
        context: TerminalCompletionFeedbackContext,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ) {
        if (feedbackAtEpochMillis < 0L) return
        mutate(context, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }
    }

    private fun mutate(
        context: TerminalCompletionFeedbackContext,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCompletionFeedbackStats) -> TerminalCompletionFeedbackStats,
    ) {
        rows.mutate(
            key = context.key(profileId, workingDirectoryUri),
            initialRow = {
                TerminalCompletionFeedbackStats(
                    source = context.source,
                    candidateKind = context.candidateKind,
                    tokenPosition = context.tokenPosition,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                )
            },
            update = update,
        )
    }

    private data class CompletionFeedbackStatsKey(
        val source: String,
        val candidateKind: TerminalCompletionCandidateKind,
        val tokenPosition: TerminalCompletionTokenPosition,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
        val profileId: String?,
        val workingDirectoryUri: String?,
    )

    private fun TerminalCompletionFeedbackStats.key(): CompletionFeedbackStatsKey =
        CompletionFeedbackStatsKey(
            source = source,
            candidateKind = candidateKind,
            tokenPosition = tokenPosition,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )

    private fun TerminalCompletionFeedbackContext.key(
        profileId: String?,
        workingDirectoryUri: String?,
    ): CompletionFeedbackStatsKey =
        CompletionFeedbackStatsKey(
            source = source,
            candidateKind = candidateKind,
            tokenPosition = tokenPosition,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )
}
