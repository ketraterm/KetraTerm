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
package io.github.ketraterm.completion.stats

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionStatsIndexesTest {
    @Test
    fun `exact command index merges duplicates and keeps stable relevance order`() {
        val index = CommandCompletionStatsIndex(capacity = 8)

        index.mergeAll(
            listOf(
                commandStats("Git Status", lastUsedEpochMillis = 10),
                commandStats("npm test", lastUsedEpochMillis = 30),
                commandStats("git status", lastUsedEpochMillis = 20),
            ),
        )

        assertEquals(listOf("npm test", "git status"), index.snapshot().map { it.commandLine })
        assertEquals(listOf(30L, 20L), index.snapshot().map { it.lastUsedEpochMillis })
        assertEquals(2, index.snapshot()[1].successCount)
    }

    @Test
    fun `exact command index records counters and evicts least relevant rows`() {
        val index = CommandCompletionStatsIndex(capacity = 2)

        index.recordCommandResult("one", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 1)
        index.recordCommandResult("two", successful = false, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 2)
        index.recordSuggestionFeedback(
            commandLine = "two",
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 3,
        )
        index.recordSuggestionFeedback(
            commandLine = "two",
            feedback = TerminalCompletionFeedbackKind.DISMISSED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 4,
        )
        index.recordCommandResult("three", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 5)

        assertEquals(listOf("three", "two"), index.snapshot().map { it.commandLine })
        assertEquals(1, index.snapshot()[1].failureCount)
        assertEquals(1, index.snapshot()[1].acceptedCount)
        assertEquals(1, index.snapshot()[1].dismissedCount)
        assertEquals(4L, index.snapshot()[1].lastUsedEpochMillis)
    }

    @Test
    fun `exact command index keeps display casing from newest event when older feedback arrives later`() {
        val index = CommandCompletionStatsIndex(capacity = 8)

        index.recordCommandResult(
            commandLine = "Git Status",
            successful = true,
            profileId = null,
            workingDirectoryUri = null,
            usedAtEpochMillis = 100,
        )
        index.recordSuggestionFeedback(
            commandLine = "git status",
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 50,
        )

        assertEquals("Git Status", index.snapshot().single().commandLine)
        assertEquals(1, index.snapshot().single().acceptedCount)
        assertEquals(100L, index.snapshot().single().lastUsedEpochMillis)
    }

    @Test
    fun `exact command index deduplicates canonical directory variants`() {
        val index = CommandCompletionStatsIndex(capacity = 8)
        index.mergeAll(
            listOf(
                TerminalCommandCompletionStats(
                    commandLine = "git status",
                    profileId = "profile",
                    workingDirectoryUri = "file:///repo/",
                    lastUsedEpochMillis = 1,
                ),
                TerminalCommandCompletionStats(
                    commandLine = "git status",
                    profileId = "profile",
                    workingDirectoryUri = "file:///repo",
                    lastUsedEpochMillis = 2,
                ),
            ),
        )

        assertEquals(listOf(2L), index.snapshot().map { it.lastUsedEpochMillis })
        assertEquals(listOf("file:///repo/"), index.snapshot().map { it.workingDirectoryUri })
    }

    private fun commandStats(
        commandLine: String,
        lastUsedEpochMillis: Long,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            successCount = 1,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
