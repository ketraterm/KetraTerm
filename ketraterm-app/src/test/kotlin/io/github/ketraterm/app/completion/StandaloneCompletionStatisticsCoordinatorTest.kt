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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneCompletionStatisticsCoordinatorTest {
    @Test
    fun `disabling persistence stops subsequent learning from reaching disk`(
        @TempDir tempDirectory: Path,
    ) {
        val path = tempDirectory.resolve(TerminalCompletionStatsStore.currentFileName())
        val source = TerminalCompletionSources.commandStats()

        StandaloneCompletionStatisticsCoordinator(source, path).use { coordinator ->
            coordinator.recordFinishedCommand(
                commandLine = "git status",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 1_000L,
            )
            coordinator.setPersistencePath(null)
            coordinator.recordFinishedCommand(
                commandLine = "npm test",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 2_000L,
            )
        }

        assertEquals(setOf("git status", "npm test"), source.snapshot().map { it.commandLine }.toSet())
        TerminalCompletionStatsStore(path).use { reloaded ->
            assertEquals(listOf("git status"), reloaded.loadSnapshot().commandStats.map { it.commandLine })
        }
    }
}
