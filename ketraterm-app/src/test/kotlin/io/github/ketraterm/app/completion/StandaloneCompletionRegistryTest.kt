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
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class StandaloneCompletionRegistryTest {
    private val registries = ArrayList<StandaloneCompletionRegistry>()
    private val persistenceDirectory = Files.createTempDirectory("ketraterm-registry-test")
    private var eventTime = 0L

    @AfterTest
    fun closeRegistries() {
        runBlocking {
            registries.forEach { it.closeAndFlush() }
        }
        persistenceDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `learned command competes as a semantic subcommand without full-line presentation`() =
        runBlocking {
            val registry = registry()
            val provider =
                registry.openProvider(
                    profileId = "bash",
                    workingDirectoryUriProvider = { "file:///repo" },
                )
            registry.recordSuccess(
                commandLine = "git switch main",
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
            )

            val suggestions = provider.suggestions(request("git s")).last()

            val learnedSuggestion = suggestions.first()
            assertEquals("switch main", learnedSuggestion.replacementText)
            assertEquals("learned", learnedSuggestion.source)
            assertEquals(4, learnedSuggestion.replacementStartOffset)
            assertEquals(5, learnedSuggestion.replacementEndOffset)
            assertTrue(suggestions.any { it.replacementText == "status" && it.source == "spec" })
        }

    @Test
    fun `directory and learned command suggestions coexist after cd space`() =
        runBlocking {
            val directory = Files.createTempDirectory("ketraterm-completion")
            try {
                Files.createDirectory(directory.resolve("alpha"))
                Files.createDirectory(directory.resolve(".hidden"))
                Files.writeString(directory.resolve("README.md"), "not a directory")
                val registry = registry(specs = TerminalCommandSpecs.defaults())
                val provider =
                    registry.openProvider(
                        profileId = "pwsh",
                        workingDirectoryUriProvider = { directory.toUri().toString() },
                    )
                registry.recordSuccess(
                    commandLine = "cd remembered",
                    profileId = "pwsh",
                    workingDirectoryUri = directory.toUri().toString(),
                )

                val suggestions = provider.suggestions(request("cd ")).last()

                assertTrue(suggestions.any { it.replacementText == "alpha/" && it.source == "path" })
                assertTrue(suggestions.none { it.replacementText == ".hidden/" })
                assertTrue(suggestions.none { it.replacementText == "README.md" })
                assertTrue(suggestions.any { it.replacementText == "remembered" && it.source == "learned" })
                registry.closeAndFlush()
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
                persistentStats.mergeSnapshot(
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
                val registry = registry(specs = TerminalCommandSpecs.defaults(), learningStore = persistentStats)
                val provider =
                    registry.openProvider(
                        profileId = "pwsh",
                        workingDirectoryUriProvider = { directory.toUri().toString() },
                    )

                val suggestions = provider.suggestions(request("cd I")).last()

                assertEquals("IdeaProjects/KetraTerm/", suggestions.first().replacementText)
                assertEquals("IdeaProjects/KetraTerm/", suggestions.first().displayText)
                assertEquals("learned", suggestions.first().source)
                assertEquals("cd IdeaProjects/KetraTerm/", suggestions.first().commandTextAfterReplacement(request("cd I")))
                assertTrue(suggestions.none { it.source == "stats" })
                registry.closeAndFlush()
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
                    registry().openProvider(
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
            val registry = registry(learningStore = persistentStats)
            val provider =
                registry.openProvider(
                    profileId = "bash",
                    workingDirectoryUriProvider = { "file:///repo" },
                )
            registry.recordSuccess(
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
            assertEquals("learned", suggestions.single { it.commandTextAfterReplacement(request) == "git show --stat" }.source)
        }

    @Test
    fun `shared learning is visible to every provider`() =
        runBlocking {
            val persistentStats = TerminalCompletionLearningStore()
            persistentStats.recordCommandResult(
                commandLine = "npm test",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 1_000,
            )
            val registry = registry(specs = emptyList(), learningStore = persistentStats)
            val first = registry.openProvider(profileId = "bash") { "file:///repo" }
            val second = registry.openProvider(profileId = "bash") { "file:///repo" }

            assertEquals(listOf("npm test"), first.suggestions(request("npm")).last().map { it.replacementText })
            assertEquals(listOf("npm test"), second.suggestions(request("npm")).last().map { it.replacementText })
        }

    @Test
    fun `learned command stats boost matching suggestions`() =
        runBlocking {
            val persistentStats = TerminalCompletionLearningStore()
            persistentStats.mergeSnapshot(
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
                registry(learningStore = persistentStats)
                    .openProvider(
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
            persistentStats.mergeSnapshot(
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
                registry(learningStore = persistentStats)
                    .openProvider(
                        profileId = "bash",
                        workingDirectoryUriProvider = { "file:///repo" },
                    )

            val suggestions = provider.suggestions(request("git ")).last()

            assertEquals("switch", suggestions.first().replacementText)
            assertEquals("spec", suggestions.first().source)
        }

    @Test
    fun `provider context boosts matching learned commands`() =
        runBlocking {
            val registry = registry(listOf(TerminalCommandSpec("git")))
            val provider =
                registry.openProvider(
                    profileId = "bash",
                    workingDirectoryUriProvider = { "file:///repo" },
                )
            registry.recordSuccess(
                commandLine = "git switch main",
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
            )
            registry.recordSuccess(
                commandLine = "git status",
                profileId = "pwsh",
                workingDirectoryUri = "file:///other",
            )

            val request = request("git s")
            val suggestions = provider.suggestions(request).last()

            assertEquals(
                listOf("git switch main", "git status"),
                suggestions.filter { it.source == "learned" }.mapNotNull { it.commandTextAfterReplacement(request) },
            )
        }

    @Test
    fun `provider reads latest working-directory context when suggesting`() =
        runBlocking {
            val registry = registry(emptyList())
            var workingDirectoryUri = "file:///repo"
            val provider =
                registry.openProvider(
                    profileId = "bash",
                    workingDirectoryUriProvider = { workingDirectoryUri },
                )
            registry.recordSuccess(
                commandLine = "git switch main",
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
            )
            registry.recordSuccess(
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
    fun `finished commands become shared learned suggestions`() =
        runBlocking {
            val registry = registry(emptyList())
            val first = registry.openProvider()
            val second = registry.openProvider()

            registry.recordSuccess(
                commandLine = "git status",
                profileId = "bash",
                workingDirectoryUri = null,
            )

            assertEquals(listOf("git status"), first.suggestions(request("git")).last().map { it.replacementText })
            assertEquals(listOf("git status"), second.suggestions(request("git")).last().map { it.replacementText })
        }

    @Test
    fun `finished commands contribute global learning before a provider exists`() =
        runBlocking {
            val registry = registry(emptyList())

            registry.recordSuccess(
                commandLine = "git status",
                profileId = "bash",
                workingDirectoryUri = null,
            )

            val provider = registry.openProvider()
            assertEquals(listOf("git status"), provider.suggestions(request("git")).last().map { it.replacementText })
        }

    @Test
    fun `dynamic working directory transitions update contextual ranking without session restart`() =
        runBlocking {
            val registry = registry(listOf(TerminalCommandSpec("git")))
            var currentDirectory = "file:///repo-a"
            val provider =
                registry.openProvider(
                    profileId = "bash",
                    workingDirectoryUriProvider = { currentDirectory },
                )

            registry.recordSuccess(
                commandLine = "git checkout feature-a",
                profileId = "bash",
                workingDirectoryUri = "file:///repo-a",
            )
            registry.recordSuccess(
                commandLine = "git checkout feature-b",
                profileId = "bash",
                workingDirectoryUri = "file:///repo-b",
            )

            // When in repo-a
            val repoASuggestions = provider.suggestions(request("git ")).last().map { it.replacementText }
            assertEquals("checkout feature-a", repoASuggestions.first())

            // Seamless OSC 7 directory transition to repo-b
            currentDirectory = "file:///repo-b"
            val repoBSuggestions = provider.suggestions(request("git ")).last().map { it.replacementText }
            assertEquals("checkout feature-b", repoBSuggestions.first())
        }

    @Test
    fun `provider profile context ranks shared learning`() =
        runBlocking {
            val registry = registry(listOf(TerminalCommandSpec("build")))
            val bashProvider = registry.openProvider(profileId = "bash")
            val zshProvider = registry.openProvider(profileId = "zsh")

            registry.recordSuccess("build --release", profileId = "bash", workingDirectoryUri = null)
            registry.recordSuccess("build --debug", profileId = "zsh", workingDirectoryUri = null)

            val bashSuggestions = bashProvider.suggestions(request("build")).last().map { it.replacementText }
            val zshSuggestions = zshProvider.suggestions(request("build")).last().map { it.replacementText }

            assertEquals("build --release", bashSuggestions.first())
            assertEquals("build --debug", zshSuggestions.first())
            assertEquals(setOf("build --release", "build --debug"), bashSuggestions.toSet())
            assertEquals(setOf("build --release", "build --debug"), zshSuggestions.toSet())
        }

    @Test
    fun `closed registry rejects new providers`() =
        runBlocking {
            val registry = registry(emptyList())
            val completionJob = requireNotNull(registry.completionScope.coroutineContext[Job])

            registry.closeAndFlush()
            registry.closeAndFlush()

            assertTrue(completionJob.isCancelled)
            assertFailsWith<IllegalStateException> { registry.createResources() }
        }

    private fun registry(
        specs: List<TerminalCommandSpec> = specs(),
        learningStore: TerminalCompletionLearningStore? = null,
    ): StandaloneCompletionRegistry =
        StandaloneCompletionRegistry
            .create(
                persistencePath = persistenceDirectory.resolve("learning-${registries.size}.jsonl"),
                persistenceEnabled = false,
                specs = specs,
                learningStore = learningStore ?: TerminalCompletionLearningStore(),
            ).also(registries::add)

    private fun StandaloneCompletionRegistry.openProvider(
        profileId: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
        workingDirectoryUriProvider: () -> String? = { null },
    ): SwingShellSuggestionProvider =
        createResources(
            profileId = profileId,
            shellCapabilities = shellCapabilities,
            workingDirectoryUriProvider = workingDirectoryUriProvider,
        ).provider

    private fun StandaloneCompletionRegistry.recordSuccess(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
    ) {
        recordFinishedCommand(
            commandLine = commandLine,
            successful = true,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            usedAtEpochMillis = ++eventTime,
        )
    }

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
