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
package io.github.ketraterm.app.history

import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest

/**
 * Standalone host provider that ranks persisted command-history entries for
 * shell suggestions.
 *
 * The provider consumes an already-ready in-memory snapshot. It does not flush
 * the history writer, perform disk I/O, parse terminal output, or mutate the
 * session. Matching is prefix-based against the text before the cursor, and
 * newest unique commands win.
 *
 * @param profileId terminal profile id whose history entries should be suggested.
 * @param historySnapshot supplier for the latest in-memory command history.
 * @param maxSuggestions maximum suggestions to return.
 */
internal class CommandHistorySuggestionProvider(
    private val profileId: String,
    private val historySnapshot: () -> List<CommandHistoryEntry>,
    private val maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
) : SwingShellSuggestionProvider {
    init {
        require(profileId.isNotEmpty()) { "profileId must not be empty" }
        require(maxSuggestions > 0) { "maxSuggestions must be > 0, was $maxSuggestions" }
    }

    override fun suggestions(request: SwingShellSuggestionRequest): List<SwingShellSuggestion> {
        val prefix = request.commandText.substring(0, request.cursorOffset).trimStart()
        val history = historySnapshot()
        if (history.isEmpty()) return emptyList()

        val result = ArrayList<SwingShellSuggestion>(minOf(maxSuggestions, history.size))
        val seenCommands = HashSet<String>(maxSuggestions * 2)
        var index = history.size - 1
        while (index >= 0 && result.size < maxSuggestions) {
            val entry = history[index]
            if (entry.profileId == profileId && matchesPrefix(entry.command, prefix) && seenCommands.add(entry.command)) {
                result += suggestionFor(entry)
            }
            index--
        }
        return result
    }

    private fun matchesPrefix(
        command: String,
        prefix: String,
    ): Boolean {
        if (command.isEmpty()) return false
        if (prefix.isEmpty()) return true
        if (command.length == prefix.length && command.equals(prefix, ignoreCase = true)) return false
        return command.startsWith(prefix, ignoreCase = true)
    }

    private fun suggestionFor(entry: CommandHistoryEntry): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = entry.command,
            displayText = entry.command,
            detail = detailFor(entry),
            source = SOURCE_HISTORY,
        )

    private fun detailFor(entry: CommandHistoryEntry): String {
        val directory = entry.workingDirectoryUri.orEmpty()
        val exit = entry.exitCode?.let { "exit $it" }.orEmpty()
        return when {
            exit.isNotEmpty() && directory.isNotEmpty() -> "$exit | $directory"
            exit.isNotEmpty() -> exit
            else -> directory
        }
    }

    private companion object {
        private const val DEFAULT_MAX_SUGGESTIONS = 8
        private const val SOURCE_HISTORY = "history"
    }
}
