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

import io.github.ketraterm.completion.TerminalCompletionCandidate
import io.github.ketraterm.completion.TerminalCompletionEngine
import io.github.ketraterm.completion.TerminalCompletionRequest
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest

/**
 * Standalone host adapter from the pure completion engine to the reusable Swing
 * shell suggestion popup contract.
 *
 * @param engine pure completion engine used for suggestion computation.
 */
internal class CompletionSuggestionProvider(
    private val engine: TerminalCompletionEngine,
) : SwingShellSuggestionProvider {
    override fun suggestions(request: SwingShellSuggestionRequest): List<SwingShellSuggestion> {
        val completionRequest =
            TerminalCompletionRequest(
                commandLine = request.commandText,
                cursorOffset = request.cursorOffset,
            )
        return engine.complete(completionRequest).map { it.toSwingSuggestion() }
    }

    private fun TerminalCompletionCandidate.toSwingSuggestion(): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = replacementText,
            displayText = displayText,
            detail = detail,
            source = source,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
        )
}
