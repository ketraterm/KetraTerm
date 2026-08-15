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
package io.github.ketraterm.completion.model

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind

/**
 * User feedback category recorded for a command completion candidate.
 */
enum class TerminalCompletionFeedbackKind {
    /**
     * The user accepted a suggestion and asked the host to apply it.
     */
    ACCEPTED,

    /**
     * The user explicitly dismissed a suggestion without applying it.
     */
    DISMISSED,
}

/**
 * Source-specific context attached to suggestion feedback.
 *
 * This model deliberately stores provider and candidate metadata, not raw
 * private argument values. Hosts should create it from the candidate that was
 * actually displayed to the user so ranking can later distinguish feedback for
 * history, static specs, path completion, and IDE context providers.
 *
 * @property source compact provider/source label from the candidate.
 * @property candidateKind semantic kind of candidate that received feedback.
 * @throws IllegalArgumentException if [source] is blank.
 */
data class TerminalCompletionFeedbackContext(
    val source: String,
    val candidateKind: TerminalCompletionCandidateKind,
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
    }
}

/**
 * Aggregated source-specific feedback counters.
 *
 * Rows are keyed by provider source, candidate kind, host profile, and working
 * directory. They do not contain command text, raw argument values, or
 * request-specific replacement ranges.
 *
 * @property source compact provider/source label from the displayed candidate.
 * @property candidateKind semantic kind of candidate that received feedback.
 * @property profileId optional host profile id associated with this row.
 * @property workingDirectoryUri optional working directory URI associated with
 * this row.
 * @property acceptedCount number of accepted suggestions for this context.
 * @property dismissedCount number of explicitly dismissed suggestions for this context.
 * @property lastUsedEpochMillis host timestamp for the newest represented event.
 * @throws IllegalArgumentException if [source] is blank, a counter is negative,
 * or [lastUsedEpochMillis] is negative.
 */
data class TerminalCompletionFeedbackStats
    @JvmOverloads
    constructor(
        val source: String,
        val candidateKind: TerminalCompletionCandidateKind,
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val acceptedCount: Int = 0,
        val dismissedCount: Int = 0,
        val lastUsedEpochMillis: Long = 0L,
    ) {
        init {
            require(source.isNotBlank()) { "source must not be blank" }
            require(acceptedCount >= 0) { "acceptedCount must be >= 0, was $acceptedCount" }
            require(dismissedCount >= 0) { "dismissedCount must be >= 0, was $dismissedCount" }
            require(lastUsedEpochMillis >= 0L) { "lastUsedEpochMillis must be >= 0, was $lastUsedEpochMillis" }
        }
    }
