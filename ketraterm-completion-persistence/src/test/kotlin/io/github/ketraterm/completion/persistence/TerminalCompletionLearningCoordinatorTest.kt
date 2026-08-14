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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionLearningCoordinatorTest {
    @Test
    fun `caller scope serializes initialization mutation and persistence`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionLearningRepository.currentFileName())
        val store = TerminalCompletionLearningStore()
        val coordinator =
            TerminalCompletionLearningCoordinator(
                TerminalCompletionLearningRepository(store, path),
                this,
            )

        coordinator.recordCommandResult("git status", true, "bash", "file:///repo", 42L)
        coordinator.flush()

        assertEquals(listOf("git status"), store.snapshot().commandStats.map { it.commandLine })
        val reloaded = TerminalCompletionLearningStore()
        TerminalCompletionLearningRepository(reloaded, path).initialize()
        assertEquals(store.snapshot(), reloaded.snapshot())
        coordinator.closeAndFlush()
    }

    @Test
    fun `shared privacy policy rejects sensitive commands before mutation`() =
        runBlocking {
            val store = TerminalCompletionLearningStore()
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    TerminalCompletionLearningRepository(store),
                    this,
                )

            coordinator.recordCommandResult("docker login --password secret", true, null, null, 42L)
            coordinator.flush()

            assertEquals(emptyList(), store.snapshot().commandStats)
            coordinator.closeAndFlush()
        }

    @Test
    fun `commands execute in submission order and close flushes the final write`(
        @TempDir directory: Path,
    ) = runBlocking {
        val firstPath = directory.resolve("first.tsv")
        val secondPath = directory.resolve("second.tsv")
        val store = TerminalCompletionLearningStore()
        val coordinator =
            TerminalCompletionLearningCoordinator(
                TerminalCompletionLearningRepository(store, firstPath),
                this,
            )

        coordinator.recordCommandResult("git status", true, null, null, 1L)
        coordinator.setPersistencePath(secondPath)
        coordinator.recordCommandResult("npm test", true, null, null, 2L)
        coordinator.closeAndFlush()

        val firstReloaded = TerminalCompletionLearningStore()
        TerminalCompletionLearningRepository(firstReloaded, firstPath).initialize()
        val secondReloaded = TerminalCompletionLearningStore()
        TerminalCompletionLearningRepository(secondReloaded, secondPath).initialize()
        assertEquals(listOf("git status"), firstReloaded.snapshot().commandStats.map { it.commandLine })
        assertEquals(
            setOf("git status", "npm test"),
            secondReloaded.snapshot().commandStats.mapTo(HashSet()) { it.commandLine },
        )
    }
}
