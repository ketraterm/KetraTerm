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
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneCompletionRegistryPersistenceTest {
    @Test
    fun `versioned learning from either platform state or config migrates to the stable filename`(
        @TempDir directory: Path,
    ) = runTest {
        for (legacyInState in listOf(false, true)) {
            val root = directory.resolve("case-$legacyInState")
            val destination = root.resolve("state").resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val previous = root.resolve("config").resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val legacy = (if (legacyInState) destination else previous).resolveSibling("command-completion-learning-v3.tsv")
            val seed = registry(TerminalCompletionLearningStore(), legacy, persistenceEnabled = true)
            seed.recordFinishedCommand("git status", true, null, null, 1L)
            seed.closeAndFlush()
            val learning = TerminalCompletionLearningStore()
            val current = registry(learning, destination, persistenceEnabled = true, previousPath = previous)
            try {
                runCurrent()
                assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
                assertFalse(Files.exists(legacy))
                assertTrue(Files.exists(destination))
                current.resetLearning().await()
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
            } finally {
                current.closeAndFlush()
            }
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(destination))
        }
    }

    @Test
    fun `previous learning relocates only on enable and merges once with live activity`(
        @TempDir directory: Path,
    ) = runTest {
        val previous = directory.resolve("config/learning.tsv")
        val destination = directory.resolve("state/learning.tsv")
        val seed = registry(TerminalCompletionLearningStore(), previous, persistenceEnabled = true)
        seed.recordFinishedCommand("git status", true, null, null, 1L)
        seed.closeAndFlush()

        val learning = TerminalCompletionLearningStore()
        val current = registry(learning, destination, persistenceEnabled = false, previousPath = previous)
        try {
            runCurrent()
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
            assertTrue(Files.exists(previous))
            assertFalse(Files.exists(destination))
            current.recordFinishedCommand("npm test", true, null, null, 2L)

            current.setPersistenceEnabled(true)
            runCurrent()
            assertFalse(Files.exists(previous))
            assertTrue(Files.exists(destination))
            assertEquals(
                setOf("git status", "npm test"),
                learning
                    .snapshot()
                    .replayCommands
                    .map { it.commandLine }
                    .toSet(),
            )
            val hydrated = learning.snapshot()
            current.setPersistenceEnabled(false)
            current.setPersistenceEnabled(true)
            runCurrent()
            assertEquals(hydrated, learning.snapshot())
        } finally {
            current.closeAndFlush()
        }
        assertEquals(learning.snapshot(), persistedSnapshot(destination))
    }

    @Test
    fun `reset supersedes queued relocation and also clears previous learning when persistence is disabled`(
        @TempDir directory: Path,
    ) = runTest {
        for (enabled in listOf(false, true)) {
            val previous = directory.resolve("previous-$enabled.tsv")
            val destination = directory.resolve("state-$enabled/learning.tsv")
            val seed = registry(TerminalCompletionLearningStore(), previous, persistenceEnabled = true)
            seed.recordFinishedCommand("git status", true, null, null, 1L)
            seed.closeAndFlush()
            val learning = TerminalCompletionLearningStore()
            val current = registry(learning, destination, persistenceEnabled = enabled, previousPath = previous)
            try {
                current.recordFinishedCommand("live command", true, null, null, 2L)
                current.resetLearning().await()
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
                assertFalse(Files.exists(previous))
                current.setPersistenceEnabled(true)
                runCurrent()
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
            } finally {
                current.closeAndFlush()
            }
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(destination))
        }
    }

    @Test
    fun `unsupported previous learning is preserved until explicit reset`(
        @TempDir directory: Path,
    ) = runTest {
        val previous =
            runInterruptible(Dispatchers.IO) { Files.writeString(directory.resolve("previous.tsv"), "unsupported previous data") }
        val destination = directory.resolve("state/learning.tsv")
        val failures = mutableListOf<Throwable>()
        val learning = TerminalCompletionLearningStore()
        val current =
            StandaloneCompletionRegistry.create(
                persistencePath = destination,
                previousPersistencePath = previous,
                persistenceEnabled = true,
                learningStore = learning,
                specs = emptyList(),
                workerDispatcher = StandardTestDispatcher(testScheduler),
                ioDispatcher = StandardTestDispatcher(testScheduler),
                onPersistenceLoadFailure = failures::add,
            )
        try {
            runCurrent()
            assertEquals(1, failures.size)
            current.recordFinishedCommand("live command", true, null, null, 2L)
            assertEquals("unsupported previous data", runInterruptible(Dispatchers.IO) { Files.readString(previous) })
            assertFalse(Files.exists(destination))
            current.resetLearning().await()
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
            assertFalse(Files.exists(previous))
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(destination))
        } finally {
            current.closeAndFlush()
        }
    }

    @Test
    fun `disable preserves disk state and replacement runtime loads only saved learning`(
        @TempDir directory: Path,
    ) = runTest {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val seed = registry(TerminalCompletionLearningStore(), path, persistenceEnabled = true)
        seed.recordFinishedCommand("git status", true, null, null, 1L)
        seed.closeAndFlush()
        val saved = runInterruptible(Dispatchers.IO) { Files.readAllBytes(path) }
        repeat(3) {
            val learning = TerminalCompletionLearningStore()
            val current = registry(learning, path, persistenceEnabled = true)
            current.recordFinishedCommand("npm test", true, null, null, 2L)
            current.closeWithoutFlush()
            current.recordFinishedCommand("late command", true, null, null, 3L)
            assertFalse(learning.snapshot().replayCommands.any { it.commandLine == "late command" })
            assertContentEquals(saved, runInterruptible(Dispatchers.IO) { Files.readAllBytes(path) })
        }
        assertEquals(listOf("git status"), persistedSnapshot(path).replayCommands.map { it.commandLine })
    }

    @Test
    fun `shutdown persists the final learned command`(
        @TempDir directory: Path,
    ) = runTest {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val learning = TerminalCompletionLearningStore()
        val registry = registry(learning, path, persistenceEnabled = true)

        registry.recordFinishedCommand("git status", true, "bash", "file:///repo", 42L)
        registry.closeAndFlush()

        assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
        assertEquals(
            listOf("git status"),
            persistedSnapshot(path).replayCommands.map { it.commandLine },
        )
    }

    @Test
    fun `disabled persistence keeps learning in memory only`(
        @TempDir directory: Path,
    ) = runTest {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val learning = TerminalCompletionLearningStore()
        val registry = registry(learning, path, persistenceEnabled = false)
        registry.recordFinishedCommand("git status", true, null, null, 1L)
        registry.recordFinishedCommand("npm test", true, null, null, 2L)
        registry.closeAndFlush()

        assertEquals(
            setOf("git status", "npm test"),
            learning
                .snapshot()
                .replayCommands
                .map { it.commandLine }
                .toSet(),
        )
        assertFalse(Files.exists(path))
    }

    @Test
    fun `failed final write still closes the registry and remains idempotent`(
        @TempDir directory: Path,
    ) = runTest {
        val parent = directory.resolve("learning")
        val path = parent.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val seedLearning = TerminalCompletionLearningStore()
        val seedRegistry = registry(seedLearning, path, persistenceEnabled = true)
        seedRegistry.recordFinishedCommand("git status", true, null, null, 1L)
        seedRegistry.closeAndFlush()

        val learning = TerminalCompletionLearningStore()
        val registry = registry(learning, path, persistenceEnabled = true)
        runCurrent()
        assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
        runInterruptible(Dispatchers.IO) {
            Files.delete(path)
            Files.delete(parent)
            Files.writeString(parent, "blocks the persistence directory")
        }
        registry.recordFinishedCommand("npm test", true, null, null, 2L)
        val completionJob = requireNotNull(registry.completionScope.coroutineContext[Job])

        assertFailsWith<IOException> { registry.closeAndFlush() }
        assertTrue(completionJob.isCancelled)
        assertFailsWith<IOException> { registry.closeAndFlush() }
        assertFailsWith<IllegalStateException> { registry.createProvider() }
        registry.recordFinishedCommand("late command", true, null, null, 3L)
        assertFalse(learning.snapshot().replayCommands.any { it.commandLine == "late command" })
    }

    @Test
    fun `reset clears memory and saved learning while persistence is disabled`(
        @TempDir directory: Path,
    ) = runTest {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val seed = registry(TerminalCompletionLearningStore(), path, persistenceEnabled = true)
        seed.recordFinishedCommand("git status", true, null, null, 1L)
        seed.closeAndFlush()

        val learning = TerminalCompletionLearningStore()
        val current = registry(learning, path, persistenceEnabled = false)
        current.recordFinishedCommand("npm test", true, null, null, 2L)
        try {
            current.resetLearning().await()
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(path))
            current.setPersistenceEnabled(true)
            runCurrent()
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
        } finally {
            current.closeAndFlush()
        }
        assertFailsWith<IllegalStateException> { current.resetLearning() }
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, persistedSnapshot(path))
    }

    private fun TestScope.registry(
        learning: TerminalCompletionLearningStore,
        path: Path,
        persistenceEnabled: Boolean,
        previousPath: Path? = null,
    ): StandaloneCompletionRegistry =
        StandaloneCompletionRegistry.create(
            persistencePath = path,
            persistenceEnabled = persistenceEnabled,
            specs = emptyList(),
            learningStore = learning,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            previousPersistencePath = previousPath,
        )

    private suspend fun TestScope.persistedSnapshot(path: Path): TerminalCompletionLearningSnapshot =
        coroutineScope {
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    learningStore = learning,
                    coroutineScope = this,
                    persistencePath = path,
                    persistenceEnabled = true,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    onPersistenceLoadFailure = {},
                )
            coordinator.closeAndFlush()
            learning.snapshot()
        }
}
