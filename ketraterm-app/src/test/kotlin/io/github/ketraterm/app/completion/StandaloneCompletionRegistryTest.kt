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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandaloneCompletionRegistryTest {
    @Test
    fun `session MRU competes as a semantic subcommand without full-line presentation`() =
        runBlocking {
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

            val suggestions = provider.suggestions(request("git s")).last()

            val mruSuggestion = suggestions.first()
            assertEquals("switch main", mruSuggestion.replacementText)
            assertEquals("mru", mruSuggestion.source)
            assertEquals(4, mruSuggestion.replacementStartOffset)
            assertEquals(5, mruSuggestion.replacementEndOffset)
            assertTrue(suggestions.any { it.replacementText == "status" && it.source == "spec" })
        }

    @Test
    fun `directory path suggestions rank ahead of command MRU after cd space`() =
        runBlocking {
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

                val suggestions = provider.suggestions(request("cd ")).last()

                assertEquals("alpha/", suggestions.first().replacementText)
                assertEquals("path", suggestions.first().source)
                assertTrue(suggestions.none { it.replacementText == ".hidden/" })
                assertTrue(suggestions.none { it.replacementText == "README.md" })
                assertTrue(suggestions.any { it.replacementText == "remembered" && it.source == "mru" })
                registry.close()
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `learned nested directory is token-local and outranks its unused parent path`() =
        runBlocking {
            val directory = Files.createTempDirectory("ketraterm-learned-directory")
            try {
                Files.createDirectories(directory.resolve("IdeaProjects/KetraTerm"))
                val persistentStats = TerminalCompletionLearningStore()
                persistentStats.replaceSnapshot(
                    TerminalCommandCompletionStatsSnapshot(
                        commandStats =
                            listOf(
                                TerminalCommandCompletionStats(
                                    commandLine = "cd IdeaProjects/KetraTerm/",
                                    profileId = "pwsh",
                                    workingDirectoryUri = directory.toUri().toString(),
                                    useCount = 8,
                                    successCount = 8,
                                    acceptedCount = 2,
                                    lastUsedEpochMillis = System.currentTimeMillis(),
                                ),
                            ),
                    ),
                )
                val registry = registry(specs = TerminalCommandSpecs.defaults(), persistentStatsSource = persistentStats)
                val provider =
                    registry.createProvider(
                        sessionId = "learned-directory",
                        profileId = "pwsh",
                        workingDirectoryUriProvider = { directory.toUri().toString() },
                    )

                val suggestions = provider.suggestions(request("cd I")).last()

                assertEquals("IdeaProjects/KetraTerm/", suggestions.first().replacementText)
                assertEquals("IdeaProjects/KetraTerm/", suggestions.first().displayText)
                assertEquals("mru", suggestions.first().source)
                assertEquals("cd IdeaProjects/KetraTerm/", suggestions.first().commandTextAfterReplacement(request("cd I")))
                assertTrue(suggestions.none { it.source == "stats" })
                registry.close()
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `bare path entries do not pollute command subcommand completion`() =
        runBlocking {
            val directory = Files.createTempDirectory("ketraterm-completion")
            try {
                Files.createDirectory(directory.resolve("status"))
                val provider =
                    registry().createProvider(
                        sessionId = "session-1",
                        profileId = "bash",
                        workingDirectoryUriProvider = { directory.toUri().toString() },
                    )

                val suggestions = provider.suggestions(request("git s")).last()

                assertTrue(suggestions.any { it.replacementText == "status" && it.source == "spec" })
                assertTrue(suggestions.none { it.source == "path" })
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `persistent stats supply learned fallback without a standalone stats source`() =
        runBlocking {
            val persistentStats = TerminalCompletionLearningStore()
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

            val request = request("git s")
            val suggestions = provider.suggestions(request).last()
            val outcomes = suggestions.mapNotNull { it.commandTextAfterReplacement(request) }

            assertTrue("git switch main" in outcomes)
            assertTrue("git show --stat" in outcomes)
            assertTrue("git status" in outcomes)
            assertTrue(suggestions.none { it.source == "stats" })
            assertEquals("mru", suggestions.single { it.commandTextAfterReplacement(request) == "git show --stat" }.source)
        }

    @Test
    fun `persistent stats source is shared across provider sessions`() =
        runBlocking {
            val persistentStats = TerminalCompletionLearningStore()
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

            assertEquals(listOf("npm test"), first.suggestions(request("npm")).last().map { it.replacementText })
            assertEquals(listOf("npm test"), second.suggestions(request("npm")).last().map { it.replacementText })
        }

    @Test
    fun `learned command stats boost matching suggestions`() =
        runBlocking {
            val persistentStats = TerminalCompletionLearningStore()
            persistentStats.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            TerminalCommandCompletionStats(
                                commandLine = "git switch",
                                acceptedCount = 10,
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 1_000,
                            ),
                            TerminalCommandCompletionStats(
                                commandLine = "git status",
                                dismissedCount = 10,
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 1_000,
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

            val suggestions = provider.suggestions(request("git ")).last()

            assertEquals(listOf("switch", "status"), suggestions.map { it.replacementText }.take(2))
        }

    @Test
    fun `learned command stats demote repeatedly dismissed suggestions`() =
        runBlocking {
            val persistentStats = TerminalCompletionLearningStore()
            persistentStats.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            TerminalCommandCompletionStats(
                                commandLine = "git status",
                                dismissedCount = 20,
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 1_000,
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

            val suggestions = provider.suggestions(request("git ")).last()

            assertEquals("switch", suggestions.first().replacementText)
            assertEquals("spec", suggestions.first().source)
        }

    @Test
    fun `provider context boosts matching session MRU commands`() =
        runBlocking {
            val registry = registry(listOf(TerminalCommandSpec("git")))
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

            val request = request("git s")
            val suggestions = provider.suggestions(request).last()

            assertEquals(
                listOf("git switch main", "git status"),
                suggestions.filter { it.source == "mru" }.mapNotNull { it.commandTextAfterReplacement(request) },
            )
        }

    @Test
    fun `provider reads latest working-directory context when suggesting`() =
        runBlocking {
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

            val request = request("git s")
            val suggestions = provider.suggestions(request).last()

            assertEquals("git status", suggestions.first().commandTextAfterReplacement(request))
        }

    @Test
    fun `session MRU commands are isolated per provider session`() =
        runBlocking {
            val registry = registry(emptyList())
            val first = registry.createProvider("session-1")
            val second = registry.createProvider("session-2")

            registry.recordSuccessfulCommand(
                sessionId = "session-1",
                commandLine = "git status",
                profileId = "bash",
                workingDirectoryUri = null,
            )

            assertEquals(listOf("git status"), first.suggestions(request("git")).last().map { it.replacementText })
            assertTrue(second.suggestions(request("git")).last().isEmpty())
        }

    @Test
    fun `removed session clears already created provider MRU source`() =
        runBlocking {
            val registry = registry(emptyList())
            val provider = registry.createProvider("session-1")
            registry.recordSuccessfulCommand(
                sessionId = "session-1",
                commandLine = "git status",
                profileId = "bash",
                workingDirectoryUri = null,
            )

            registry.removeSession("session-1")

            assertTrue(provider.suggestions(request("git")).last().isEmpty())
        }

    @Test
    fun `unregistered session records are ignored`() =
        runBlocking {
            val registry = registry(emptyList())

            registry.recordSuccessfulCommand(
                sessionId = "missing",
                commandLine = "git status",
                profileId = "bash",
                workingDirectoryUri = null,
            )

            val provider = registry.createProvider("session-1")
            assertTrue(provider.suggestions(request("git")).last().isEmpty())
        }

    @Test
    fun `closed registry rejects new providers`() {
        val registry = registry(emptyList())

        registry.close()
        registry.close()

        assertFailsWith<IllegalStateException> { registry.createProvider("late-session") }
    }

    private fun registry(
        specs: List<TerminalCommandSpec> = specs(),
        persistentStatsSource: TerminalCompletionLearningStore? = null,
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
