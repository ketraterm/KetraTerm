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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.CompletionLearningIndexCache
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LearnedHistoryCandidateIndexTest {
    @Test
    fun `lookup selects prior-token context and active prefix without scanning unrelated commands`() =
        runBlocking {
            val snapshot =
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            positive("git switch main"),
                            positive("git switch maintenance"),
                            positive("git checkout main"),
                            positive("gradle assemble"),
                            TerminalCommandCompletionStats(commandLine = "git switch malformed"),
                        ),
                )
            val index = CompletionLearningIndexCache().indexesFor(snapshot, TerminalShellSyntax.POSIX).history
            val requestLine = TerminalCommandLineTokenizer.parse("git switch ma", "git switch ma".length, TerminalShellSyntax.POSIX)

            val matches = index.matching(requestLine)

            assertEquals(listOf("git switch main", "git switch maintenance"), matches.map { it.stats.commandLine })
        }

    private fun positive(commandLine: String): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(commandLine = commandLine, successCount = 1)
}
