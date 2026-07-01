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
        assertEquals("git status", TerminalCommandCompletionStats.normalizeCommandLine("  Git Status  "))
    }

    @Test
    fun `rejects blank multiline and blank normalized commands`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCommandCompletionStats(commandLine = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCommandCompletionStats(commandLine = "git status\ngit log")
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCommandCompletionStats(commandLine = "git status", normalizedCommandLine = " ")
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

    private fun stats(
        useCount: Int = 0,
        successCount: Int = 0,
        failureCount: Int = 0,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
        lastUsedEpochMillis: Long = 0,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = "git status",
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
