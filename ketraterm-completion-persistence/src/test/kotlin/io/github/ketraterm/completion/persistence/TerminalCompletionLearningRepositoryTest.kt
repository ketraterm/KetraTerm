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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TerminalCompletionLearningRepositoryTest {
    @Test
    fun `initial configured path merges with preexisting memory learning`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("npm test", true, null, null, 42L)
            val repository = repository(learning, path)

            repository.initialize()

            assertEquals(
                setOf("git status", "npm test"),
                learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine },
            )
        }

    @Test
    fun `missing initial path keeps preexisting memory learning`() =
        runTest {
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("git status", true, null, null, 42L)
            val repository = repository(learning, completionLearningPath())

            repository.initialize()

            assertEquals(listOf("git status"), learning.snapshot().commandStats.map { it.commandLine })
        }

    @Test
    fun `first enable of the same fixed path merges offline session learning once`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, path, enabled = false)

            repository.initialize()
            learning.recordCommandResult("npm test", true, null, null, 42L)
            repository.setPersistenceEnabled(true)

            assertEquals(
                setOf("git status", "npm test"),
                learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine },
            )
        }

    @Test
    fun `re-enabling an initialized fixed path keeps offline learning without reloading`() =
        runTest {
            val path = completionLearningPath()
            seedCommand(path, "git status", 41L)
            val learning = TerminalCompletionLearningStore()
            val repository = repository(learning, path)

            repository.initialize()
            repository.setPersistenceEnabled(false)
            learning.recordCommandResult("npm test", true, null, null, 42L)
            seedCommand(path, "external rewrite", 43L)
            repository.setPersistenceEnabled(true)

            assertEquals(
                setOf("git status", "npm test"),
                learning.snapshot().commandStats.mapTo(mutableSetOf()) { it.commandLine },
            )
            val request = assertNotNull(repository.writeRequestOrNull())
            assertEquals(path.toAbsolutePath().normalize(), request.path)
            assertEquals(learning.snapshot(), repository.materialize(request).snapshot)
        }

    @Test
    fun `rejected initial file keeps memory and blocks writes`() =
        runTest {
            val rejectedPath = completionLearningPath()
            val originalBytes = "KetraTerm_COMMAND_COMPLETION_STATS\t999\n".encodeToByteArray()
            Files.write(rejectedPath, originalBytes)
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("old namespace", true, null, null, 1L)
            val repository = repository(learning, rejectedPath)

            repository.initialize()

            assertEquals(listOf("old namespace"), learning.snapshot().commandStats.map { it.commandLine })
            assertNull(repository.writeRequestOrNull())
            assertContentEquals(originalBytes, Files.readAllBytes(rejectedPath))
        }

    @Test
    fun `read failure reports diagnostics and blocks the active path`() =
        runTest {
            val path = completionLearningPath()
            Files.writeString(path, "KetraTerm_COMMAND_COMPLETION_STATS\t1")
            val expectedFailure = IOException("test read failure")
            val failures = mutableListOf<Throwable>()
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("old namespace", true, null, null, 1L)
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    persistencePath = path,
                    persistenceEnabled = true,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    onPersistenceFailure = failures::add,
                    fileStoreFactory = { storePath, onFailure ->
                        CompletionLearningFileStore(storePath, onFailure, openInput = { throw expectedFailure })
                    },
                )

            repository.initialize()

            assertSame(expectedFailure, failures.single())
            assertEquals(listOf("old namespace"), learning.snapshot().commandStats.map { it.commandLine })
            assertNull(repository.writeRequestOrNull())
        }

    private fun completionLearningPath(): Path =
        createTempDirectory("completion-learning").resolve(TerminalCompletionLearningCoordinator.currentFileName())

    private fun seedCommand(
        path: Path,
        commandLine: String,
        usedAtEpochMillis: Long,
    ) {
        val learning = TerminalCompletionLearningStore()
        learning.recordCommandResult(commandLine, true, null, null, usedAtEpochMillis)
        CompletionLearningFileStore(path).persist(learning.snapshot())
    }

    private fun TestScope.repository(
        learning: TerminalCompletionLearningStore,
        path: Path,
        enabled: Boolean = true,
    ): TerminalCompletionLearningRepository =
        TerminalCompletionLearningRepository(
            learningStore = learning,
            persistencePath = path,
            persistenceEnabled = enabled,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
}
