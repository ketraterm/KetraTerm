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
package io.github.ketraterm.intellij.services

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalDirectoryScanner
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class IntellijCompletionRegistryTest {
    @Test
    fun `live shell authority filters catalog commands through the IntelliJ provider`() =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            try {
                val native =
                    context().copy(
                        shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                        shellProvider =
                            TerminalCompletionShellProvider { request, _, _ ->
                                if (request.commandLine == "dock") {
                                    listOf(
                                        TerminalCompletionCandidate(
                                            "docker",
                                            0,
                                            4,
                                            "powershell",
                                            TerminalCompletionCandidateKind.COMMAND,
                                        ),
                                    )
                                } else {
                                    emptyList()
                                }
                            },
                    )
                val provider = registry.createProvider(native)
                assertEquals(listOf("docker"), provider.suggestions(request("dock")).last().map { it.replacementText })
                assertTrue(provider.suggestions(request("car")).last().isEmpty())
            } finally {
                registry.closeAndFlush()
            }
        }

    @Test
    fun `Git directory changes select the same environment for every host source`() =
        runBlocking {
            val originalDirectory = Path.of("completion-workspace").toAbsolutePath().normalize()
            val selectedDirectory = originalDirectory.resolve("other repo")
            val selectedUri = selectedDirectory.toUri().toString()
            val referenceDirectories = mutableListOf<String?>()
            val commitDirectories = mutableListOf<String?>()
            val statusDirectories = mutableListOf<String?>()
            val projectDirectories = mutableListOf<String?>()
            val scannedDirectories = mutableListOf<Path>()
            val sources =
                listOf(
                    TerminalCompletionSources.git("intellij-git-reference") { request, _ ->
                        referenceDirectories += request.workingDirectoryUri
                        TerminalGitCompletionSnapshot(
                            listOf(TerminalCompletionDomainValue("feature/selected")),
                            emptyList(),
                            emptyList(),
                        )
                    },
                    intellijGitCommitCompletionSource { uri ->
                        commitDirectories += uri
                        listOf(TerminalCompletionDomainValue("abc123"))
                    },
                    intellijGitStatusPathCompletionSource { uri, _ ->
                        statusDirectories += uri
                        listOf(TerminalFuzzyPathEntry("Changed.kt", false))
                    },
                    intellijProjectFileCompletionSource { uri, _ ->
                        projectDirectories += uri
                        listOf(TerminalFuzzyPathEntry("Changed.kt", false))
                    },
                )
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            try {
                val provider =
                    registry.createProvider(
                        context(
                            additionalSources = sources.map { TerminalCompletionSourceEntry(it) },
                            workingDirectoryUriProvider = { originalDirectory.toUri().toString() },
                            scanner =
                                TerminalDirectoryScanner { directory, _ ->
                                    scannedDirectories.add(directory)
                                    listOf(TerminalFileEntry("Changed.kt", false))
                                },
                        ),
                    )
                for ((line, expected) in listOf(
                    "git -C 'other repo' switch fe" to "feature/selected",
                    "git -C 'other repo' cherry-pick ab" to "abc123",
                    "git -C 'other repo' add Cha" to "Changed.kt",
                )) {
                    assertTrue(provider.suggestions(request(line)).last().any { it.replacementText == expected })
                }

                assertEquals(listOf(selectedUri), referenceDirectories)
                assertEquals(listOf(selectedUri), commitDirectories)
                assertEquals(listOf(selectedUri), statusDirectories)
                assertEquals(listOf(selectedUri), projectDirectories)
                assertEquals(listOf(selectedDirectory), scannedDirectories)
            } finally {
                registry.closeAndFlush()
            }
        }

    @Test
    fun `explicit remote environments never invoke local host sources`() =
        runBlocking {
            var loads = 0
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            try {
                val provider =
                    registry.createProvider(
                        context(
                            additionalSources =
                                listOf(
                                    TerminalCompletionSourceEntry(
                                        { _, _, _ ->
                                            loads++
                                            emptyList()
                                        },
                                    ),
                                ),
                            workingDirectoryUriProvider = { "file://remote.example/repo" },
                            scanner =
                                TerminalDirectoryScanner { _, _ ->
                                    loads++
                                    emptyList()
                                },
                        ),
                    )

                assertTrue(provider.suggestions(request("git add Cha")).last().isEmpty())
                assertEquals(0, loads)
            } finally {
                registry.closeAndFlush()
            }
        }

    @Test
    fun `reset removes shared completion learning`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("git status", true, "bash", "file:///repo", 1L)
            val registry =
                IntellijCompletionRegistry(
                    learningStore = learningStore,
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )

            registry.resetLearning()

            assertTrue(learningStore.snapshot().rankingStats.isEmpty())
            assertTrue(learningStore.snapshot().replayCommands.isEmpty())
            registry.closeAndFlush()
        }

    @Test
    fun `directory completion suspends until the scanner returns real values`() =
        runBlocking {
            var scans = 0
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val resources =
                registry.createProvider(
                    context(
                        scanner =
                            TerminalDirectoryScanner { _, _ ->
                                scans++
                                listOf(TerminalFileEntry("src", true))
                            },
                    ),
                )

            val suggestions = resources.suggestions(request("cd s")).last()

            assertEquals(1, scans)
            assertEquals("src/", suggestions.first { it.source == "path" }.replacementText)
            registry.closeAndFlush()
        }

    @Test
    fun `dynamic provider loads values in the completion coroutine`() =
        runBlocking {
            var loads = 0
            val source =
                TerminalCompletionSources.valueDomain(
                    sourceId = "intellij-git-reference",
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    valuesProvider = { _, _ ->
                        loads++
                        listOf(TerminalCompletionDomainValue("main"))
                    },
                )
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val resources =
                registry.createProvider(context(additionalSources = listOf(TerminalCompletionSourceEntry(source, 20))))

            val suggestions = resources.suggestions(request("git switch m")).last()
            assertEquals("Git", suggestions.first { it.source == "intellij-git-reference" }.sourceDisplayText)

            assertEquals(1, loads)
            registry.closeAndFlush()
        }

    @Test
    fun `successful commands feed shared learning`() =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val first = registry.createProvider(context())
            val second = registry.createProvider(context())
            registry.recordFinishedCommand(
                "bash",
                TerminalShellIntegrationCommandMetadata(
                    recordId = 1,
                    commandText = "git switch main",
                    lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                    workingDirectoryUri = "file:///repo",
                    exitCode = 0,
                    startedAtEpochMillis = 1L,
                    finishedAtEpochMillis = 2L,
                ),
            )

            assertTrue(
                first
                    .suggestions(request("git s"))
                    .last()
                    .any { it.source == "learned" },
            )
            assertTrue(
                second
                    .suggestions(request("git s"))
                    .last()
                    .any { it.source == "learned" },
            )
            registry.closeAndFlush()
        }

    @Test
    fun `suggestion feedback keeps the working directory captured by its request`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            val registry =
                IntellijCompletionRegistry(
                    specs =
                        listOf(
                            TerminalCommandSpec(
                                name = "git",
                                subcommands = listOf(TerminalCommandSpec("status")),
                            ),
                        ),
                    learningStore = learningStore,
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            var workingDirectoryUri = "file:///repo-a"
            val resources =
                registry.createProvider(
                    context(workingDirectoryUriProvider = { workingDirectoryUri }),
                )
            val request = request("git s")
            val opened = AtomicReference<SwingShellSuggestionSource>()
            SwingUtilities.invokeAndWait { opened.set(resources.open(request)) }
            val source = opened.get()
            val suggestion =
                source
                    .suggestions
                    .last()
                    .first { it.replacementText == "status" }

            workingDirectoryUri = "file:///repo-b"
            source.feedbackHandler.onSuggestionFeedback(
                SwingShellSuggestionFeedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    suggestion = suggestion,
                    index = 0,
                    request = request,
                ),
            )

            val snapshot = learningStore.snapshot()
            assertTrue(snapshot.replayCommands.isEmpty())
            val ranking = snapshot.rankingStats.single()
            assertEquals("bash", ranking.profileId)
            assertEquals("file:///repo-a/", ranking.workingDirectoryUri)
            assertEquals(1, ranking.acceptedCount)
            registry.closeAndFlush()
        }

    @Test
    fun `closed registry rejects new sessions`(): Unit =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )

            registry.closeAndFlush()
            registry.closeAndFlush()

            assertThrows(IllegalStateException::class.java) { registry.createProvider(context()) }
        }

    @Test
    fun `close and flush persists the final queued command`() =
        runBlocking {
            val path =
                Files
                    .createTempDirectory("intellij-completion-registry")
                    .resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val learningStore = TerminalCompletionLearningStore()
            val registry =
                IntellijCompletionRegistry(
                    learningStore = learningStore,
                    persistencePath = path,
                    persistenceEnabled = true,
                    coroutineScope = this,
                )

            registry.recordFinishedCommand(
                profileId = "bash",
                metadata =
                    TerminalShellIntegrationCommandMetadata(
                        recordId = 1,
                        commandText = "git status",
                        lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                        workingDirectoryUri = "file:///repo",
                        exitCode = 0,
                        startedAtEpochMillis = 1L,
                        finishedAtEpochMillis = 2L,
                    ),
            )

            registry.closeAndFlush()

            assertEquals(listOf("git status"), learningStore.snapshot().replayCommands.map { it.commandLine })
            assertEquals(listOf("git status"), persistedSnapshot(path).replayCommands.map { it.commandLine })
        }

    @Test
    fun `reset clears saved and session learning while persistence is disabled`() =
        runBlocking {
            val path = memoryOnlyPath()
            val seedLearning = TerminalCompletionLearningStore()
            val seed =
                IntellijCompletionRegistry(
                    learningStore = seedLearning,
                    persistencePath = path,
                    persistenceEnabled = true,
                    coroutineScope = this,
                )
            seed.recordFinishedCommand(
                profileId = "bash",
                metadata =
                    TerminalShellIntegrationCommandMetadata(
                        recordId = 1,
                        commandText = "git status",
                        lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                        workingDirectoryUri = null,
                        exitCode = 0,
                        startedAtEpochMillis = 1L,
                        finishedAtEpochMillis = 2L,
                    ),
            )
            seed.closeAndFlush()
            assertFalse(persistedSnapshot(path).replayCommands.isEmpty())

            val learning =
                TerminalCompletionLearningStore().apply {
                    recordCommandResult("npm test", true, null, null, 2L)
                }
            val registry =
                IntellijCompletionRegistry(
                    learningStore = learning,
                    persistencePath = path,
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            try {
                registry.resetLearning().await()
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(path))
                registry.setPersistenceEnabled(true)
            } finally {
                registry.closeAndFlush()
            }
            assertThrows(IllegalStateException::class.java) { registry.resetLearning() }
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(path))
        }

    private suspend fun persistedSnapshot(path: Path) =
        coroutineScope {
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    learningStore = learning,
                    coroutineScope = this,
                    persistencePath = path,
                    persistenceEnabled = true,
                    onPersistenceLoadFailure = {},
                )
            coordinator.closeAndFlush()
            learning.snapshot()
        }

    private fun memoryOnlyPath(): Path =
        Files
            .createTempDirectory("intellij-completion-memory")
            .resolve(TerminalCompletionLearningCoordinator.currentFileName())

    private fun context(
        additionalSources: List<TerminalCompletionSourceEntry> = emptyList(),
        scanner: TerminalDirectoryScanner = TerminalDirectoryScanner { _: Path, _: String -> emptyList() },
        workingDirectoryUriProvider: () -> String? = { "file:///repo" },
    ) = IntellijCompletionContext(
        profileId = "bash",
        workingDirectoryUriProvider = workingDirectoryUriProvider,
        shellCapabilities = TerminalShellCapabilities.POSIX,
        additionalSources = additionalSources,
        directoryScanner = scanner,
    )

    private fun request(command: String) = SwingShellSuggestionRequest(command, command.length)
}
