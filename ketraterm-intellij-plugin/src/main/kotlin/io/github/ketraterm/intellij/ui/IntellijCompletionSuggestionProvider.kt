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
package io.github.ketraterm.intellij.ui

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest

/** IntelliJ-owned adapter from the shared completion engine to Swing suggestions. */
internal class IntellijCompletionSuggestionProvider(
    private val engine: TerminalCompletionEngine,
    private val contextProvider: () -> IntellijCompletionContext,
) : SwingShellSuggestionProvider {
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

/** Immutable IntelliJ-owned context attached to one completion request. */
internal data class IntellijCompletionContext(
    val profileId: String,
    val workingDirectoryUri: String?,
    val shellCapabilities: TerminalShellCapabilities,
)
