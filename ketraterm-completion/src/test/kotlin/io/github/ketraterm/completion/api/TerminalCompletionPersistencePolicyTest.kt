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
import kotlin.test.*

class TerminalCompletionPersistencePolicyTest {
    @Test
    fun `allows ordinary command text`() {
        assertTrue(TerminalCompletionPersistencePolicy.allowsCommand("git status"))
    }

    @Test
    fun `rejects blank multiline and ignorespace command text`() {
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("   "))
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("git status\ngit log"))
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("git status\rgit log"))
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand(" git status"))
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("\tgit status"))
    }

    @Test
    fun `rejects common credential-bearing command text case-insensitively`() {
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("docker login --password hunter2"))
        assertFalse(TerminalCompletionPersistencePolicy.allowsCommand("export SECRET_TOKEN=123"))
    }

    @Test
    fun `snapshot sanitization removes private exact commands`() {
        val safe = commandStats("git status")
        val sanitized =
            TerminalCompletionPersistencePolicy.sanitizeSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    listOf(
                        safe,
                        commandStats(" export SECRET_TOKEN=123"),
                        commandStats("docker login --password hunter2"),
                    ),
                ),
            )

        assertEquals(TerminalCommandCompletionStatsSnapshot(listOf(safe)), sanitized)
    }

    @Test
    fun `snapshot sanitization retains an already safe snapshot instance`() {
        val snapshot = TerminalCommandCompletionStatsSnapshot(listOf(commandStats("git status")))

        assertSame(snapshot, TerminalCompletionPersistencePolicy.sanitizeSnapshot(snapshot))
    }

    private fun commandStats(commandLine: String): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            useCount = 1,
            successCount = 1,
            lastUsedEpochMillis = 100,
        )
}
