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
package io.github.ketraterm.app.history

import io.github.ketraterm.completion.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandCompletionStatsSanitizerTest {
    @Test
    fun `removes exact command stats with ignorespace or sensitive command text`() {
        val safe = commandStats("git status")
        val privateByWhitespace = commandStats(" export SECRET_TOKEN=123")
        val privateByKeyword = commandStats("docker login --password hunter2")

        val sanitized =
            CommandCompletionStatsSanitizer.sanitize(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = listOf(safe, privateByWhitespace, privateByKeyword),
                ),
            )

        assertEquals(listOf(safe), sanitized.commandStats)
    }

    @Test
    fun `removes shape stats with sensitive public shape vocabulary`() {
        val safe = shapeStats("git status")
        val sensitiveExecutable = shapeStats("secret-tool list")
        val sensitiveOption = shapeStats("curl --authorization bearer")

        val sanitized =
            CommandCompletionStatsSanitizer.sanitize(
                TerminalCommandCompletionStatsSnapshot(
                    shapeStats = listOf(safe, sensitiveExecutable, sensitiveOption),
                ),
            )

        assertEquals(listOf(safe), sanitized.shapeStats)
    }

    @Test
    fun `keeps feedback stats because they do not contain raw command arguments`() {
        val feedback =
            TerminalCompletionFeedbackStats(
                source = "spec",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                tokenPosition = TerminalCompletionTokenPosition.SUBCOMMAND,
                replacementStartOffset = 4,
                replacementEndOffset = 10,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                acceptedCount = 2,
                dismissedCount = 1,
                lastUsedEpochMillis = 900,
            )

        val sanitized =
            CommandCompletionStatsSanitizer.sanitize(
                TerminalCommandCompletionStatsSnapshot(feedbackStats = listOf(feedback)),
            )

        assertEquals(listOf(feedback), sanitized.feedbackStats)
    }

    @Test
    fun `sanitizes command and shape rows without mutating safe feedback rows`() {
        val safeCommand = commandStats("git status")
        val privateCommand = commandStats("docker login --password hunter2")
        val safeShape = shapeStats("git status")
        val privateShape = shapeStats("curl --authorization bearer")
        val feedback =
            TerminalCompletionFeedbackStats(
                source = "history",
                candidateKind = TerminalCompletionCandidateKind.HISTORY,
                acceptedCount = 1,
                lastUsedEpochMillis = 100,
            )

        val sanitized =
            CommandCompletionStatsSanitizer.sanitize(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = listOf(safeCommand, privateCommand),
                    shapeStats = listOf(safeShape, privateShape),
                    feedbackStats = listOf(feedback),
                ),
            )

        assertEquals(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(safeCommand),
                shapeStats = listOf(safeShape),
                feedbackStats = listOf(feedback),
            ),
            sanitized,
        )
    }

    private fun commandStats(commandLine: String): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            normalizedCommandLine = TerminalCommandCompletionStats.normalizeCommandLine(commandLine),
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )

    private fun shapeStats(commandLine: String): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape = TerminalCommandLineShape.fromCommandLine(commandLine)!!,
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )
}
