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
package io.github.ketraterm.completion.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalCommandCompletionStatsTest {
    @Test
    fun `normalizes command line by trimming and lowercasing`() {
        assertEquals("git status", TerminalCommandCompletionStats("  Git Status  ").normalizedCommandLine)
    }

    @Test
    fun `rejects blank and multiline commands`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCommandCompletionStats(commandLine = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCommandCompletionStats(commandLine = "git status\ngit log")
        }
    }

    @Test
    fun `rejects negative counters and timestamps`() {
        assertFailsWith<IllegalArgumentException> {
            stats(useCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            stats(successCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            stats(failureCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            stats(acceptedCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            stats(dismissedCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            stats(lastUsedEpochMillis = -1)
        }
    }

    @Test
    fun `snapshot defensively copies and exposes immutable statistic lists`() {
        val mutableCommands = arrayListOf(stats())
        val snapshot = TerminalCommandCompletionStatsSnapshot(commandStats = mutableCommands)

        mutableCommands += stats(commandLine = "git log")

        assertEquals(listOf("git status"), snapshot.commandStats.map { it.commandLine })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.commandStats as MutableList<TerminalCommandCompletionStats>).add(stats(commandLine = "git diff"))
        }
    }

    private fun stats(
        commandLine: String = "git status",
        useCount: Int = 0,
        successCount: Int = 0,
        failureCount: Int = 0,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
        lastUsedEpochMillis: Long = 0,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
