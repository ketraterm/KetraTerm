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
 * Shell-specific quoting policy for replacement text produced by completion
 * sources.
 */
enum class TerminalShellQuotingPolicy {
    /** Infer a safe policy from request metadata, falling back to POSIX style. */
    AUTO,

    /** POSIX shell escaping using backslash escapes and POSIX quote rules. */
    POSIX,

    /** PowerShell escaping using single-quoted literals by default. */
    POWERSHELL,
}

/**
 * Immutable command-line context used to request completion candidates.
 *
 * Hosts should construct one request per popup refresh from the visible command
 * line and live host metadata. The completion module treats the request as
 * read-only input and never mutates terminal state.
 *
 * @property commandLine full command-line text known to the host.
 * @property cursorOffset UTF-16 cursor offset within [commandLine]. The offset
 * must be a scalar boundary and must not split a surrogate pair.
 * @property workingDirectoryUri optional current working directory URI.
 * @property profileId optional host profile id, such as `pwsh`, `bash`, or `zsh`.
 * @property maxCandidates maximum number of candidates to return.
 * @property shellQuotingPolicy shell escaping policy for replacement text.
 */
data class TerminalCompletionRequest
    @JvmOverloads
    constructor(
        val commandLine: String,
        val cursorOffset: Int,
        val workingDirectoryUri: String? = null,
        val profileId: String? = null,
        val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        val shellQuotingPolicy: TerminalShellQuotingPolicy = TerminalShellQuotingPolicy.AUTO,
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
