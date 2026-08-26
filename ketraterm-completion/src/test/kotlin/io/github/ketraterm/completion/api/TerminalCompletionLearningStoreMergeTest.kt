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

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionLearningStoreMergeTest {
    @Test
    fun `merge snapshot canonicalizes context without collapsing command case`() {
        val store = TerminalCompletionLearningStore()
        store.mergeSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandStats("Git Status", "file:///repo", 3, 1, 100)),
            ),
        )

        store.mergeSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandStats("git status", "file:///repo/", 4, 2, 200)),
            ),
        )

        val rows = store.snapshot().commandStats
        assertEquals(listOf("git status", "Git Status"), rows.map { it.commandLine })
        assertEquals(listOf("file:///repo/", "file:///repo/"), rows.map { it.workingDirectoryUri })
        assertEquals(listOf(4, 3), rows.map { it.useCount })
        assertEquals(listOf(2, 1), rows.map { it.successCount })
        assertEquals(listOf(200L, 100L), rows.map { it.lastUsedEpochMillis })
    }

    private fun commandStats(
        commandLine: String,
        directory: String,
        useCount: Int,
        successCount: Int,
        timestamp: Long,
    ) = TerminalCommandCompletionStats(
        commandLine = commandLine,
        profileId = "bash",
        workingDirectoryUri = directory,
        useCount = useCount,
        successCount = successCount,
        lastUsedEpochMillis = timestamp,
    )
}
