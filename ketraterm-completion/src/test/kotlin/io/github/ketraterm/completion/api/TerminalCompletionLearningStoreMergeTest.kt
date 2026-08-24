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
    fun `merge snapshot adds exact command aggregates by canonical context`() {
        val store = TerminalCompletionLearningStore()
        store.replaceSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandStats("Git Status", "file:///repo", 3, 1, 100)),
            ),
        )

        store.mergeSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandStats("git status", "file:///repo/", 4, 2, 200)),
            ),
        )

        val merged = store.snapshot().commandStats.single()
        assertEquals("git status", merged.commandLine)
        assertEquals("file:///repo/", merged.workingDirectoryUri)
        assertEquals(7, merged.useCount)
        assertEquals(3, merged.successCount)
        assertEquals(200, merged.lastUsedEpochMillis)
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
