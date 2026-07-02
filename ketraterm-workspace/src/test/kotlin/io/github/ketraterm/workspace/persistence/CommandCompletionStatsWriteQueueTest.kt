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
package io.github.ketraterm.workspace.persistence

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandCompletionStatsWriteQueueTest {
    @Test
    fun `coalesces pending snapshots while a write is active`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<TerminalCommandCompletionStatsSnapshot>()
        val queue =
            CommandCompletionStatsWriteQueue(
                writeSnapshot = { snapshot ->
                    synchronized(written) {
                        written += snapshot
                    }
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                },
                threadName = "ketraterm-test-command-stats",
            )

        queue.enqueue(snapshot("first"))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        queue.enqueue(snapshot("second"))
        queue.enqueue(snapshot("third"))
        release.countDown()

        queue.flush()
        queue.close()

        assertEquals(listOf("first", "third"), written.map { it.commandStats.single().commandLine })
    }

    @Test
    fun `flush writes latest queued snapshot`() {
        val written = mutableListOf<TerminalCommandCompletionStatsSnapshot>()
        val queue =
            CommandCompletionStatsWriteQueue(
                writeSnapshot = { snapshot -> written += snapshot },
                threadName = "ketraterm-test-command-stats",
            )

        queue.enqueue(snapshot("git status"))
        queue.flush()
        queue.close()

        assertEquals(listOf("git status"), written.map { it.commandStats.single().commandLine })
    }

    @Test
    fun `close writes latest queued snapshot`() {
        val written = mutableListOf<TerminalCommandCompletionStatsSnapshot>()
        val queue =
            CommandCompletionStatsWriteQueue(
                writeSnapshot = { snapshot -> written += snapshot },
                threadName = "ketraterm-test-command-stats",
            )

        queue.enqueue(snapshot("npm test"))
        queue.close()

        assertEquals(listOf("npm test"), written.map { it.commandStats.single().commandLine })
    }

    @Test
    fun `enqueue after close is ignored`() {
        val written = mutableListOf<TerminalCommandCompletionStatsSnapshot>()
        val queue =
            CommandCompletionStatsWriteQueue(
                writeSnapshot = { snapshot -> written += snapshot },
                threadName = "ketraterm-test-command-stats",
            )

        queue.close()
        queue.enqueue(snapshot("git status"))
        queue.flush()

        assertEquals(emptyList(), written)
    }

    private fun snapshot(commandLine: String): TerminalCommandCompletionStatsSnapshot =
        TerminalCommandCompletionStatsSnapshot(
            commandStats =
                listOf(
                    TerminalCommandCompletionStats(
                        commandLine = commandLine,
                        useCount = 1,
                        successCount = 1,
                        lastUsedEpochMillis = 1,
                    ),
                ),
        )
}
