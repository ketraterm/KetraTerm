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
import io.github.ketraterm.completion.api.TerminalCompletionSources
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionLearningRepositoryTest {
    @Test
    fun `mutation is persisted through one suspending repository`() =
        runTest {
            val path = createTempDirectory("completion-learning").resolve(TerminalCompletionStatsStore.currentFileName())
            val learning = TerminalCompletionSources.learningStore()
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    initialPersistencePath = path,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            repository.initialize()
            repository.mutate {
                recordCommandResult("git status", true, null, null, 42L)
            }

            assertEquals(learning.snapshotAll(), TerminalCompletionStatsStore(path).loadSnapshot())
        }

    @Test
    fun `first mutation loads persisted learning before replacing the snapshot`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionSources.learningStore()
            val repository = repository(learning, path)

            repository.mutate {
                recordCommandResult("git log", true, null, null, 42L)
            }

            assertEquals(setOf("git status", "git log"), learning.snapshotAll().commandStats.mapTo(mutableSetOf()) { it.commandLine })
            assertEquals(learning.snapshotAll(), TerminalCompletionStatsStore(path).loadSnapshot())
        }

    @Test
    fun `initialize does not reload a path that was already initialized`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionSources.learningStore()
            val repository = repository(learning, path)

            repository.initialize()
            seedCommand(path, "git log", 42L)
            repository.initialize()

            assertEquals(listOf("git status"), learning.snapshotAll().commandStats.map { it.commandLine })
        }

    @Test
    fun `path reconfiguration initializes only the replacement path`() =
        runTest {
            val initialPath = completionLearningPath()
            val replacementPath = completionLearningPath()
            seedCommand(initialPath, "git status", 41L)
            seedCommand(replacementPath, "git log", 42L)
            val learning = TerminalCompletionSources.learningStore()
            val repository = repository(learning, initialPath)

            repository.setPersistencePath(replacementPath)

            assertEquals(listOf("git log"), learning.snapshotAll().commandStats.map { it.commandLine })
            assertEquals(learning.snapshotAll(), TerminalCompletionStatsStore(replacementPath).loadSnapshot())
        }

    @Test
    fun `disabling persistence before initialization does not read the configured path`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionSources.learningStore()
            val repository = repository(learning, path)

            repository.setPersistenceEnabled(false)

            assertEquals(emptyList(), learning.snapshotAll().commandStats)
        }

    private fun completionLearningPath(): Path =
        createTempDirectory("completion-learning").resolve(TerminalCompletionStatsStore.currentFileName())

    private fun seedCommand(
        path: Path,
        commandLine: String,
        usedAtEpochMillis: Long,
    ) {
        val learning = TerminalCompletionSources.learningStore()
        learning.recordCommandResult(commandLine, true, null, null, usedAtEpochMillis)
        TerminalCompletionStatsStore(path).persist(learning.snapshotAll())
    }

    private fun TestScope.repository(
        learning: TerminalCompletionLearningStore,
        path: Path,
    ): TerminalCompletionLearningRepository =
        TerminalCompletionLearningRepository(
            learningStore = learning,
            initialPersistencePath = path,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
}
