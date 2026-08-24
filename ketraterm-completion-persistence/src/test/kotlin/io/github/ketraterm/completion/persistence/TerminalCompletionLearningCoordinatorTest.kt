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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCompletionLearningCoordinatorTest {
    @Test
    fun `large mutation burst updates memory synchronously and coalesces to one latest write`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            repeat(BURST_SIZE) { index ->
                coordinator.recordCommandResult("git status", true, "bash", "file:///repo", index + 1L)
            }

            assertEquals(
                BURST_SIZE,
                learning
                    .snapshot()
                    .commandStats
                    .single()
                    .useCount,
            )
            assertEquals(emptyList(), files.writes)

            runCurrent()
            assertEquals(emptyList(), files.writes)

            coordinator.flush()

            assertEquals(1, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.single().snapshot)
            coordinator.closeAndFlush()
        }

    @Test
    fun `event recorded during startup hydration is immediately visible and persisted after merge`() =
        runTest {
            val path = Path("existing.tsv")
            val files = RecordingSnapshotFiles()
            files.snapshots[path.toAbsolutePath().normalize()] = snapshot("git status", 1L)
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, path = path)

            coordinator.recordCommandResult("npm test", true, null, null, 2L)

            assertEquals(listOf("npm test"), learning.snapshot().commandStats.map { it.commandLine })

            coordinator.flush()

            assertEquals(
                setOf("git status", "npm test"),
                learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine },
            )
            assertEquals(1, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.single().snapshot)
            coordinator.closeAndFlush()
        }

    @Test
    fun `disable during startup hydration prevents the pending live change from being written`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    persistencePath = Path("fixed.tsv"),
                    persistenceEnabled = true,
                    ioDispatcher = Dispatchers.IO,
                    fileStoreFactory = files::store,
                )
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    repository = repository,
                    coroutineScope = this,
                    workerDispatcher = Dispatchers.Default,
                    writeDebounceMillis = 0L,
                )

            try {
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))
                coordinator.recordCommandResult("npm test", true, null, null, 2L)
                coordinator.setPersistenceEnabled(false)
                releaseLoad.countDown()
                coordinator.flush()

                assertEquals(listOf("npm test"), learning.snapshot().commandStats.map { it.commandLine })
                assertEquals(emptyList(), files.writes)
            } finally {
                releaseLoad.countDown()
                coordinator.closeAndFlush()
            }
        }

    @Test
    fun `flush surfaces the latest write failure and reports it once`() =
        runTest {
            val expectedFailure = IOException("test write failure")
            val failures = mutableListOf<Throwable>()
            val files = RecordingSnapshotFiles(writeFailure = expectedFailure)
            val coordinator = coordinator(TerminalCompletionLearningStore(), files, onFailure = failures::add)

            coordinator.recordCommandResult("git status", true, null, null, 42L)

            val thrown = assertFailsWith<IOException> { coordinator.flush() }
            assertEquals(expectedFailure.message, thrown.message)
            assertSame(expectedFailure, failures.single())

            coordinator.setPersistenceEnabled(false)
            coordinator.closeAndFlush()
        }

    @Test
    fun `disabling persistence discards a debounced backlog but keeps every memory mutation`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            repeat(BURST_SIZE) { index ->
                coordinator.recordCommandResult("npm test", true, null, null, index + 1L)
            }
            coordinator.setPersistenceEnabled(false)
            coordinator.flush()

            assertEquals(
                BURST_SIZE,
                learning
                    .snapshot()
                    .commandStats
                    .single()
                    .useCount,
            )
            assertEquals(emptyList(), files.writes)
            coordinator.closeAndFlush()
        }

    @Test
    fun `enabling the same fixed path merges disk once with offline session learning`() =
        runTest {
            val path = Path("fixed.tsv")
            val files = RecordingSnapshotFiles()
            files.snapshots[path.toAbsolutePath().normalize()] = snapshot("git status", 1L)
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, path = path, enabled = false)

            coordinator.recordCommandResult("npm test", true, null, null, 2L)
            coordinator.setPersistenceEnabled(true)
            coordinator.flush()
            coordinator.setPersistenceEnabled(false)
            coordinator.recordCommandResult("npm test", true, null, null, 3L)
            coordinator.setPersistenceEnabled(true)
            coordinator.flush()

            assertEquals(1, files.loadCount)
            assertEquals(
                mapOf("git status" to 1, "npm test" to 2),
                learning.snapshot().commandStats.associate { it.commandLine to it.useCount },
            )
            assertEquals(2, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.last().snapshot)
            coordinator.closeAndFlush()
        }

    @Test
    fun `disable remains authoritative when an earlier enable load completes late`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    persistencePath = Path("fixed.tsv"),
                    persistenceEnabled = false,
                    ioDispatcher = Dispatchers.IO,
                    fileStoreFactory = files::store,
                )
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    repository = repository,
                    coroutineScope = this,
                    workerDispatcher = Dispatchers.Default,
                    writeDebounceMillis = 0L,
                )

            try {
                coordinator.setPersistenceEnabled(true)
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))

                coordinator.setPersistenceEnabled(false)
                coordinator.recordCommandResult("npm test", true, null, null, 2L)
                releaseLoad.countDown()
                coordinator.flush()

                assertEquals(listOf("npm test"), learning.snapshot().commandStats.map { it.commandLine })
                assertEquals(emptyList(), files.writes)
            } finally {
                releaseLoad.countDown()
                coordinator.closeAndFlush()
            }
        }

    @Test
    fun `startup load and flush do not rewrite an unchanged snapshot`() =
        runTest {
            val path = Path("existing.tsv")
            val files = RecordingSnapshotFiles()
            files.snapshots[path.toAbsolutePath().normalize()] = snapshot("git status", 1L)
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, path = path)

            coordinator.flush()

            assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
            assertEquals(emptyList(), files.writes)
            coordinator.closeAndFlush()
        }

    @Test
    fun `shared privacy policy rejects sensitive commands before mutation`() =
        runTest {
            val coordinator =
                coordinator(
                    learning = TerminalCompletionLearningStore(),
                    files = RecordingSnapshotFiles(),
                    path = null,
                )

            coordinator.recordCommandResult("docker login --password secret", true, null, null, 42L)
            coordinator.flush()

            assertEquals(emptyList(), coordinator.learningStore.snapshot().commandStats)
            coordinator.closeAndFlush()
        }

    @Test
    fun `exact suggestion feedback mutates and persists through the coordinator`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                feedbackAtEpochMillis = 42L,
            )
            coordinator.flush()

            assertEquals(
                1,
                learning
                    .snapshot()
                    .commandStats
                    .single()
                    .acceptedCount,
            )
            assertEquals(1, files.writes.size)
            coordinator.closeAndFlush()
        }

    @Test
    fun `evicted no-op mutation does not request a disk write`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore(capacity = 1)
            learning.recordCommandResult("retained", true, null, null, 100L)
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("obsolete", true, null, null, 1L)
            coordinator.flush()

            assertEquals(listOf("retained"), learning.snapshot().commandStats.map { it.commandLine })
            assertEquals(emptyList(), files.writes)
            coordinator.closeAndFlush()
        }

    @Test
    fun `close flushes the final requested generation`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("gradle test", true, null, null, 42L)
            coordinator.closeAndFlush()
            coordinator.closeAndFlush()

            assertEquals(1, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.single().snapshot)
        }

    @Test
    fun `concurrent close either accepts and flushes a complete mutation or rejects it`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)
            val start = CompletableDeferred<Unit>()
            val recordings =
                List(CONCURRENT_RECORDINGS) { index ->
                    async(Dispatchers.Default) {
                        start.await()
                        runCatching {
                            coordinator.recordCommandResult("git status", true, null, null, index + 1L)
                        }.isSuccess
                    }
                }
            val closing =
                async(Dispatchers.Default) {
                    start.await()
                    coordinator.close()
                }

            start.complete(Unit)
            val accepted = recordings.awaitAll().count { it }
            closing.await()
            coordinator.closeAndFlush()

            val retained =
                learning
                    .snapshot()
                    .commandStats
                    .singleOrNull()
                    ?.useCount ?: 0
            assertEquals(accepted, retained)
            if (accepted == 0) {
                assertEquals(emptyList(), files.writes)
            } else {
                assertEquals(
                    accepted,
                    files.writes
                        .single()
                        .snapshot.commandStats
                        .single()
                        .useCount,
                )
            }
        }

    private fun TestScope.coordinator(
        learning: TerminalCompletionLearningStore,
        files: RecordingSnapshotFiles,
        path: Path? = Path("learning.tsv"),
        enabled: Boolean = true,
        onFailure: (Throwable) -> Unit = {},
    ): TerminalCompletionLearningCoordinator {
        val workerDispatcher = StandardTestDispatcher(testScheduler, name = "learning-worker")
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "learning-io")
        val repository =
            TerminalCompletionLearningRepository(
                learningStore = learning,
                persistencePath = path,
                persistenceEnabled = enabled,
                ioDispatcher = ioDispatcher,
                onPersistenceFailure = onFailure,
                fileStoreFactory = files::store,
            )
        return TerminalCompletionLearningCoordinator(
            repository = repository,
            coroutineScope = this,
            workerDispatcher = workerDispatcher,
            writeDebounceMillis = TEST_DEBOUNCE_MILLIS,
        )
    }

    private class RecordingSnapshotFiles(
        var writeFailure: Throwable? = null,
        private val beforeLoad: () -> Unit = {},
    ) {
        val snapshots = mutableMapOf<Path, TerminalCommandCompletionStatsSnapshot>()
        val writes = mutableListOf<RecordedSnapshotWrite>()
        var loadCount = 0

        fun store(
            path: Path,
            onFailure: (Throwable) -> Unit,
        ): CompletionLearningSnapshotFileStore =
            object : CompletionLearningSnapshotFileStore {
                override fun loadSnapshot(): CompletionLearningFileLoadOutcome {
                    beforeLoad()
                    loadCount++
                    val snapshot = snapshots[path.toAbsolutePath().normalize()] ?: return CompletionLearningFileLoadOutcome.Missing
                    return CompletionLearningFileLoadOutcome.Loaded(snapshot)
                }

                override fun persist(snapshot: TerminalCommandCompletionStatsSnapshot) {
                    val failure = writeFailure
                    if (failure != null) {
                        onFailure(failure)
                        throw failure
                    }
                    val identity = path.toAbsolutePath().normalize()
                    snapshots[identity] = snapshot
                    writes += RecordedSnapshotWrite(identity, snapshot)
                }
            }

        data class RecordedSnapshotWrite(
            val path: Path,
            val snapshot: TerminalCommandCompletionStatsSnapshot,
        )
    }

    private fun snapshot(
        commandLine: String,
        timestamp: Long,
    ): TerminalCommandCompletionStatsSnapshot =
        TerminalCompletionLearningStore()
            .apply { recordCommandResult(commandLine, true, null, null, timestamp) }
            .snapshot()

    private companion object {
        private const val BURST_SIZE = 5_000
        private const val CONCURRENT_RECORDINGS = 128
        private const val TEST_DEBOUNCE_MILLIS = 1_000L
    }
}
