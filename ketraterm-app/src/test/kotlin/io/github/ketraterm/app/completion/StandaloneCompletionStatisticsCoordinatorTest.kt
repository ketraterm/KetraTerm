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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StandaloneCompletionStatisticsCoordinatorTest {
    @Test
    fun `shutdown persists the final learned command`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val learning = TerminalCompletionLearningStore()
        val coordinator = StandaloneCompletionStatisticsCoordinator(learning, path, true, this)

        coordinator.recordFinishedCommand("git status", true, "bash", "file:///repo", 42L)
        coordinator.closeAndFlush()

        assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
        assertEquals(
            listOf("git status"),
            persistedSnapshot(path).commandStats.map { it.commandLine },
        )
    }

    @Test
    fun `disabled persistence keeps learning in memory only`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val learning = TerminalCompletionLearningStore()
        val coordinator = StandaloneCompletionStatisticsCoordinator(learning, path, false, this)
        coordinator.recordFinishedCommand("git status", true, null, null, 1L)
        coordinator.recordFinishedCommand("npm test", true, null, null, 2L)
        coordinator.closeAndFlush()

        assertEquals(
            setOf("git status", "npm test"),
            learning
                .snapshot()
                .commandStats
                .map { it.commandLine }
                .toSet(),
        )
        assertFalse(Files.exists(path))
    }

    private suspend fun persistedSnapshot(path: Path): TerminalCommandCompletionStatsSnapshot =
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
}
