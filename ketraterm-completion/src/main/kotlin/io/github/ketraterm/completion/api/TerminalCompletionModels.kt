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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.internal.isTerminalCompletionUtf16Boundary

/**
 * Immutable command-line context used to request completion candidates.
 *
 * @property commandLine full command-line text known to the host.
 * @property cursorOffset UTF-16 cursor offset within [commandLine]. The offset
 * must be a scalar boundary and must not split a surrogate pair.
 * @property workingDirectoryUri optional current working directory URI.
 * @property profileId optional host profile id, such as `pwsh`, `bash`, or `zsh`.
 * @property maxCandidates maximum number of candidates to return.
 */
data class TerminalCompletionRequest
    @JvmOverloads
    constructor(
        val commandLine: String,
        val cursorOffset: Int,
        val workingDirectoryUri: String? = null,
        val profileId: String? = null,
        val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ) {
        init {
            require(cursorOffset in 0..commandLine.length) {
                "cursorOffset must be in 0..${commandLine.length}, was $cursorOffset"
            }
            require(commandLine.isTerminalCompletionUtf16Boundary(cursorOffset)) {
                "cursorOffset must not split a UTF-16 surrogate pair, was $cursorOffset"
            }
            require(maxCandidates > 0) { "maxCandidates must be > 0, was $maxCandidates" }
        }

        companion object {
            /**
             * Default number of candidates returned to host UI popups.
             */
            const val DEFAULT_MAX_CANDIDATES: Int = 8
        }
    }

/**
 * Completion candidate category used by hosts for grouping, ranking display,
 * and source-specific styling.
 */
enum class TerminalCompletionCandidateKind {
    /** Top-level executable or shell command candidate. */
    COMMAND,

    /** Command-specific subcommand candidate. */
    SUBCOMMAND,

    /** Command option or flag candidate. */
    OPTION,

    /** Positional argument candidate. */
    ARGUMENT,

    /** File-system path candidate. */
    PATH,

    /** Candidate learned from local command history or session MRU. */
    HISTORY,
}

/**
 * Immutable completion candidate returned by [TerminalCompletionEngine].
 *
 * @property replacementText text to insert into [replacementStartOffset],
 * exclusive [replacementEndOffset].
 * @property displayText primary text shown in suggestion UI.
 * @property detail optional secondary text explaining the candidate.
 * @property source compact source label, such as `spec`, `mru`, or `history`.
 * @property kind semantic candidate category.
 * @property replacementStartOffset inclusive UTF-16 replacement start offset in
 * the original request command line.
 * @property replacementEndOffset exclusive UTF-16 replacement end offset in the
 * original request command line.
 * @property score deterministic ranking score; larger values are better.
 */
data class TerminalCompletionCandidate
    @JvmOverloads
    constructor(
        val replacementText: String,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
        val displayText: String = replacementText,
        val detail: String = "",
        val source: String = "",
        val kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.ARGUMENT,
        val score: Int = 0,
    ) {
        init {
            require(replacementText.isNotEmpty()) { "replacementText must not be empty" }
            require(displayText.isNotEmpty()) { "displayText must not be empty" }
            require(replacementStartOffset >= 0) {
                "replacementStartOffset must be >= 0, was $replacementStartOffset"
            }
            require(replacementEndOffset >= replacementStartOffset) {
                "replacementEndOffset must be >= replacementStartOffset, was $replacementEndOffset"
            }
        }
    }

/**
 * Pure completion source contract for one bounded provider such as static
 * command specs, session MRU, indexed history, path completion, or IDE context.
 */
fun interface TerminalCompletionSource {
    /**
     * Returns candidates produced by this source for [request].
     *
     * Implementations must be deterministic for a stable source snapshot and
     * must not perform shell I/O, UI work, disk I/O, or network I/O. Expensive
     * sources should maintain ready in-memory indexes outside this callback.
     *
     * @param request command-line completion context.
     * @return ordered candidates from this source.
     */
    fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate>

    companion object {
        /**
         * Source that returns no candidates.
         */
        @JvmField
        val NONE: TerminalCompletionSource = TerminalCompletionSource { emptyList() }
    }
}

/**
 * Source registration consumed by merged completion engines.
 *
 * @property source completion source to query.
 * @property priority source-level ranking priority. Larger values rank ahead of
 * lower-priority sources before candidate score is considered.
 */
data class TerminalCompletionSourceEntry
    @JvmOverloads
    constructor(
        val source: TerminalCompletionSource,
        val priority: Int = 0,
    )

/**
 * Pure completion engine contract.
 */
fun interface TerminalCompletionEngine {
    /**
     * Returns a bounded, best-first candidate list for [request].
     *
     * Implementations must not mutate terminal state, block on shell I/O, or
     * perform UI work. Slow sources should maintain a ready in-memory index and
     * answer from that index.
     *
     * @param request command-line completion context.
     * @return ordered completion candidates.
     */
    fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate>

    companion object {
        /**
         * Engine that returns no candidates.
         */
        @JvmField
        val NONE: TerminalCompletionEngine = TerminalCompletionEngine { emptyList() }
    }
}
