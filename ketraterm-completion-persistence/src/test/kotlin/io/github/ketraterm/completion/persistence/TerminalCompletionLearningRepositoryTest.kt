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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TerminalCompletionLearningRepositoryTest {
    @Test
    fun `mutation is persisted through one suspending repository`() =
        runTest {
            val path = createTempDirectory("completion-learning").resolve(TerminalCompletionLearningRepository.currentFileName())
            val learning = TerminalCompletionLearningStore()
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    initialPersistencePath = path,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            repository.initialize()
            repository.mutate {
                recordCommandResult("git status", true, null, null, 42L)
            }

            assertEquals(learning.snapshot(), CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot())
        }

    @Test
    fun `first mutation loads persisted learning before replacing the snapshot`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, path)

            repository.mutate {
                recordCommandResult("git log", true, null, null, 42L)
            }

            assertEquals(setOf("git status", "git log"), learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine })
            assertEquals(learning.snapshot(), CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot())
        }

    @Test
    fun `initialize does not reload a path that was already initialized`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, path)

            repository.initialize()
            seedCommand(path, "git log", 42L)
            repository.initialize()

            assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
        }

    @Test
    fun `path reconfiguration initializes only the replacement path`() =
        runTest {
            val initialPath = completionLearningPath()
            val replacementPath = completionLearningPath()
            seedCommand(initialPath, "git status", 41L)
            seedCommand(replacementPath, "git log", 42L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, initialPath)

            repository.setPersistencePath(replacementPath)

            assertEquals(listOf("git log"), learning.snapshot().commandStats.map { it.commandLine })
            assertEquals(learning.snapshot(), CompletionLearningFileStore(replacementPath).loadSnapshot().loadedSnapshot())
        }

    @Test
    fun `disabling persistence before initialization does not read the configured path`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, path)

            repository.setPersistenceEnabled(false)

            assertEquals(emptyList(), learning.snapshot().commandStats)
        }

    @Test
    fun `initialization merges overlapping exact shape and provider aggregates`() =
        runTest {
            val path = completionLearningPath()
            val shape = TerminalCommandLineShape(executable = "git", subcommands = listOf("status"))
            val persisted =
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = listOf(commandStats("Git Status", "file:///repo", 3, 2, 200L)),
                    shapeStats = listOf(shapeStats(shape, "file:///repo", 4, 3, 200L)),
                    feedbackStats = listOf(feedbackStats("file:///repo", 5, 2, 200L)),
                )
            CompletionLearningFileStore(path).persist(persisted)
            val learning = TerminalCompletionLearningStore()
            learning.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = listOf(commandStats("git status", "file:///repo/", 7, 1, 100L)),
                    shapeStats = listOf(shapeStats(shape, "file:///repo/", 6, 1, 100L)),
                    feedbackStats = listOf(feedbackStats("file:///repo/", 8, 4, 100L)),
                ),
            )
            val repository = repository(learning, path)

            repository.initialize()

            val snapshot = learning.snapshot()
            with(snapshot.commandStats.single()) {
                assertEquals(10, useCount)
                assertEquals(3, successCount)
                assertEquals(200L, lastUsedEpochMillis)
            }
            with(snapshot.shapeStats.single()) {
                assertEquals(10, useCount)
                assertEquals(4, acceptedCount)
                assertEquals(200L, lastUsedEpochMillis)
            }
            with(snapshot.feedbackStats.single()) {
                assertEquals(13, acceptedCount)
                assertEquals(6, dismissedCount)
                assertEquals(200L, lastUsedEpochMillis)
            }
            assertEquals(snapshot, CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot())
        }

    @Test
    fun `re-enabling an imported path flushes offline learning without importing it twice`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, path)

            repository.initialize()
            repository.setPersistenceEnabled(false)
            repository.mutate {
                recordCommandResult("git status", true, null, null, 42L)
            }
            repository.setPersistenceEnabled(true)

            assertEquals(
                2,
                learning
                    .snapshot()
                    .commandStats
                    .single()
                    .useCount,
            )
            assertEquals(learning.snapshot(), CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot())
        }

    @Test
    fun `rejected files remain byte-identical after later learning mutations`() =
        runTest {
            val header = "KetraTerm_COMMAND_COMPLETION_STATS\t1\n".encodeToByteArray()
            val excessiveLines =
                buildString {
                    appendLine("KetraTerm_COMMAND_COMPLETION_STATS\t1")
                    repeat(MAX_FILE_LINES) { appendLine("ignored") }
                }.encodeToByteArray()
            val rejectedInputs =
                listOf(
                    "unknown header" to "KetraTerm_COMMAND_COMPLETION_STATS\t999\n".encodeToByteArray(),
                    "oversized file" to ByteArray(MAX_FILE_BYTES + 1) { 'x'.code.toByte() },
                    "oversized line" to header + ByteArray(MAX_LINE_BYTES + 1) { 'x'.code.toByte() },
                    "excessive line count" to excessiveLines,
                )

            for ((description, originalBytes) in rejectedInputs) {
                val path = completionLearningPath()
                Files.write(path, originalBytes)
                val repository = repository(TerminalCompletionLearningStore(), path)

                repository.initialize()
                repository.mutate {
                    recordCommandResult("git status", true, null, null, 42L)
                }

                assertContentEquals(originalBytes, Files.readAllBytes(path), description)
            }
        }

    @Test
    fun `read failure blocks later writes while the path remains active`() =
        runTest {
            val path = completionLearningPath()
            val originalBytes = "KetraTerm_COMMAND_COMPLETION_STATS\t1\n".encodeToByteArray()
            Files.write(path, originalBytes)
            val expectedFailure = IOException("test read failure")
            val failures = mutableListOf<Throwable>()
            val learning = TerminalCompletionLearningStore()
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    initialPersistencePath = path,
                    persistenceEnabled = true,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    onPersistenceFailure = failures::add,
                    fileStoreFactory = { storePath, onFailure ->
                        CompletionLearningFileStore(storePath, onFailure, openInput = { throw expectedFailure })
                    },
                )

            repository.initialize()
            repository.mutate {
                recordCommandResult("git status", true, null, null, 42L)
            }

            assertSame(expectedFailure, failures.single())
            assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
            assertContentEquals(originalBytes, Files.readAllBytes(path))
        }

    private fun completionLearningPath(): Path =
        createTempDirectory("completion-learning").resolve(TerminalCompletionLearningRepository.currentFileName())

    private fun seedCommand(
        path: Path,
        commandLine: String,
        usedAtEpochMillis: Long,
    ) {
        val learning = TerminalCompletionLearningStore()
        learning.recordCommandResult(commandLine, true, null, null, usedAtEpochMillis)
        CompletionLearningFileStore(path).persist(learning.snapshot())
    }

    private fun commandStats(
        commandLine: String,
        directory: String,
        useCount: Int,
        successCount: Int,
        timestamp: Long,
    ) = TerminalCommandCompletionStats(
        commandLine = commandLine,
        profileId = "bash",
        workingDirectoryUri = directory,
        useCount = useCount,
        successCount = successCount,
        lastUsedEpochMillis = timestamp,
    )

    private fun shapeStats(
        shape: TerminalCommandLineShape,
        directory: String,
        useCount: Int,
        acceptedCount: Int,
        timestamp: Long,
    ) = TerminalCommandShapeStats(
        shape = shape,
        profileId = "bash",
        workingDirectoryUri = directory,
        useCount = useCount,
        acceptedCount = acceptedCount,
        lastUsedEpochMillis = timestamp,
    )

    private fun feedbackStats(
        directory: String,
        acceptedCount: Int,
        dismissedCount: Int,
        timestamp: Long,
    ) = TerminalCompletionFeedbackStats(
        source = "spec",
        candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        profileId = "bash",
        workingDirectoryUri = directory,
        acceptedCount = acceptedCount,
        dismissedCount = dismissedCount,
        lastUsedEpochMillis = timestamp,
    )

    private fun CompletionLearningFileLoadOutcome.loadedSnapshot(): TerminalCommandCompletionStatsSnapshot =
        assertIs<CompletionLearningFileLoadOutcome.Loaded>(this).snapshot

    private fun TestScope.repository(
        learning: TerminalCompletionLearningStore,
        path: Path,
    ): TerminalCompletionLearningRepository =
        TerminalCompletionLearningRepository(
            learningStore = learning,
            initialPersistencePath = path,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private companion object {
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_LINES = 1 + 3 * 2_048
        private const val MAX_LINE_BYTES = 16 * 1024
    }
}
