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

import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.persistence.TerminalCompletionStatsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneCompletionStatisticsCoordinatorTest {
    @Test
    fun `command learning uses the lifecycle scope and repository`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionStatsStore.currentFileName())
        val learning = TerminalCompletionSources.learningStore()
        val coordinator = StandaloneCompletionStatisticsCoordinator(learning, path, this)

        coordinator.recordFinishedCommand("git status", true, "bash", "file:///repo", 42L)
        coroutineContext[Job]?.children?.toList()?.joinAll()

        assertEquals(listOf("git status"), learning.snapshot().map { it.commandLine })
        assertEquals(
            listOf("git status"),
            TerminalCompletionStatsStore(path).loadSnapshot().commandStats.map { it.commandLine },
        )
    }

    @Test
    fun `null persistence path keeps later learning in memory only`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionStatsStore.currentFileName())
        val learning = TerminalCompletionSources.learningStore()
        val coordinator = StandaloneCompletionStatisticsCoordinator(learning, path, this)
        coordinator.recordFinishedCommand("git status", true, null, null, 1L)
        coordinator.setPersistencePath(null)
        coordinator.recordFinishedCommand("npm test", true, null, null, 2L)
        coroutineContext[Job]?.children?.toList()?.joinAll()

        assertEquals(setOf("git status", "npm test"), learning.snapshot().map { it.commandLine }.toSet())
        assertEquals(
            listOf("git status"),
            TerminalCompletionStatsStore(path).loadSnapshot().commandStats.map { it.commandLine },
        )
    }
}
