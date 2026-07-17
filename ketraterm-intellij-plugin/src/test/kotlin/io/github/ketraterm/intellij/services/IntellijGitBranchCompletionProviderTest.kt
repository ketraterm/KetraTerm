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

import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntellijGitBranchCompletionProviderTest {
    @Test
    fun `publishes immutable ready snapshot after background load`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val provider =
            provider(scheduler, { publications++ }) { key ->
                listOf(TerminalCompletionDomainValue("$key-branch"))
            }

        assertTrue(provider.values().isEmpty())
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals(listOf("first-branch"), provider.values().map { it.value })
    }

    @Test
    fun `working directory change rejects stale branch publication`() {
        val scheduler = RecordingScheduler()
        var key: String? = "first"
        var publications = 0
        val provider =
            IntellijGitBranchCompletionProvider(
                workingDirectoryUriProvider = { key },
                scheduler = scheduler,
                loader = { value -> listOf(TerminalCompletionDomainValue("$value-branch")) },
                onSnapshotChanged = { publications++ },
                snapshotTtlNanos = Long.MAX_VALUE,
            )

        provider.values()
        key = "second"
        provider.values()
        scheduler.runNext()
        assertEquals(0, publications)
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals("second-branch", provider.values().single().value)
    }

    @Test
    fun `closed provider discards in-flight results`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val provider = provider(scheduler, { publications++ }) { listOf(TerminalCompletionDomainValue("main")) }

        provider.values()
        provider.close()
        scheduler.runNext()

        assertEquals(0, publications)
        assertTrue(provider.values().isEmpty())
    }

    private fun provider(
        scheduler: RecordingScheduler,
        onSnapshotChanged: () -> Unit,
        loader: (String?) -> List<TerminalCompletionDomainValue>,
    ): IntellijGitBranchCompletionProvider =
        IntellijGitBranchCompletionProvider(
            workingDirectoryUriProvider = { "first" },
            scheduler = scheduler,
            loader = loader,
            onSnapshotChanged = onSnapshotChanged,
            snapshotTtlNanos = Long.MAX_VALUE,
        )

    private class RecordingScheduler : IntellijCompletionLoadScheduler {
        private val tasks = ArrayDeque<suspend () -> Unit>()

        override fun schedule(work: suspend () -> Unit): Boolean {
            tasks.addLast(work)
            return true
        }

        fun runNext() {
            runBlocking { tasks.removeFirst().invoke() }
        }
    }
}
