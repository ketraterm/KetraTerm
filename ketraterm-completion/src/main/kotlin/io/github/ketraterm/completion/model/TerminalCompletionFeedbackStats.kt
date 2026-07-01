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
 * Position of a completion candidate within the command line that produced it.
 */
enum class TerminalCompletionTokenPosition {
    /**
     * Candidate replaces or completes the executable token.
     */
    COMMAND,

    /**
     * Candidate replaces or completes a command-specific subcommand token.
     */
    SUBCOMMAND,

    /**
     * Candidate replaces or completes an option or flag token.
     */
    OPTION,

    /**
     * Candidate replaces or completes a positional argument or option value.
     */
    ARGUMENT,

    /**
     * Candidate position could not be classified safely.
     */
    UNKNOWN,

    ;

    companion object {
        /**
         * Maps a candidate kind to its default command-line token position.
         *
         * Hosts may override the position when they have richer parser context,
         * but this mapping is the shared fallback used by generic completion
         * sources and host adapters.
         *
         * @param candidateKind semantic candidate kind.
         * @return default token position for [candidateKind].
         */
        @JvmStatic
        fun fromCandidateKind(candidateKind: TerminalCompletionCandidateKind): TerminalCompletionTokenPosition =
            when (candidateKind) {
                TerminalCompletionCandidateKind.COMMAND -> COMMAND
                TerminalCompletionCandidateKind.SUBCOMMAND -> SUBCOMMAND
                TerminalCompletionCandidateKind.OPTION -> OPTION
                TerminalCompletionCandidateKind.ARGUMENT,
                TerminalCompletionCandidateKind.PATH,
                -> ARGUMENT
                TerminalCompletionCandidateKind.HISTORY -> UNKNOWN
            }
    }
}

/**
 * Source-specific context attached to suggestion feedback.
 *
 * This model deliberately stores provider and replacement metadata, not raw
 * private argument values. Hosts should create it from the candidate that was
 * actually displayed to the user so ranking can later distinguish feedback for
 * history, static specs, path completion, and IDE context providers.
 *
 * @property source compact provider/source label from the candidate.
 * @property candidateKind semantic kind of candidate that received feedback.
 * @property tokenPosition classified command-line position of the candidate.
 * @property replacementStartOffset inclusive UTF-16 replacement start offset.
 * @property replacementEndOffset exclusive UTF-16 replacement end offset.
 */
data class TerminalCompletionFeedbackContext
    @JvmOverloads
    constructor(
        val source: String,
        val candidateKind: TerminalCompletionCandidateKind,
        val tokenPosition: TerminalCompletionTokenPosition = TerminalCompletionTokenPosition.UNKNOWN,
        val replacementStartOffset: Int = -1,
        val replacementEndOffset: Int = -1,
    ) {
        init {
            require(source.isNotBlank()) { "source must not be blank" }
            requireValidReplacementRange(replacementStartOffset, replacementEndOffset)
        }
    }

/**
 * Aggregated source-specific feedback counters.
 *
 * Rows are keyed by provider source, candidate kind, token position, replacement
 * range, profile, and working directory. They do not contain command text or
 * raw argument values.
 *
 * @property source compact provider/source label from the displayed candidate.
 * @property candidateKind semantic kind of candidate that received feedback.
 * @property tokenPosition classified command-line position of the candidate.
 * @property replacementStartOffset inclusive UTF-16 replacement start offset,
 * or `-1` when unknown.
 * @property replacementEndOffset exclusive UTF-16 replacement end offset, or
 * `-1` when unknown.
 * @property profileId optional host profile id associated with this row.
 * @property workingDirectoryUri optional working directory URI associated with
 * this row.
 * @property acceptedCount number of accepted suggestions for this context.
 * @property dismissedCount number of explicitly dismissed suggestions for this context.
 * @property lastUsedEpochMillis host timestamp for the newest represented event.
 */
data class TerminalCompletionFeedbackStats
    @JvmOverloads
    constructor(
        val source: String,
        val candidateKind: TerminalCompletionCandidateKind,
        val tokenPosition: TerminalCompletionTokenPosition = TerminalCompletionTokenPosition.UNKNOWN,
        val replacementStartOffset: Int = -1,
        val replacementEndOffset: Int = -1,
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val acceptedCount: Int = 0,
        val dismissedCount: Int = 0,
        val lastUsedEpochMillis: Long = 0L,
    ) {
        init {
            require(source.isNotBlank()) { "source must not be blank" }
            requireValidReplacementRange(replacementStartOffset, replacementEndOffset)
            require(acceptedCount >= 0) { "acceptedCount must be >= 0, was $acceptedCount" }
            require(dismissedCount >= 0) { "dismissedCount must be >= 0, was $dismissedCount" }
            require(lastUsedEpochMillis >= 0L) { "lastUsedEpochMillis must be >= 0, was $lastUsedEpochMillis" }
        }
    }

private fun requireValidReplacementRange(
    replacementStartOffset: Int,
    replacementEndOffset: Int,
) {
    require(
        (replacementStartOffset == -1 && replacementEndOffset == -1) ||
            (replacementStartOffset >= 0 && replacementEndOffset >= replacementStartOffset),
    ) {
        "replacement range must be unset or satisfy 0 <= start <= end, was " +
            "$replacementStartOffset..$replacementEndOffset"
    }
}
