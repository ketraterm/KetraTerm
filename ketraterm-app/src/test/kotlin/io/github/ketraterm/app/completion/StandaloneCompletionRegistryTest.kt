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

import io.github.ketraterm.completion.TerminalCommandSpec
import io.github.ketraterm.completion.TerminalCommandStatsCompletionSource
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneCompletionRegistryTest {
    @Test
    fun `session MRU suggestions rank ahead of static spec suggestions`() {
        val registry = registry()
        val provider =
            registry.createProvider(
                sessionId = "session-1",
                profileId = "bash",
                workingDirectoryUriProvider = { "file:///repo" },
            )
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git switch main",
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        val suggestions = provider.suggestions(request("git s"))

        assertEquals("git switch main", suggestions[0].replacementText)
        assertEquals("mru", suggestions[0].source)
        assertEquals(0, suggestions[0].replacementStartOffset)
        assertEquals(5, suggestions[0].replacementEndOffset)
        assertTrue(suggestions.any { it.replacementText == "status" && it.source == "spec" })
    }

    @Test
    fun `persistent stats suggestions rank between session MRU and static specs`() {
        val persistentStats = TerminalCommandStatsCompletionSource()
        persistentStats.recordCommandResult(
            commandLine = "git show --stat",
            successful = true,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 1_000,
        )
        val registry = registry(persistentStatsSource = persistentStats)
        val provider =
            registry.createProvider(
                sessionId = "session-1",
                profileId = "bash",
                workingDirectoryUriProvider = { "file:///repo" },
            )
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git switch main",
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        val suggestions = provider.suggestions(request("git s"))

        assertEquals("git switch main", suggestions[0].replacementText)
        assertEquals("mru", suggestions[0].source)
        assertEquals("git show --stat", suggestions[1].replacementText)
        assertEquals("stats", suggestions[1].source)
        assertTrue(suggestions.any { it.replacementText == "status" && it.source == "spec" })
    }

    @Test
    fun `persistent stats source is shared across provider sessions`() {
        val persistentStats = TerminalCommandStatsCompletionSource()
        persistentStats.recordCommandResult(
            commandLine = "npm test",
            successful = true,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 1_000,
        )
        val registry = registry(specs = emptyList(), persistentStatsSource = persistentStats)
        val first = registry.createProvider("session-1", profileId = "bash") { "file:///repo" }
        val second = registry.createProvider("session-2", profileId = "bash") { "file:///repo" }

        assertEquals(listOf("npm test"), first.suggestions(request("npm")).map { it.replacementText })
        assertEquals(listOf("npm test"), second.suggestions(request("npm")).map { it.replacementText })
    }

    @Test
    fun `provider context boosts matching session MRU commands`() {
        val registry = registry(emptyList())
        val provider =
            registry.createProvider(
                sessionId = "session-1",
                profileId = "bash",
                workingDirectoryUriProvider = { "file:///repo" },
            )
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git switch main",
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git status",
            profileId = "pwsh",
            workingDirectoryUri = "file:///other",
        )

        val suggestions = provider.suggestions(request("git s"))

        assertEquals(listOf("git switch main", "git status"), suggestions.map { it.replacementText })
    }

    @Test
    fun `provider reads latest working-directory context when suggesting`() {
        val registry = registry(emptyList())
        var workingDirectoryUri = "file:///repo"
        val provider =
            registry.createProvider(
                sessionId = "session-1",
                profileId = "bash",
                workingDirectoryUriProvider = { workingDirectoryUri },
            )
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git switch main",
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git status",
            profileId = "bash",
            workingDirectoryUri = "file:///other",
        )

        workingDirectoryUri = "file:///other"

        val suggestions = provider.suggestions(request("git s"))

        assertEquals("git status", suggestions.first().replacementText)
    }

    @Test
    fun `session MRU commands are isolated per provider session`() {
        val registry = registry(emptyList())
        val first = registry.createProvider("session-1")
        val second = registry.createProvider("session-2")

        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git status",
            profileId = "bash",
            workingDirectoryUri = null,
        )

        assertEquals(listOf("git status"), first.suggestions(request("git")).map { it.replacementText })
        assertTrue(second.suggestions(request("git")).isEmpty())
    }

    @Test
    fun `removed session clears already created provider MRU source`() {
        val registry = registry(emptyList())
        val provider = registry.createProvider("session-1")
        registry.recordSuccessfulCommand(
            sessionId = "session-1",
            commandLine = "git status",
            profileId = "bash",
            workingDirectoryUri = null,
        )

        registry.removeSession("session-1")

        assertTrue(provider.suggestions(request("git")).isEmpty())
    }

    @Test
    fun `unregistered session records are ignored`() {
        val registry = registry(emptyList())

        registry.recordSuccessfulCommand(
            sessionId = "missing",
            commandLine = "git status",
            profileId = "bash",
            workingDirectoryUri = null,
        )

        val provider = registry.createProvider("session-1")
        assertTrue(provider.suggestions(request("git")).isEmpty())
    }

    private fun registry(
        specs: List<TerminalCommandSpec> = specs(),
        persistentStatsSource: TerminalCommandStatsCompletionSource? = null,
    ): StandaloneCompletionRegistry =
        StandaloneCompletionRegistry(
            specs = specs,
            persistentStatsSource = persistentStatsSource,
            sessionMruCapacity = 4,
        )

    private fun specs(): List<TerminalCommandSpec> =
        listOf(
            TerminalCommandSpec(
                name = "git",
                subcommands =
                    listOf(
                        TerminalCommandSpec("status", "show status"),
                        TerminalCommandSpec("switch", "switch branches"),
                    ),
            ),
        )

    private fun request(commandText: String): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = commandText.length,
            anchorColumn = commandText.length,
            anchorRow = 0,
        )
}
