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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TerminalCompletionLearningStoreReplayFilterTest {
    @Test
    fun `denying all replay preserves execution and feedback evidence`() {
        val store = TerminalCompletionLearningStore(replayFilter = { false })
        assertTrue(store.recordCommandResult("git status", true, null, null, 1L))
        assertTrue(store.recordCommandResult("git status", false, null, null, 2L))
        assertTrue(store.recordSuggestionFeedback("git status", TerminalCompletionFeedbackKind.ACCEPTED, null, null, 3L))
        assertTrue(store.recordSuggestionFeedback("git status", TerminalCompletionFeedbackKind.DISMISSED, null, null, 4L))

        val snapshot = store.snapshot()
        val stats = snapshot.rankingStats.single()
        assertEquals(2, stats.useCount)
        assertEquals(1, stats.successCount)
        assertEquals(1, stats.failureCount)
        assertEquals(1, stats.acceptedCount)
        assertEquals(1, stats.dismissedCount)
        assertEquals(4L, stats.lastUsedEpochMillis)
        assertTrue(snapshot.replayCommands.isEmpty())
        assertFalse("git status" in snapshot.toString())
        assertSame(snapshot, store.snapshot())
    }

    @Test
    fun `host filter restricts built-in approval without changing ranking evidence`() {
        val inspected = mutableListOf<String>()
        val filtered =
            TerminalCompletionLearningStore(replayFilter = { command ->
                inspected += command
                !command.startsWith("acme ")
            })
        val defaults = TerminalCompletionLearningStore()
        val commands =
            listOf("git status", "acme deploy prod", "curl -u alice:pass https://example.test", "git\u0000status", "x".repeat(4_097))
        for ((index, command) in commands.withIndex()) {
            assertTrue(filtered.recordCommandResult(command, true, null, null, index + 1L))
            assertTrue(defaults.recordCommandResult(command, true, null, null, index + 1L))
        }

        assertEquals(listOf("git status", "acme deploy prod"), inspected)
        assertEquals(defaults.snapshot().rankingStats, filtered.snapshot().rankingStats)
        assertEquals(listOf("git status"), filtered.snapshot().replayCommands.map { it.commandLine })
    }

    @Test
    fun `unsuccessful executions feedback and invalid events never call the filter`() {
        val store = TerminalCompletionLearningStore(replayFilter = { error("unexpected replay admission") })
        assertTrue(store.recordCommandResult("git status", false, null, null, 1L))
        assertTrue(store.recordSuggestionFeedback("git status", TerminalCompletionFeedbackKind.ACCEPTED, null, null, 2L))
        assertTrue(store.recordSuggestionFeedback("git status", TerminalCompletionFeedbackKind.DISMISSED, null, null, 3L))
        assertFalse(store.recordCommandResult("", true, null, null, 4L))
        assertFalse(store.recordCommandResult("git\nstatus", true, null, null, 4L))
        assertFalse(store.recordCommandResult("git\uD800status", true, null, null, 4L))
        assertFalse(store.recordCommandResult("git status", true, null, null, -1L))

        val stats = store.snapshot().rankingStats.single()
        assertEquals(1, stats.failureCount)
        assertEquals(1, stats.acceptedCount)
        assertEquals(1, stats.dismissedCount)
        assertTrue(store.snapshot().replayCommands.isEmpty())
    }

    @Test
    fun `snapshot imports retain all evidence but only approved replay`() {
        val imported =
            learningSnapshot(
                commandLearning("git status", successCount = 2, lastUsedEpochMillis = 1L),
                commandLearning("acme deploy prod", successCount = 3, lastUsedEpochMillis = 2L),
                commandLearning("curl -u alice:pass https://example.test", successCount = 1, lastUsedEpochMillis = 3L),
            )
        val inspected = mutableListOf<String>()
        val filtered =
            TerminalCompletionLearningStore(replayFilter = { command ->
                inspected += command
                !command.startsWith("acme ")
            })
        val opaqueOnly = TerminalCompletionLearningStore(replayFilter = { false })
        val defaults = TerminalCompletionLearningStore()
        filtered.mergeSnapshot(imported)
        opaqueOnly.mergeSnapshot(imported)
        defaults.mergeSnapshot(imported)

        assertEquals(listOf("git status", "acme deploy prod"), inspected)
        assertEquals(defaults.snapshot().rankingStats, filtered.snapshot().rankingStats)
        assertEquals(defaults.snapshot().rankingStats, opaqueOnly.snapshot().rankingStats)
        assertEquals(listOf("git status"), filtered.snapshot().replayCommands.map { it.commandLine })
        assertTrue(opaqueOnly.snapshot().replayCommands.isEmpty())
        assertEquals(3, imported.replayCommands.size, "the caller's snapshot stays unchanged")
    }

    @Test
    fun `filter failures abort recording and merging before mutation`() {
        val failure = IllegalStateException("host policy unavailable")
        val store =
            TerminalCompletionLearningStore(replayFilter = { command ->
                if (command == "git log") throw failure
                true
            })
        store.recordCommandResult("git status", true, null, null, 1L)
        val before = store.snapshot()

        assertSame(failure, assertFailsWith<IllegalStateException> { store.recordCommandResult("git log", true, null, null, 2L) })
        assertSame(before, store.snapshot())

        val imported =
            learningSnapshot(
                commandLearning("git diff", successCount = 1, lastUsedEpochMillis = 2L),
                commandLearning("git log", successCount = 1, lastUsedEpochMillis = 3L),
            )
        assertSame(failure, assertFailsWith<IllegalStateException> { store.mergeSnapshot(imported) })
        assertSame(before, store.snapshot())
    }

    @Test
    fun `custom filtering preserves capacity validation and bounded ranking`() {
        assertFailsWith<IllegalArgumentException> { TerminalCompletionLearningStore(replayFilter = { false }, capacity = 0) }
        assertFailsWith<IllegalArgumentException> { TerminalCompletionLearningStore(replayFilter = { false }, capacity = -1) }
        val store = TerminalCompletionLearningStore(replayFilter = { false }, capacity = 1)
        store.recordCommandResult("git status", true, null, null, 1L)
        store.recordCommandResult("git log", true, null, null, 2L)
        store.mergeSnapshot(learningSnapshot(commandLearning("git diff", successCount = 1, lastUsedEpochMillis = 3L)))

        assertEquals(
            learningSnapshot(commandLearning("git diff", successCount = 1, lastUsedEpochMillis = 3L)).rankingStats,
            store.snapshot().rankingStats,
        )
        assertTrue(store.snapshot().replayCommands.isEmpty())
        store.clear()
        store.recordCommandResult("git status", true, null, null, 4L)
        assertTrue(store.snapshot().replayCommands.isEmpty(), "clearing does not change admission policy")
    }

    @Test
    fun `a blocked filter does not block other store operations`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val store =
            TerminalCompletionLearningStore(replayFilter = { command ->
                if (command == "git log") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "filter release was not signalled" }
                }
                true
            })
        try {
            store.recordCommandResult("git status", true, null, null, 1L)
            val recording = executor.submit<Boolean> { store.recordCommandResult("git log", true, null, null, 2L) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "recording must reach the filter")
                val feedback =
                    executor.submit<Boolean> {
                        store.recordSuggestionFeedback("git status", TerminalCompletionFeedbackKind.ACCEPTED, null, null, 3L)
                    }
                assertTrue(feedback.get(10, TimeUnit.SECONDS))
                assertEquals(
                    1,
                    store
                        .snapshot()
                        .rankingStats
                        .single()
                        .acceptedCount,
                )
            } finally {
                release.countDown()
            }
            assertTrue(recording.get(10, TimeUnit.SECONDS))
            assertEquals(2, store.snapshot().rankingStats.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
