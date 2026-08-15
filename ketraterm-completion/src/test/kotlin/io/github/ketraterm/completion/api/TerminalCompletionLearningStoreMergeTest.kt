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

import io.github.ketraterm.completion.model.*
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

    @Test
    fun `merge snapshot adds shape aggregates with saturated counters`() {
        val shape = TerminalCommandLineShape(executable = "git", subcommands = listOf("status"))
        val store = TerminalCompletionLearningStore()
        store.replaceSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                shapeStats = listOf(shapeStats(shape, Int.MAX_VALUE, 2, 100)),
            ),
        )

        store.mergeSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                shapeStats = listOf(shapeStats(shape, 5, 3, 200)),
            ),
        )

        val merged = store.snapshot().shapeStats.single()
        assertEquals(Int.MAX_VALUE, merged.useCount)
        assertEquals(5, merged.acceptedCount)
        assertEquals(200, merged.lastUsedEpochMillis)
    }

    @Test
    fun `merge snapshot adds provider feedback by canonical provider context`() {
        val store = TerminalCompletionLearningStore()
        store.replaceSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                feedbackStats = listOf(feedbackStats("file:///repo", 2, 1, 100)),
            ),
        )

        store.mergeSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                feedbackStats = listOf(feedbackStats("file:///repo/", 3, 4, 200)),
            ),
        )

        val merged = store.snapshot().feedbackStats.single()
        assertEquals("file:///repo/", merged.workingDirectoryUri)
        assertEquals(5, merged.acceptedCount)
        assertEquals(5, merged.dismissedCount)
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

    private fun shapeStats(
        shape: TerminalCommandLineShape,
        useCount: Int,
        acceptedCount: Int,
        timestamp: Long,
    ) = TerminalCommandShapeStats(
        shape = shape,
        profileId = "bash",
        workingDirectoryUri = "file:///repo",
        useCount = useCount,
        acceptedCount = acceptedCount,
        lastUsedEpochMillis = timestamp,
    )

    private fun feedbackStats(
        directory: String,
        acceptedCount: Int,
        dismissedCount: Int,
        timestamp: Long,
    ) = TerminalCompletionFeedbackStats(
        source = "spec",
        candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        profileId = "bash",
        workingDirectoryUri = directory,
        acceptedCount = acceptedCount,
        dismissedCount = dismissedCount,
        lastUsedEpochMillis = timestamp,
    )
}
