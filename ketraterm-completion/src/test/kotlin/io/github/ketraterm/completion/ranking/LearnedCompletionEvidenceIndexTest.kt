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
package io.github.ketraterm.completion.ranking

import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LearnedCompletionEvidenceIndexTest {
    @Test
    fun `textual path variants merge counters and retain newest timestamp`() {
        val resolver = TerminalCompletionOutcomeKeyResolver(TerminalCommandSpecs.defaults())
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        stats("cd build", lastUsedEpochMillis = NOW - FORTY_DAYS),
                        stats("cd build/", lastUsedEpochMillis = NOW),
                    ),
            )
        val index = LearnedCompletionEvidenceIndex.build(snapshot, TerminalShellSyntax.POSIX, resolver)
        val tokens = TerminalCommandLineTokenizer.parse("cd build", "cd build".length, TerminalShellSyntax.POSIX).tokens
        val key = requireNotNull(resolver.learnedKey(tokens, 1, pathAware = true))

        assertEquals(192, index.exactAdjustment(key, request(), NOW))
    }

    @Test
    fun `snapshot shares one compiled learning view per syntax and command specs`() {
        val specs = TerminalCommandSpecs.defaults()
        val snapshot = TerminalCommandCompletionStatsSnapshot.EMPTY

        val firstPosix = snapshot.compiledLearning.indexesFor(TerminalShellSyntax.POSIX, specs)

        assertSame(firstPosix, snapshot.compiledLearning.indexesFor(TerminalShellSyntax.POSIX, specs))
        assertNotSame(firstPosix, snapshot.compiledLearning.indexesFor(TerminalShellSyntax.POWERSHELL, specs))
        assertNotSame(
            firstPosix,
            TerminalCommandCompletionStatsSnapshot().compiledLearning.indexesFor(TerminalShellSyntax.POSIX, specs),
        )
    }

    private fun stats(
        commandLine: String,
        lastUsedEpochMillis: Long,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            profileId = "profile",
            workingDirectoryUri = "file:///repo",
            useCount = 12,
            successCount = 5,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )

    private fun request(): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = "cd ",
            cursorOffset = 3,
            profileId = "profile",
            workingDirectoryUri = "file:///repo/",
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )

    private companion object {
        private const val NOW = 2_000_000_000_000L
        private const val FORTY_DAYS = 40L * 24L * 60L * 60L * 1_000L
    }
}
