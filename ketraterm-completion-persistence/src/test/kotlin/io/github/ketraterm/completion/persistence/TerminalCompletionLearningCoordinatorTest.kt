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
import kotlinx.coroutines.test.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCompletionLearningCoordinatorTest {
    @Test
    fun `large mutation burst is synchronous and checkpoints one latest snapshot`() =
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
            assertTrue(files.writes.isEmpty())

            runCurrent()
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS - 1L).milliseconds)
            runCurrent()
            assertTrue(files.writes.isEmpty())

            advanceTimeBy(1L.milliseconds)
            runCurrent()
            assertEquals(listOf(learning.snapshot()), files.writes)

            coordinator.closeAndFlush()
            assertEquals(1, files.writes.size)
        }

    @Test
    fun `later mutations do not postpone the fixed checkpoint`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("git status", true, null, null, 1L)
            runCurrent()
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS * 3L / 5L).milliseconds)
            coordinator.recordCommandResult("git status", true, null, null, 2L)
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS * 2L / 5L).milliseconds)
            runCurrent()

            assertEquals(1, files.writes.size)
            assertEquals(
                2,
                files.writes
                    .single()
                    .commandStats
                    .single()
                    .useCount,
            )
            coordinator.closeAndFlush()
        }

    @Test
    fun `a clean store schedules another checkpoint after its next mutation`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("git status", true, null, null, 1L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            coordinator.recordCommandResult("npm test", true, null, null, 2L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()

            assertEquals(2, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.last())
            coordinator.closeAndFlush()
        }

    @Test
    fun `event recorded during hydration is immediately visible and persisted after merge`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))
                coordinator.recordCommandResult("npm test", true, null, null, 2L)
                assertEquals(listOf("npm test"), learning.snapshot().commandStats.map { it.commandLine })

                releaseLoad.countDown()
                coordinator.closeAndFlush()

                assertEquals(
                    setOf("git status", "npm test"),
                    learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine },
                )
                assertEquals(listOf(learning.snapshot()), files.writes)
            } finally {
                releaseLoad.countDown()
            }
        }

    @Test
    fun `disable during hydration lets the started load merge but prevents writes`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))
                coordinator.recordCommandResult("npm test", true, null, null, 2L)
                coordinator.setPersistenceEnabled(false)
                releaseLoad.countDown()
                coordinator.closeAndFlush()

                assertEquals(
                    setOf("git status", "npm test"),
                    learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine },
                )
                assertTrue(files.writes.isEmpty())
            } finally {
                releaseLoad.countDown()
            }
        }

    @Test
    fun `disabled persistence cancels a checkpoint but keeps memory mutations`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            repeat(BURST_SIZE) { index ->
                coordinator.recordCommandResult("npm test", true, null, null, index + 1L)
            }
            runCurrent()
            coordinator.setPersistenceEnabled(false)
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(
                BURST_SIZE,
                learning
                    .snapshot()
                    .commandStats
                    .single()
                    .useCount,
            )
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `first enable hydrates once and reenable never reloads`() =
        runTest {
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, enabled = false)

            runCurrent()
            assertEquals(0, files.loadCount.get())
            coordinator.recordCommandResult("npm test", true, null, null, 2L)
            coordinator.setPersistenceEnabled(true)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()

            coordinator.setPersistenceEnabled(false)
            files.loadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("external rewrite", 3L))
            coordinator.recordCommandResult("npm test", true, null, null, 4L)
            coordinator.setPersistenceEnabled(true)
            coordinator.closeAndFlush()

            assertEquals(1, files.loadCount.get())
            assertEquals(
                mapOf("git status" to 1, "npm test" to 2),
                learning.snapshot().commandStats.associate { it.commandLine to it.useCount },
            )
            assertEquals(2, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.last())
        }

    @Test
    fun `clean hydration and shutdown do not rewrite the file`() =
        runTest {
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `memory changes advance the dirty revision before file store sanitization`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("npm token list", true, null, null, 42L)
            coordinator.closeAndFlush()

            assertEquals(1, files.writes.size)
            assertEquals(
                "npm token list",
                files.writes
                    .single()
                    .commandStats
                    .single()
                    .commandLine,
            )
        }

    @Test
    fun `persistence policy does not suppress in-memory learning`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, enabled = false)

            coordinator.recordCommandResult("npm token list", true, null, null, 42L)
            coordinator.recordSuggestionFeedback(
                commandLine = "npm token create",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 2L,
            )
            coordinator.recordCommandResult("git status", true, null, null, -1L)
            coordinator.closeAndFlush()

            val learned = learning.snapshot().commandStats.associateBy { it.commandLine }
            assertEquals(setOf("npm token list", "npm token create"), learned.keys)
            assertEquals(1, learned.getValue("npm token create").acceptedCount)
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `leading-space command remains in memory but is removed at the file boundary`() =
        runTest {
            val path =
                createTempDirectory("completion-leading-space")
                    .resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    learningStore = learning,
                    fileStore = CompletionLearningFileStore(path),
                    coroutineScope = this,
                    persistenceEnabled = true,
                    checkpointIntervalMillis = CHECKPOINT_INTERVAL_MILLIS,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()

            coordinator.recordCommandResult(" historyless-command", true, null, null, 42L)
            coordinator.closeAndFlush()

            assertEquals(
                listOf(" historyless-command"),
                learning.snapshot().commandStats.map { it.commandLine },
            )
            val persisted =
                requireNotNull(
                    CompletionLearningSnapshotCodec.decode(
                        Files.readAllLines(path),
                    ),
                )
            assertTrue(persisted.commandStats.isEmpty())
        }

    @Test
    fun `exact suggestion feedback uses the same checkpoint path`() =
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
            coordinator.closeAndFlush()

            assertEquals(
                1,
                learning
                    .snapshot()
                    .commandStats
                    .single()
                    .acceptedCount,
            )
            assertEquals(listOf(learning.snapshot()), files.writes)
        }

    @Test
    fun `evicted no-op mutation does not request a checkpoint`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore(capacity = 1)
            learning.recordCommandResult("retained", true, null, null, 100L)
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("obsolete", true, null, null, 1L)
            coordinator.closeAndFlush()

            assertEquals(listOf("retained"), learning.snapshot().commandStats.map { it.commandLine })
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `rejected and failed hydration block overwrite for the lifecycle`() =
        runTest {
            for (
            outcome in
            listOf(
                CompletionLearningFileLoadOutcome.Rejected,
                CompletionLearningFileLoadOutcome.Failed,
            )
            ) {
                val files = RecordingSnapshotFiles(initialLoadOutcome = outcome)
                val learning = TerminalCompletionLearningStore()
                val coordinator = coordinator(learning, files)

                coordinator.recordCommandResult("git status", true, null, null, 42L)
                coordinator.closeAndFlush()

                assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
                assertTrue(files.writes.isEmpty())
            }
        }

    @Test
    fun `checkpoint failure retries only after a newer mutation`() =
        runTest {
            val expectedFailure = IOException("test write failure")
            val files = RecordingSnapshotFiles(writeFailure = expectedFailure)
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            assertEquals(1, files.writeAttempts.get())

            files.writeFailure = null
            coordinator.recordCommandResult("npm test", true, null, null, 43L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(2, files.writeAttempts.get())
            assertEquals(listOf(learning.snapshot()), files.writes)
        }

    @Test
    fun `shutdown retries a failed checkpoint once and surfaces the final failure`() =
        runTest {
            val expectedFailure = IOException("test write failure")
            val files = RecordingSnapshotFiles(writeFailure = expectedFailure)
            val coordinator = coordinator(TerminalCompletionLearningStore(), files)

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()

            val thrown = assertFailsWith<IOException> { coordinator.closeAndFlush() }
            assertEquals(expectedFailure.message, thrown.message)
            assertEquals(2, files.writeAttempts.get())
            assertFailsWith<IOException> { coordinator.closeAndFlush() }
            assertEquals(2, files.writeAttempts.get())
        }

    @Test
    fun `mutation accepted during an in-flight write is included in the final write`() =
        runTest {
            val loadFinished = CompletableDeferred<Unit>()
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    afterLoad = { loadFinished.complete(Unit) },
                    beforePersist = { attempt ->
                        if (attempt == 1) {
                            writeStarted.complete(Unit)
                            check(releaseWrite.await(5L, TimeUnit.SECONDS)) { "test write was not released" }
                        }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                loadFinished.await()
                runCurrent()
                coordinator.recordCommandResult("git status", true, null, null, 1L)
                runCurrent()
                advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
                runCurrent()
                writeStarted.await()

                coordinator.recordCommandResult("git status", true, null, null, 2L)
                releaseWrite.countDown()
                coordinator.closeAndFlush()

                assertEquals(2, files.writes.size)
                assertEquals(
                    1,
                    files.writes
                        .first()
                        .commandStats
                        .single()
                        .useCount,
                )
                assertEquals(
                    2,
                    files.writes
                        .last()
                        .commandStats
                        .single()
                        .useCount,
                )
            } finally {
                releaseWrite.countDown()
            }
        }

    @Test
    fun `disable during an in-flight write allows only that write to finish`() =
        runTest {
            val loadFinished = CompletableDeferred<Unit>()
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    afterLoad = { loadFinished.complete(Unit) },
                    beforePersist = { attempt ->
                        if (attempt == 1) {
                            writeStarted.complete(Unit)
                            check(releaseWrite.await(5L, TimeUnit.SECONDS)) { "test write was not released" }
                        }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                loadFinished.await()
                runCurrent()
                coordinator.recordCommandResult("git status", true, null, null, 1L)
                runCurrent()
                advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
                runCurrent()
                writeStarted.await()

                coordinator.recordCommandResult("git status", true, null, null, 2L)
                coordinator.setPersistenceEnabled(false)
                releaseWrite.countDown()
                coordinator.closeAndFlush()

                assertEquals(1, files.writes.size)
                assertEquals(
                    1,
                    files.writes
                        .single()
                        .commandStats
                        .single()
                        .useCount,
                )
                assertEquals(
                    2,
                    learning
                        .snapshot()
                        .commandStats
                        .single()
                        .useCount,
                )
            } finally {
                releaseWrite.countDown()
            }
        }

    @Test
    fun `close flushes once and remains idempotent`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("gradle test", true, null, null, 42L)
            coordinator.closeAndFlush()
            coordinator.closeAndFlush()

            assertEquals(listOf(learning.snapshot()), files.writes)
        }

    @Test
    fun `concurrent close persists every mutation that returned normally`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)
            val start = CompletableDeferred<Unit>()
            val recordings =
                List(CONCURRENT_RECORDINGS) { index ->
                    async<Throwable?>(Dispatchers.Default) {
                        start.await()
                        try {
                            coordinator.recordCommandResult("git status", true, null, null, index + 1L)
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    }
                }
            val closing =
                async(Dispatchers.Default) {
                    start.await()
                    coordinator.closeAndFlush()
                }

            start.complete(Unit)
            val outcomes = recordings.awaitAll()
            outcomes.filterNotNull().forEach { failure ->
                assertTrue(failure is IllegalStateException, "unexpected recording failure: $failure")
                assertEquals("completion-learning owner is closed", failure.message)
            }
            val accepted = outcomes.count { it == null }
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
                assertTrue(files.writes.isEmpty())
            } else {
                assertEquals(
                    accepted,
                    files.writes
                        .single()
                        .commandStats
                        .single()
                        .useCount,
                )
            }
        }

    @Test
    fun `checkpoint interval must be positive`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                TerminalCompletionLearningCoordinator(
                    learningStore = TerminalCompletionLearningStore(),
                    fileStore = RecordingSnapshotFiles(),
                    coroutineScope = this,
                    persistenceEnabled = true,
                    checkpointIntervalMillis = 0L,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            }
        }

    private fun TestScope.coordinator(
        learning: TerminalCompletionLearningStore,
        files: RecordingSnapshotFiles,
        enabled: Boolean = true,
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler, name = "learning-io"),
    ): TerminalCompletionLearningCoordinator =
        TerminalCompletionLearningCoordinator(
            learningStore = learning,
            fileStore = files,
            coroutineScope = this,
            persistenceEnabled = enabled,
            checkpointIntervalMillis = CHECKPOINT_INTERVAL_MILLIS,
            ioDispatcher = ioDispatcher,
        )

    private class RecordingSnapshotFiles(
        initialLoadOutcome: CompletionLearningFileLoadOutcome = CompletionLearningFileLoadOutcome.Missing,
        @Volatile var writeFailure: Throwable? = null,
        private val beforeLoad: () -> Unit = {},
        private val afterLoad: () -> Unit = {},
        private val beforePersist: (Int) -> Unit = {},
    ) : CompletionLearningSnapshotFileStore {
        @Volatile
        var loadOutcome: CompletionLearningFileLoadOutcome = initialLoadOutcome

        val loadCount = AtomicInteger()
        val writeAttempts = AtomicInteger()
        val writes = CopyOnWriteArrayList<TerminalCommandCompletionStatsSnapshot>()

        override fun loadSnapshot(): CompletionLearningFileLoadOutcome {
            beforeLoad()
            loadCount.incrementAndGet()
            val outcome = loadOutcome
            afterLoad()
            return outcome
        }

        override fun persist(snapshot: TerminalCommandCompletionStatsSnapshot) {
            val attempt = writeAttempts.incrementAndGet()
            beforePersist(attempt)
            writeFailure?.let { throw it }
            writes += snapshot
        }
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
        private const val CHECKPOINT_INTERVAL_MILLIS = 1_000L
    }
}
