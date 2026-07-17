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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest

/**
 * Host-neutral adapter from the pure completion engine to Swing suggestions.
 *
 * The adapter reads [contextProvider] for every request so hosts can publish
 * current profile and working-directory state without rebuilding the engine.
 *
 * @param engine pure completion engine queried on the Swing caller's thread.
 * @param contextProvider supplier for current host-owned request metadata.
 */
class SwingCompletionSuggestionProvider(
    private val engine: TerminalCompletionEngine,
    private val contextProvider: () -> SwingCompletionContext = { SwingCompletionContext.EMPTY },
) : SwingShellSuggestionProvider {
    /** Returns candidates adapted to the reusable Swing popup contract. */
    override fun suggestions(request: SwingShellSuggestionRequest): List<SwingShellSuggestion> {
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
                return emptyList()
            }
        return engine.complete(completionRequest).map { candidate -> candidate.toSwingSuggestion() }
    }

    private companion object {
        private fun TerminalCompletionCandidate.toSwingSuggestion(): SwingShellSuggestion =
            SwingShellSuggestion(
                replacementText = replacementText,
                replacementStartOffset = replacementStartOffset,
                replacementEndOffset = replacementEndOffset,
                source = source,
                kind = kind.name,
                displayText = displayText,
                detail = detail,
            )
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
