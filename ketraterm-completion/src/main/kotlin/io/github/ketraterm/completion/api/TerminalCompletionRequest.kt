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

import io.github.ketraterm.completion.api.TerminalShellCapabilities.Companion.PLAIN
import io.github.ketraterm.completion.api.TerminalShellCapabilities.Companion.POSIX
import io.github.ketraterm.completion.api.TerminalShellCapabilities.Companion.POWERSHELL
import io.github.ketraterm.completion.internal.isTerminalCompletionUtf16Boundary

/**
 * Shell-specific quoting policy for replacement text produced by completion
 * sources.
 */
enum class TerminalShellQuotingPolicy {
    /**
     * Preserve only replacements that are safe without dialect-specific
     * escaping. Unsafe unquoted values are omitted instead of guessed.
     */
    CONSERVATIVE,

    /** POSIX shell escaping using backslash escapes and POSIX quote rules. */
    POSIX,

    /** PowerShell escaping using single-quoted literals by default. */
    POWERSHELL,
}

/**
 * Shell lexical policy used to identify command separators and escapes while
 * resolving the command segment that contains the completion cursor.
 *
 * This policy is explicit because quoting policy alone does not determine
 * operator semantics. Hosts should derive it from authoritative shell profile
 * metadata rather than terminal text or process output.
 */
enum class TerminalShellSyntax {
    /**
     * Conservative whitespace-and-quote tokenization with no command operator
     * segmentation. Use for unknown shells and dialects without an implemented
     * lexical contract.
     */
    PLAIN,

    /** POSIX shell operators and backslash escaping. */
    POSIX,

    /** PowerShell pipeline operators and backtick escaping. */
    POWERSHELL,
}

/**
 * Immutable shell capabilities resolved by the host from authoritative profile
 * metadata before a completion request is created.
 *
 * The shared completion engine never infers shell behavior from command text or
 * profile ids. Hosts should use [PLAIN] for shells without a tested lexical and
 * replacement contract, [POSIX] for the supported POSIX subset, and
 * [POWERSHELL] for PowerShell.
 *
 * @property syntax lexical rules used to resolve the cursor's command segment.
 * @property quoting replacement escaping policy used by candidate sources.
 */
data class TerminalShellCapabilities
    @JvmOverloads
    constructor(
        val syntax: TerminalShellSyntax = TerminalShellSyntax.PLAIN,
        val quoting: TerminalShellQuotingPolicy = TerminalShellQuotingPolicy.CONSERVATIVE,
    ) {
        companion object {
            /** Conservative capabilities for unknown or unsupported shells. */
            @JvmField
            val PLAIN: TerminalShellCapabilities = TerminalShellCapabilities()

            /** Capabilities for the supported POSIX shell subset. */
            @JvmField
            val POSIX: TerminalShellCapabilities =
                TerminalShellCapabilities(
                    syntax = TerminalShellSyntax.POSIX,
                    quoting = TerminalShellQuotingPolicy.POSIX,
                )

            /** Capabilities for PowerShell lexical and quoting rules. */
            @JvmField
            val POWERSHELL: TerminalShellCapabilities =
                TerminalShellCapabilities(
                    syntax = TerminalShellSyntax.POWERSHELL,
                    quoting = TerminalShellQuotingPolicy.POWERSHELL,
                )
        }
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
 * @property profileId optional host profile id used by host-owned ranking data.
 * @property maxCandidates maximum number of candidates to return.
 * @property shellCapabilities resolved shell lexical and replacement policy.
 */
data class TerminalCompletionRequest
    @JvmOverloads
    constructor(
        val commandLine: String,
        val cursorOffset: Int,
        val workingDirectoryUri: String? = null,
        val profileId: String? = null,
        val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        val shellCapabilities: TerminalShellCapabilities = PLAIN,
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
