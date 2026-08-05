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

import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Tests the IntelliJ privacy boundary around learned-completion persistence. */
class IntellijCompletionStatisticsCoordinatorTest {
    @Test
    fun `disabled persistence neither loads nor writes but keeps in-memory learning`() {
        val loaded = AtomicInteger()
        val persisted = AtomicInteger()
        val changed = CountDownLatch(1)
        val stats = TerminalCompletionSources.commandStats(commandSpecs = emptyList())
        val coordinator =
            IntellijCompletionStatisticsCoordinator(
                statsSource = stats,
                loadStats = {
                    loaded.incrementAndGet()
                    TerminalCommandCompletionStatsSnapshot.EMPTY
                },
                persistStats = { persisted.incrementAndGet() },
                initialPersistenceEnabled = false,
                onStatsChanged = changed::countDown,
            )
        try {
            coordinator.recordFinishedCommand("pwsh", successfulCommand("tool local"))

            assertTrue("in-memory mutation timed out", changed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("tool local"), stats.snapshotAll().commandStats.map { it.commandLine })
            assertEquals(0, loaded.get())
            assertEquals(0, persisted.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `enabling persistence loads stored learning when memory is empty`() {
        val loaded = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val stats = TerminalCompletionSources.commandStats(commandSpecs = emptyList())
        val stored =
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(TerminalCommandCompletionStats(commandLine = "tool stored", useCount = 1)),
            )
        val coordinator =
            IntellijCompletionStatisticsCoordinator(
                statsSource = stats,
                loadStats = {
                    loaded.countDown()
                    stored
                },
                persistStats = {},
                initialPersistenceEnabled = false,
                onStatsChanged = changed::countDown,
            )
        try {
            coordinator.setPersistenceEnabled(true)

            assertTrue("stored snapshot load timed out", loaded.await(5, TimeUnit.SECONDS))
            assertTrue("snapshot notification timed out", changed.await(5, TimeUnit.SECONDS))
            assertEquals(stored, stats.snapshotAll())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `enabling persistence preserves and writes existing in-memory learning`() {
        val loaded = AtomicInteger()
        val persisted = CountDownLatch(1)
        val stats = TerminalCompletionSources.commandStats(commandSpecs = emptyList())
        stats.recordCommandResult(
            commandLine = "tool current",
            successful = true,
            profileId = null,
            workingDirectoryUri = null,
            usedAtEpochMillis = 1_000L,
        )
        val coordinator =
            IntellijCompletionStatisticsCoordinator(
                statsSource = stats,
                loadStats = {
                    loaded.incrementAndGet()
                    TerminalCommandCompletionStatsSnapshot.EMPTY
                },
                persistStats = { persisted.countDown() },
                initialPersistenceEnabled = false,
                onStatsChanged = {},
            )
        try {
            coordinator.setPersistenceEnabled(true)

            assertTrue("current snapshot persistence timed out", persisted.await(5, TimeUnit.SECONDS))
            assertEquals(0, loaded.get())
            assertFalse(stats.snapshotAll().commandStats.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `disabling persistence cancels a queued enable before disk access`() {
        val mutationEnteredNotification = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val loaded = AtomicInteger()
        val stats = TerminalCompletionSources.commandStats(commandSpecs = emptyList())
        val coordinator =
            IntellijCompletionStatisticsCoordinator(
                statsSource = stats,
                loadStats = {
                    loaded.incrementAndGet()
                    TerminalCommandCompletionStatsSnapshot.EMPTY
                },
                persistStats = {},
                initialPersistenceEnabled = false,
                onStatsChanged = {
                    mutationEnteredNotification.countDown()
                    releaseMutation.await(5, TimeUnit.SECONDS)
                },
            )
        try {
            coordinator.recordFinishedCommand("pwsh", successfulCommand("tool local"))
            assertTrue("mutation did not reach notification", mutationEnteredNotification.await(5, TimeUnit.SECONDS))

            coordinator.setPersistenceEnabled(true)
            coordinator.setPersistenceEnabled(false)
            releaseMutation.countDown()
        } finally {
            coordinator.close()
        }

        assertEquals(0, loaded.get())
    }

    private fun successfulCommand(command: String): TerminalShellIntegrationCommandMetadata =
        TerminalShellIntegrationCommandMetadata(
            recordId = 1,
            lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            commandText = command,
            workingDirectoryUri = null,
            exitCode = 0,
            startedAtEpochMillis = 500L,
            finishedAtEpochMillis = 1_000L,
        )
}
