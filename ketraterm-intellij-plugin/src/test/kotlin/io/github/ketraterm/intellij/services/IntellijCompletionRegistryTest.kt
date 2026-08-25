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
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class IntellijCompletionRegistryTest {
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
            val session =
                registry.openSession(
                    context(
                        scanner =
                            TerminalDirectoryScanner { _, _ ->
                                scans++
                                listOf(TerminalFileEntry("src", true))
                            },
                    ),
                )

            val suggestions = session.provider.suggestions(request("cd s")).last()

            assertEquals(1, scans)
            assertEquals("src/", suggestions.first { it.source == "path" }.replacementText)
            session.close()
            registry.closeAndFlush()
        }

    @Test
    fun `dynamic provider loads values in the completion coroutine`() =
        runBlocking {
            var loads = 0
            val source =
                TerminalCompletionSources.valueDomain(
                    sourceId = "test-branch",
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    valuesProvider = {
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
            val session = registry.openSession(context(additionalSources = listOf(TerminalCompletionSourceEntry(source, 20))))

            session.provider.suggestions(request("git switch m")).last()

            assertEquals(1, loads)
            session.close()
            registry.closeAndFlush()
        }

    @Test
    fun `successful commands feed session mru`() =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val first = registry.openSession(context(sessionId = "first"))
            val second = registry.openSession(context(sessionId = "second"))
            registry.recordFinishedCommand(
                "first",
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
                first.provider
                    .suggestions(request("git s"))
                    .last()
                    .any { it.source == "mru" },
            )
            first.close()
            second.close()
            registry.closeAndFlush()
        }

    @Test
    fun `closing a replaced session cannot remove its replacement`() =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    specs = emptyList(),
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val previous = registry.openSession(context(sessionId = "session"))
            val replacement = registry.openSession(context(sessionId = "session"))

            previous.close()
            registry.recordFinishedCommand(
                "session",
                "bash",
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

            assertEquals(
                listOf("git status"),
                replacement.provider
                    .suggestions(request("git"))
                    .last()
                    .map { it.replacementText },
            )
            replacement.close()
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

            assertThrows(IllegalStateException::class.java) { registry.openSession(context()) }
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
                sessionId = "session",
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

            assertEquals(listOf("git status"), learningStore.snapshot().commandStats.map { it.commandLine })
            assertEquals(listOf("git status"), persistedSnapshot(path).commandStats.map { it.commandLine })
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
                )
            coordinator.closeAndFlush()
            learning.snapshot()
        }

    private fun memoryOnlyPath(): Path =
        Files
            .createTempDirectory("intellij-completion-memory")
            .resolve(TerminalCompletionLearningCoordinator.currentFileName())

    private fun context(
        sessionId: String = "session",
        additionalSources: List<TerminalCompletionSourceEntry> = emptyList(),
        scanner: TerminalDirectoryScanner = TerminalDirectoryScanner { _: Path, _: String -> emptyList() },
    ) = IntellijCompletionSessionContext(
        sessionId = sessionId,
        profileId = "bash",
        workingDirectoryUriProvider = { "file:///repo" },
        shellCapabilities = TerminalShellCapabilities.POSIX,
        additionalSources = additionalSources,
        directoryScanner = scanner,
    )

    private fun request(command: String) = SwingShellSuggestionRequest(command, command.length, 0, 0)
}
