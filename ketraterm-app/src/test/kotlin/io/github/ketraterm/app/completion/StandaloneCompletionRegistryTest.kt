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

import io.github.ketraterm.completion.api.TerminalCommandStatsCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneCompletionRegistryTest {
    @Test
    fun `static subcommand suggestions rank ahead of session MRU in subcommand position`() {
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

        assertEquals("status", suggestions[0].replacementText)
        assertEquals("spec", suggestions[0].source)
        val mruSuggestion = suggestions.single { it.replacementText == "git switch main" && it.source == "mru" }
        assertEquals(0, mruSuggestion.replacementStartOffset)
        assertEquals(5, mruSuggestion.replacementEndOffset)
    }

    @Test
    fun `directory path suggestions rank ahead of command MRU after cd space`() {
        val directory = Files.createTempDirectory("ketraterm-completion")
        try {
            Files.createDirectory(directory.resolve("alpha"))
            Files.createDirectory(directory.resolve(".hidden"))
            Files.writeString(directory.resolve("README.md"), "not a directory")
            val registry = registry(specs = TerminalCommandSpecs.defaults())
            val provider =
                registry.createProvider(
                    sessionId = "session-1",
                    profileId = "pwsh",
                    workingDirectoryUriProvider = { directory.toUri().toString() },
                )
            registry.recordSuccessfulCommand(
                sessionId = "session-1",
                commandLine = "cd remembered",
                profileId = "pwsh",
                workingDirectoryUri = directory.toUri().toString(),
            )

            val suggestions = provider.suggestions(request("cd "))

            assertEquals("alpha/", suggestions.first().replacementText)
            assertEquals("path", suggestions.first().source)
            assertTrue(suggestions.none { it.replacementText == ".hidden/" })
            assertTrue(suggestions.none { it.replacementText == "README.md" })
            assertTrue(suggestions.any { it.replacementText == "cd remembered" && it.source == "mru" })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bare path entries do not pollute command subcommand completion`() {
        val directory = Files.createTempDirectory("ketraterm-completion")
        try {
            Files.createDirectory(directory.resolve("status"))
            val provider =
                registry().createProvider(
                    sessionId = "session-1",
                    profileId = "bash",
                    workingDirectoryUriProvider = { directory.toUri().toString() },
                )

            val suggestions = provider.suggestions(request("git s"))

            assertTrue(suggestions.any { it.replacementText == "status" && it.source == "spec" })
            assertTrue(suggestions.none { it.source == "path" })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persistent stats suggestions rank below static subcommands and session MRU in subcommand position`() {
        val persistentStats = TerminalCompletionSources.commandStats()
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

        assertEquals("status", suggestions[0].replacementText)
        assertEquals("spec", suggestions[0].source)
        assertTrue(suggestions.indexOfFirst { it.replacementText == "git switch main" && it.source == "mru" } > 0)
        assertTrue(suggestions.indexOfFirst { it.replacementText == "git show --stat" && it.source == "stats" } > 0)
        assertTrue(
            suggestions.indexOfFirst { it.replacementText == "git switch main" && it.source == "mru" } <
                suggestions.indexOfFirst { it.replacementText == "git show --stat" && it.source == "stats" },
        )
    }

    @Test
    fun `persistent stats source is shared across provider sessions`() {
        val persistentStats = TerminalCompletionSources.commandStats()
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
    fun `shape stats boost matching static spec suggestions`() {
        val persistentStats = TerminalCompletionSources.commandStats()
        persistentStats.replaceSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                shapeStats =
                    listOf(
                        shapeStats(
                            commandLine = "git switch main",
                            acceptedCount = 4,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                        ),
                    ),
            ),
        )
        val provider =
            registry(persistentStatsSource = persistentStats)
                .createProvider(
                    sessionId = "session-1",
                    profileId = "bash",
                    workingDirectoryUriProvider = { "file:///repo" },
                )

        val suggestions = provider.suggestions(request("git "))

        assertEquals("switch", suggestions.first().replacementText)
        assertEquals("spec", suggestions.first().source)
    }

    @Test
    fun `shape stats demote repeatedly dismissed static spec suggestions`() {
        val persistentStats = TerminalCompletionSources.commandStats()
        persistentStats.replaceSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                shapeStats =
                    listOf(
                        shapeStats(
                            commandLine = "git status",
                            dismissedCount = 4,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                        ),
                    ),
            ),
        )
        val provider =
            registry(persistentStatsSource = persistentStats)
                .createProvider(
                    sessionId = "session-1",
                    profileId = "bash",
                    workingDirectoryUriProvider = { "file:///repo" },
                )

        val suggestions = provider.suggestions(request("git "))

        assertEquals("switch", suggestions.first().replacementText)
        assertEquals("spec", suggestions.first().source)
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

        assertEquals(
            listOf("git switch main", "git status"),
            suggestions.filter { it.source == "mru" }.map { it.replacementText },
        )
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

        assertEquals("git status", suggestions.first { it.source == "mru" }.replacementText)
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

    private fun shapeStats(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
    ): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape =
                when (commandLine) {
                    "git switch main" ->
                        TerminalCommandLineShape(
                            executable = "git",
                            subcommands = listOf("switch"),
                            positionalArgumentCount = 1,
                        )
                    "git status" ->
                        TerminalCommandLineShape(
                            executable = "git",
                            subcommands = listOf("status"),
                        )
                    else -> error("Unsupported test command: $commandLine")
                },
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = 100,
        )

    private fun request(commandText: String): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = commandText.length,
            anchorColumn = commandText.length,
            anchorRow = 0,
        )
}
