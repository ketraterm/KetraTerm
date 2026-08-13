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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class IntellijCompletionStatisticsCoordinatorTest {
    @Test
    fun `finished command is serialized and persisted by the repository`() =
        runBlocking {
            val directory = Files.createTempDirectory("intellij-completion-learning")
            val path = directory.resolve(TerminalCompletionLearningRepository.currentFileName())
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                IntellijCompletionStatisticsCoordinator(
                    TerminalCompletionLearningRepository(learning, path),
                    this,
                )
            coordinator.recordFinishedCommand(
                "bash",
                TerminalShellIntegrationCommandMetadata(
                    recordId = 1,
                    commandText = "git status",
                    lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                    workingDirectoryUri = "file:///repo",
                    exitCode = 0,
                    startedAtEpochMillis = 1L,
                    finishedAtEpochMillis = 42L,
                ),
            )
            coroutineContext[Job]?.children?.toList()?.joinAll()

            assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
            assertEquals(
                listOf("git status"),
                persistedSnapshot(path).commandStats.map { it.commandLine },
            )
        }

    private suspend fun persistedSnapshot(path: Path): TerminalCommandCompletionStatsSnapshot {
        val learning = TerminalCompletionLearningStore()
        TerminalCompletionLearningRepository(learning, path).initialize()
        return learning.snapshot()
    }
}
