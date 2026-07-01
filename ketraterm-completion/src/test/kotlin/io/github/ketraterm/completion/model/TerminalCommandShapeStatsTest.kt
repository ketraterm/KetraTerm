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

import io.github.ketraterm.completion.source.TerminalCommandStatsCompletionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TerminalCommandShapeStatsTest {
    @Test
    fun `shape extracts executable subcommand options and private argument counts`() {
        val shape = TerminalCommandLineShape.fromCommandLine("git log --stat --author Alice main")!!

        assertEquals("git", shape.executable)
        assertEquals(listOf("log"), shape.subcommands)
        assertEquals(listOf("--author", "--stat"), shape.optionNames)
        assertEquals(1, shape.optionValueCount)
        assertEquals(1, shape.positionalArgumentCount)
        assertFalse(shape.normalizedShapeKey.contains("Alice", ignoreCase = true))
        assertFalse(shape.normalizedShapeKey.contains("main", ignoreCase = true))
    }

    @Test
    fun `shape skips environment assignments before executable`() {
        val shape = TerminalCommandLineShape.fromCommandLine("NODE_ENV=test npm run build -- --watch")!!

        assertEquals("npm", shape.executable)
        assertEquals(listOf("run"), shape.subcommands)
        assertEquals(emptyList(), shape.optionNames)
        assertEquals(2, shape.positionalArgumentCount)
        assertFalse(shape.normalizedShapeKey.contains("build", ignoreCase = true))
        assertFalse(shape.normalizedShapeKey.contains("watch", ignoreCase = true))
    }

    @Test
    fun `shape rejects blank multiline and assignment only commands`() {
        assertNull(TerminalCommandLineShape.fromCommandLine("   "))
        assertNull(TerminalCommandLineShape.fromCommandLine("git status\ngit log"))
        assertNull(TerminalCommandLineShape.fromCommandLine("FOO=bar"))
    }

    @Test
    fun `source records command results into matching shape stats`() {
        val source = TerminalCommandStatsCompletionSource()

        source.recordCommandResult(
            commandLine = "git log --stat main",
            successful = true,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 100,
        )
        source.recordCommandResult(
            commandLine = "git log --stat feature",
            successful = false,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 200,
        )

        val stats = source.shapeSnapshot().single()
        assertEquals("git", stats.shape.executable)
        assertEquals(listOf("log"), stats.shape.subcommands)
        assertEquals(listOf("--stat"), stats.shape.optionNames)
        assertEquals(1, stats.shape.positionalArgumentCount)
        assertEquals(2, stats.useCount)
        assertEquals(1, stats.successCount)
        assertEquals(1, stats.failureCount)
        assertEquals(200, stats.lastUsedEpochMillis)
    }

    @Test
    fun `source records spec aware nested command family shapes`() {
        val source = TerminalCommandStatsCompletionSource()

        source.recordCommandResult(
            commandLine = "docker compose up",
            successful = true,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 100,
        )

        val stats = source.shapeSnapshot().single()
        assertEquals("docker", stats.shape.executable)
        assertEquals(listOf("compose", "up"), stats.shape.subcommands)
        assertEquals(0, stats.shape.positionalArgumentCount)
    }

    @Test
    fun `source records accepted and dismissed feedback into shape stats`() {
        val source = TerminalCommandStatsCompletionSource()

        source.recordSuggestionFeedback(
            commandLine = "npm test -- --watch",
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            feedbackAtEpochMillis = 100,
        )
        source.recordSuggestionFeedback(
            commandLine = "npm test -- --watch",
            feedback = TerminalCompletionFeedbackKind.DISMISSED,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            feedbackAtEpochMillis = 200,
        )

        val stats = source.shapeSnapshot().single()
        assertEquals(1, stats.acceptedCount)
        assertEquals(1, stats.dismissedCount)
        assertEquals(200, stats.lastUsedEpochMillis)
    }

    @Test
    fun `replace shape stats deduplicates by shape profile and directory`() {
        val source = TerminalCommandStatsCompletionSource()
        val shape = TerminalCommandLineShape.fromCommandLine("git status")!!

        source.replaceShapeStats(
            listOf(
                TerminalCommandShapeStats(
                    shape = shape,
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    lastUsedEpochMillis = 10,
                ),
                TerminalCommandShapeStats(
                    shape = shape,
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    lastUsedEpochMillis = 20,
                ),
                TerminalCommandShapeStats(shape = shape, profileId = "pwsh", workingDirectoryUri = "file:///repo", lastUsedEpochMillis = 5),
            ),
        )

        assertEquals(listOf(20L, 5L), source.shapeSnapshot().map { it.lastUsedEpochMillis })
    }
}
