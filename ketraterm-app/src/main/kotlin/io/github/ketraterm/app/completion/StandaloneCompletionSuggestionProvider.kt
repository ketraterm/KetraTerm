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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest

/**
 * Standalone Swing host adapter from the shared pure completion engine to the
 * reusable Swing shell suggestion popup contract.
 *
 * This adapter is intentionally app-owned. IntelliJ plugin integration should
 * use the same `ketraterm-completion` engine and sources, but map candidates to
 * IntelliJ-owned presentation and acceptance APIs instead of reusing this
 * standalone Swing adapter.
 *
 * @param engine pure completion engine used for suggestion computation.
 * @param contextProvider supplies host-owned completion context for each request.
 */
internal class StandaloneCompletionSuggestionProvider(
    private val engine: TerminalCompletionEngine,
    private val contextProvider: () -> StandaloneCompletionSuggestionContext = { StandaloneCompletionSuggestionContext.EMPTY },
) : SwingShellSuggestionProvider {
    /**
     * Returns Swing popup suggestions for the visible command-line request.
     *
     * The app-owned [contextProvider] is evaluated at request time so live
     * working-directory changes can affect ranking without rebuilding the
     * provider.
     *
     * @param request reusable Swing shell suggestion request.
     * @return ordered Swing suggestions adapted from shared completion candidates.
     */
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
        return engine.complete(completionRequest).map { it.toSwingSuggestion() }
    }

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

/**
 * Standalone host context attached to completion requests.
 *
 * This data deliberately stays in `ketraterm-app`: reusable Swing UI only knows
 * visible command text and cursor position, while the standalone host owns
 * profile and current-working-directory metadata.
 *
 * @property profileId stable standalone profile id, or `null` when unknown.
 * @property workingDirectoryUri latest OSC 7 current-working-directory URI, or
 * `null` before one has been reported.
 * @property shellCapabilities resolved shell lexical and replacement policy.
 */
internal data class StandaloneCompletionSuggestionContext(
    val profileId: String? = null,
    val workingDirectoryUri: String? = null,
    val shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
) {
    companion object {
        /**
         * Empty context used by standalone tests and direct adapters without
         * host metadata.
         */
        val EMPTY = StandaloneCompletionSuggestionContext()
    }
}
