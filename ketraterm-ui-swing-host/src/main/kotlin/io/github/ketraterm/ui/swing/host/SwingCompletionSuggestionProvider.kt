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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.*

/**
 * Host-neutral adapter from the pure completion engine to Swing suggestions.
 *
 * The adapter reads [contextProvider] for every request so hosts can publish
 * current profile and working-directory state without rebuilding the engine.
 *
 * @param engine pure progressive completion engine. This adapter does not select
 * a dispatcher; the owning suggestion caller controls its coroutine context.
 * @param contextProvider supplier for current host-owned request metadata.
 */
class SwingCompletionSuggestionProvider(
    private val engine: TerminalCompletionEngine,
    private val contextProvider: () -> SwingCompletionContext = { SwingCompletionContext.EMPTY },
) : SwingShellSuggestionProvider {
    /**
     * Returns progressive candidate snapshots adapted to the reusable Swing popup contract.
     *
     * @param request visible command text, cursor, and popup anchor supplied by Swing.
     * @return cold ordered Swing suggestion snapshots, or one empty snapshot when conversion is invalid.
     */
    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> {
        val context = contextProvider()
        val completionRequest =
            try {
                TerminalCompletionRequest(
                    commandLine = request.commandText,
                    cursorOffset = request.cursorOffset,
                    workingDirectoryUri = context.workingDirectoryUri,
                    profileId = context.profileId,
                    shellCapabilities = context.shellCapabilities,
                )
            } catch (_: IllegalArgumentException) {
                return flowOf(emptyList())
            }
        return engine.completions(completionRequest).map { candidates -> candidates.map { it.toSwingSuggestion() } }
    }

    private companion object {
        private fun TerminalCompletionCandidate.toSwingSuggestion(): SwingShellSuggestion =
            SwingShellSuggestion(
                replacementText = replacementText,
                replacementStartOffset = replacementStartOffset,
                replacementEndOffset = replacementEndOffset,
                source = source,
                sourceDisplayText = source.toDisplayText(),
                kind = kind.name,
                displayText = displayText,
                detail = detail,
                accentRole =
                    when (kind) {
                        TerminalCompletionCandidateKind.COMMAND,
                        TerminalCompletionCandidateKind.SUBCOMMAND,
                        -> SwingShellSuggestionAccentRole.COMMAND

                        TerminalCompletionCandidateKind.PATH -> SwingShellSuggestionAccentRole.PATH
                        TerminalCompletionCandidateKind.OPTION -> SwingShellSuggestionAccentRole.OPTION
                        TerminalCompletionCandidateKind.HISTORY -> SwingShellSuggestionAccentRole.HISTORY
                        else -> SwingShellSuggestionAccentRole.OTHER
                    },
                matchedRanges =
                    SwingShellSuggestionMatchRanges.fromPackedOffsets(
                        displayText,
                        matchedRanges.copyPackedOffsets(),
                    ),
            )

        private fun String.toDisplayText(): String {
            val normalized = trim().boundedSourceLabel().lowercase(Locale.ROOT).boundedSourceLabel()
            return when {
                normalized == "spec" -> "Built-in"
                normalized == "mru" || normalized == "history" -> "Recent"
                normalized == "stats" -> "Learned"
                normalized == "observed" -> "Session"
                normalized.containsIdentifierToken("gradle") -> "Gradle"
                normalized.containsIdentifierToken("git") -> "Git"
                normalized.containsIdentifierToken("project") && normalized.containsIdentifierToken("file") -> "Project"
                normalized.containsIdentifierToken("path") || normalized.containsIdentifierToken("file") -> "Path"
                else ->
                    normalized
                        .removePrefix("intellij-")
                        .humanizeSourceIdentifier()
                        .replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
                        .boundedSourceLabel()
            }
        }

        private fun String.containsIdentifierToken(token: String): Boolean {
            var startIndex = indexOf(token)
            while (startIndex >= 0) {
                val endIndex = startIndex + token.length
                val startsAtBoundary = startIndex == 0 || !this[startIndex - 1].isLetterOrDigit()
                val endsAtBoundary = endIndex == length || !this[endIndex].isLetterOrDigit()
                if (startsAtBoundary && endsAtBoundary) return true
                startIndex = indexOf(token, startIndex + 1)
            }
            return false
        }

        private fun String.humanizeSourceIdentifier(): String {
            val result = StringBuilder(length.coerceAtMost(MAXIMUM_SOURCE_LABEL_CODE_UNITS))
            var separatorPending = false
            for (character in this) {
                if (character == '-' || character == '_' || character.isWhitespace()) {
                    separatorPending = result.isNotEmpty()
                } else {
                    if (separatorPending) result.append(' ')
                    result.append(character)
                    separatorPending = false
                }
            }
            return result.toString().ifEmpty { "other" }
        }

        private fun String.boundedSourceLabel(): String {
            if (length <= MAXIMUM_SOURCE_LABEL_CODE_UNITS) return this
            var retainedLength = MAXIMUM_SOURCE_LABEL_CODE_UNITS - ELLIPSIS.length
            if (
                Character.isHighSurrogate(this[retainedLength - 1]) && Character.isLowSurrogate(this[retainedLength])
            ) {
                retainedLength--
            }
            return substring(0, retainedLength).trimEnd() + ELLIPSIS
        }

        private const val MAXIMUM_SOURCE_LABEL_CODE_UNITS = 128
        private const val ELLIPSIS = "…"
    }
}

/**
 * Immutable host metadata attached to a Swing completion request.
 *
 * @property profileId stable host profile id, or `null` when unknown.
 * @property workingDirectoryUri current authoritative working-directory URI.
 * @property shellCapabilities explicit shell lexical and replacement policy.
 */
data class SwingCompletionContext
    @JvmOverloads
    constructor(
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ) {
        companion object {
            /** Empty context for hosts without profile or directory metadata. */
            @JvmField
            val EMPTY: SwingCompletionContext = SwingCompletionContext()
        }
    }
