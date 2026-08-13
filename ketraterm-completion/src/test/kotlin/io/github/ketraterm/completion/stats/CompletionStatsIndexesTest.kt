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

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.commandline.classifyGenericCommandLineShape
import io.github.ketraterm.completion.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionStatsIndexesTest {
    @Test
    fun `exact command index keeps newest duplicate and stable relevance order`() {
        val index = CommandCompletionStatsIndex(capacity = 8)

        index.replaceAll(
            listOf(
                commandStats("Git Status", lastUsedEpochMillis = 10),
                commandStats("npm test", lastUsedEpochMillis = 30),
                commandStats("git status", lastUsedEpochMillis = 20),
            ),
        )

        assertEquals(listOf("npm test", "git status"), index.snapshot().map { it.commandLine })
        assertEquals(listOf(30L, 20L), index.snapshot().map { it.lastUsedEpochMillis })
    }

    @Test
    fun `exact command index records counters ignores malformed events and evicts least relevant rows`() {
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
        index.recordCommandResult("   ", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 6)
        index.recordCommandResult(
            "four\nfive",
            successful = true,
            profileId = null,
            workingDirectoryUri = null,
            usedAtEpochMillis = 7,
        )
        index.recordCommandResult("six", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = -1)

        assertEquals(listOf("three", "two"), index.snapshot().map { it.commandLine })
        assertEquals(1, index.snapshot()[1].failureCount)
        assertEquals(1, index.snapshot()[1].acceptedCount)
        assertEquals(1, index.snapshot()[1].dismissedCount)
        assertEquals(4L, index.snapshot()[1].lastUsedEpochMillis)
    }

    @Test
    fun `shape index keeps newest duplicate and does not retain raw arguments`() {
        val index = CommandShapeStatsIndex(capacity = 8, commandSpecs = TerminalCommandSpecs.defaults())

        index.replaceAll(
            listOf(
                shapeStats("git switch private-branch", lastUsedEpochMillis = 10),
                shapeStats("npm test", lastUsedEpochMillis = 30),
                shapeStats("git switch another-private-branch", lastUsedEpochMillis = 20),
            ),
        )

        assertEquals(listOf("npm|test||p=0|ov=0", "git|switch||p=1|ov=0"), index.snapshot().map { it.shape.normalizedShapeKey })
        assertTrue(index.snapshot().none { "private-branch" in it.shape.normalizedShapeKey })
        assertEquals(listOf(30L, 20L), index.snapshot().map { it.lastUsedEpochMillis })
    }

    @Test
    fun `shape index records counters ignores malformed commands and evicts least relevant rows`() {
        val index = CommandShapeStatsIndex(capacity = 2, commandSpecs = emptyList())

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
        index.recordCommandResult("   ", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 6)
        index.recordCommandResult("four\rfive", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 7)
        index.recordCommandResult("six", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = -1)

        assertEquals(listOf("three|||p=0|ov=0", "two|||p=0|ov=0"), index.snapshot().map { it.shape.normalizedShapeKey })
        assertEquals(1, index.snapshot()[1].failureCount)
        assertEquals(1, index.snapshot()[1].acceptedCount)
        assertEquals(1, index.snapshot()[1].dismissedCount)
        assertEquals(4L, index.snapshot()[1].lastUsedEpochMillis)
    }

    @Test
    fun `feedback index keeps newest duplicate and stable relevance order`() {
        val index = CompletionFeedbackStatsIndex(capacity = 8)

        index.replaceAll(
            listOf(
                feedbackStats(source = "spec", lastUsedEpochMillis = 10, acceptedCount = 1),
                feedbackStats(source = "path", lastUsedEpochMillis = 30, acceptedCount = 1),
                feedbackStats(source = "spec", lastUsedEpochMillis = 20, dismissedCount = 1),
            ),
        )

        assertEquals(listOf("path", "spec"), index.snapshot().map { it.source })
        assertEquals(listOf(30L, 20L), index.snapshot().map { it.lastUsedEpochMillis })
        assertEquals(1, index.snapshot()[1].dismissedCount)
    }

    @Test
    fun `feedback index records counters ignores invalid timestamps and evicts least relevant rows`() {
        val index = CompletionFeedbackStatsIndex(capacity = 2)
        val specContext = feedbackContext(source = "spec")

        index.recordSuggestionFeedback(
            context = feedbackContext(source = "history"),
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 1,
        )
        index.recordSuggestionFeedback(
            context = specContext,
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 2,
        )
        index.recordSuggestionFeedback(
            context = specContext,
            feedback = TerminalCompletionFeedbackKind.DISMISSED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 3,
        )
        index.recordSuggestionFeedback(
            context = feedbackContext(source = "path"),
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 4,
        )
        index.recordSuggestionFeedback(
            context = feedbackContext(source = "ide"),
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = -1,
        )

        assertEquals(listOf("path", "spec"), index.snapshot().map { it.source })
        assertEquals(1, index.snapshot()[1].acceptedCount)
        assertEquals(1, index.snapshot()[1].dismissedCount)
        assertEquals(3L, index.snapshot()[1].lastUsedEpochMillis)
    }

    @Test
    fun `all learning indexes deduplicate canonical directory variants`() {
        val commandIndex = CommandCompletionStatsIndex(capacity = 8)
        commandIndex.replaceAll(
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
        val shape = requireNotNull(classifyGenericCommandLineShape("git status"))
        val shapeIndex = CommandShapeStatsIndex(capacity = 8, commandSpecs = emptyList())
        shapeIndex.replaceAll(
            listOf(
                TerminalCommandShapeStats(
                    shape = shape,
                    profileId = "profile",
                    workingDirectoryUri = "file:///repo/",
                    lastUsedEpochMillis = 1,
                ),
                TerminalCommandShapeStats(
                    shape = shape,
                    profileId = "profile",
                    workingDirectoryUri = "file:///repo",
                    lastUsedEpochMillis = 2,
                ),
            ),
        )
        val feedbackIndex = CompletionFeedbackStatsIndex(capacity = 8)
        feedbackIndex.replaceAll(
            listOf(
                TerminalCompletionFeedbackStats(
                    source = "spec",
                    candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    profileId = "profile",
                    workingDirectoryUri = "file:///repo/",
                    lastUsedEpochMillis = 1,
                ),
                TerminalCompletionFeedbackStats(
                    source = "spec",
                    candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    profileId = "profile",
                    workingDirectoryUri = "file:///repo",
                    lastUsedEpochMillis = 2,
                ),
            ),
        )

        assertEquals(listOf(2L), commandIndex.snapshot().map { it.lastUsedEpochMillis })
        assertEquals(listOf(2L), shapeIndex.snapshot().map { it.lastUsedEpochMillis })
        assertEquals(listOf(2L), feedbackIndex.snapshot().map { it.lastUsedEpochMillis })
        assertEquals(listOf("file:///repo/"), commandIndex.snapshot().map { it.workingDirectoryUri })
        assertEquals(listOf("file:///repo/"), shapeIndex.snapshot().map { it.workingDirectoryUri })
        assertEquals(listOf("file:///repo/"), feedbackIndex.snapshot().map { it.workingDirectoryUri })
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

    private fun shapeStats(
        commandLine: String,
        lastUsedEpochMillis: Long,
    ): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape = requireNotNull(classifyGenericCommandLineShape(commandLine)),
            successCount = 1,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )

    private fun feedbackContext(source: String): TerminalCompletionFeedbackContext =
        TerminalCompletionFeedbackContext(
            source = source,
            candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        )

    private fun feedbackStats(
        source: String,
        lastUsedEpochMillis: Long,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
    ): TerminalCompletionFeedbackStats =
        TerminalCompletionFeedbackStats(
            source = source,
            candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
