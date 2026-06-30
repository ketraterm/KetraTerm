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

import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandHistorySuggestionProviderTest {
    @Test
    fun `returns newest unique commands matching typed prefix for profile`() {
        val provider =
            provider(
                listOf(
                    entry("bash", "git status", finishedAt = 10),
                    entry("pwsh", "git switch main", finishedAt = 20),
                    entry("bash", "git status", finishedAt = 30),
                    entry("bash", "git switch feature", finishedAt = 40),
                    entry("bash", "gradle test", finishedAt = 50),
                ),
            )

        val suggestions = provider.suggestions(request("git s"))

        assertEquals(
            listOf("git switch feature", "git status"),
            suggestions.map { it.replacementText },
        )
        assertEquals(listOf("history", "history"), suggestions.map { it.source })
    }

    @Test
    fun `empty prefix returns newest unique commands`() {
        val provider =
            provider(
                listOf(
                    entry("bash", "one", finishedAt = 10),
                    entry("bash", "two", finishedAt = 20),
                    entry("bash", "one", finishedAt = 30),
                ),
            )

        assertEquals(
            listOf("one", "two"),
            provider.suggestions(request("")).map { it.replacementText },
        )
    }

    @Test
    fun `does not suggest exact current command`() {
        val provider =
            provider(
                listOf(
                    entry("bash", "git status", finishedAt = 10),
                    entry("bash", "git status --short", finishedAt = 20),
                ),
            )

        assertEquals(
            listOf("git status --short"),
            provider.suggestions(request("git status")).map { it.replacementText },
        )
    }

    @Test
    fun `uses text before cursor as prefix`() {
        val provider =
            provider(
                listOf(
                    entry("bash", "git status", finishedAt = 10),
                    entry("bash", "grep TODO", finishedAt = 20),
                ),
            )

        assertEquals(
            listOf("git status"),
            provider.suggestions(request("git --ignored", cursorOffset = 3)).map { it.replacementText },
        )
    }

    @Test
    fun `caps result count`() {
        val provider =
            provider(
                List(12) { index ->
                    entry("bash", "cmd-$index", finishedAt = index.toLong())
                },
                maxSuggestions = 3,
            )

        assertEquals(
            listOf("cmd-11", "cmd-10", "cmd-9"),
            provider.suggestions(request("")).map { it.replacementText },
        )
    }

    @Test
    fun `formats exit code and working directory detail`() {
        val provider =
            provider(
                listOf(
                    entry("bash", "make test", workingDirectoryUri = "file:///repo", exitCode = 7),
                ),
            )

        val suggestion = provider.suggestions(request("make")).single()

        assertEquals("exit 7 | file:///repo", suggestion.detail)
    }

    private fun provider(
        history: List<CommandHistoryEntry>,
        maxSuggestions: Int = 8,
    ): CommandHistorySuggestionProvider =
        CommandHistorySuggestionProvider(
            profileId = "bash",
            historySnapshot = { history },
            maxSuggestions = maxSuggestions,
        )

    private fun request(
        commandText: String,
        cursorOffset: Int = commandText.length,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = cursorOffset,
            anchorRow = 0,
        )

    private fun entry(
        profileId: String,
        command: String,
        finishedAt: Long = 1,
        workingDirectoryUri: String? = null,
        exitCode: Int? = 0,
    ): CommandHistoryEntry =
        CommandHistoryEntry(
            profileId = profileId,
            command = command,
            workingDirectoryUri = workingDirectoryUri,
            exitCode = exitCode,
            startedAtEpochMillis = finishedAt - 1,
            finishedAtEpochMillis = finishedAt,
        )
}
