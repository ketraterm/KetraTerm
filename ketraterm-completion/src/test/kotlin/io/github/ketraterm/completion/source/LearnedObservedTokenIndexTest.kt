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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.resolveCompletionContext
import io.github.ketraterm.completion.internal.CompletionLearningIndexCache
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnedObservedTokenIndexTest {
    @Test
    fun `derives first arguments and options without retaining later values`() {
        val index =
            index(
                positive("abc de -g"),
                positive("abc de -f"),
                positive("abc de private-value"),
                positive("abc --config private-value"),
            )

        val firstArguments = candidates(index, "abc d")
        val options = candidates(index, "abc de ")
        val optionValues = candidates(index, "abc --config ")

        assertEquals(listOf("de"), firstArguments.map { it.replacementText })
        assertEquals(setOf("-f", "-g"), options.map { it.replacementText }.toSet())
        assertTrue(optionValues.isEmpty())
        assertTrue(firstArguments.all { it.kind == TerminalCompletionCandidateKind.ARGUMENT })
        assertTrue(options.all { it.kind == TerminalCompletionCandidateKind.OPTION })
        assertTrue((firstArguments + options).all { it.source == "observed" })
        assertTrue((firstArguments + options).all { it.detail == "learned from successful commands" })
    }

    @Test
    fun `uses the requested shell syntax when compiling observed transitions`() {
        val snapshot = snapshot(positive("abc hello\\ world"))
        val cache = CompletionLearningIndexCache()

        val posix = candidates(cache.indexesFor(snapshot, TerminalShellSyntax.POSIX).observed, "abc h", TerminalShellSyntax.POSIX)
        val powershell =
            candidates(
                cache.indexesFor(snapshot, TerminalShellSyntax.POWERSHELL).observed,
                "abc h",
                TerminalShellSyntax.POWERSHELL,
            )

        assertEquals(listOf("hello\\ world"), posix.map { it.replacementText })
        assertEquals(listOf("hello world"), posix.map { it.displayText })
        assertEquals(listOf("hello\\"), powershell.map { it.replacementText })

        val quoted = candidates(cache.indexesFor(snapshot, TerminalShellSyntax.POSIX).observed, "abc 'h", TerminalShellSyntax.POSIX)
        assertEquals(listOf("'hello world'"), quoted.map { it.replacementText })
    }

    @Test
    fun `suppresses known command families at query time without changing the cache key`() {
        val index = index(positive("git status"))

        assertEquals(listOf("status"), candidates(index, "git s").map { it.replacementText })
        assertTrue(candidates(index, "git s", commandSpecs = listOf(TerminalCommandSpec(name = "git"))).isEmpty())
    }

    @Test
    fun `uses successful execution rows but not failure or feedback-only rows`() {
        val index =
            index(
                positive("abc success"),
                TerminalCommandCompletionStats(commandLine = "abc failed", useCount = 1, failureCount = 1),
                TerminalCommandCompletionStats(commandLine = "abc accepted", acceptedCount = 1),
            )

        assertEquals(listOf("success"), candidates(index, "abc ").map { it.replacementText })
    }

    @Test
    fun `preserves case-distinct observed arguments`() {
        val index = index(positive("abc Foo"), positive("abc foo"))

        assertEquals(setOf("Foo", "foo"), candidates(index, "abc f").mapTo(mutableSetOf()) { it.replacementText })
    }

    @Test
    fun `does not derive transitions from a chained command segment`() {
        val index = index(positive("echo ready && abc private-value"))

        assertTrue(candidates(index, "abc p").isEmpty())
    }

    @Test
    fun `aggregates duplicate transitions and retains their best matching host context`() {
        val index =
            index(
                positive(
                    commandLine = "abc deploy --fast",
                    successCount = 2,
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                ),
                positive(
                    commandLine = "abc deploy --force",
                    successCount = 3,
                    profileId = "pwsh",
                    workingDirectoryUri = "file:///other",
                ),
                positive(
                    commandLine = "abc debug",
                    successCount = 5,
                ),
            )

        val matching = candidates(index, "abc d", profileId = "bash", workingDirectoryUri = "file:///repo")
        val neutral = candidates(index, "abc d")

        assertEquals(1, matching.count { it.replacementText == "deploy" })
        assertTrue(matching.single { it.replacementText == "deploy" }.score > matching.single { it.replacementText == "debug" }.score)
        assertEquals(
            neutral.single { it.replacementText == "debug" }.score,
            neutral.single { it.replacementText == "deploy" }.score,
        )
    }

    @Test
    fun `retains the strongest bounded set deterministically`() {
        val rows =
            List(2_050) { index ->
                positive(
                    commandLine = "abc token${index.toString().padStart(4, '0')}",
                    successCount = index + 1,
                )
            }
        val index = index(*rows.toTypedArray())

        val replacements = candidates(index, "abc ").mapTo(HashSet(), TerminalCompletionCandidate::replacementText)

        assertEquals(2_048, replacements.size)
        assertFalse("token0000" in replacements)
        assertFalse("token0001" in replacements)
        assertTrue("token0002" in replacements)
        assertTrue("token2049" in replacements)
    }

    private fun index(vararg rows: TerminalCommandCompletionStats): LearnedObservedTokenIndex =
        CompletionLearningIndexCache()
            .indexesFor(snapshot(*rows), TerminalShellSyntax.POSIX)
            .observed

    private fun snapshot(vararg rows: TerminalCommandCompletionStats): TerminalCommandCompletionStatsSnapshot =
        TerminalCommandCompletionStatsSnapshot(rows.toList())

    private fun candidates(
        index: LearnedObservedTokenIndex,
        commandLine: String,
        shellSyntax: TerminalShellSyntax = TerminalShellSyntax.POSIX,
        commandSpecs: List<TerminalCommandSpec> = emptyList(),
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    ): List<TerminalCompletionCandidate> {
        val request =
            TerminalCompletionRequest(
                commandLine = commandLine,
                cursorOffset = commandLine.length,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
                shellCapabilities =
                    when (shellSyntax) {
                        TerminalShellSyntax.PLAIN -> TerminalShellCapabilities.PLAIN
                        TerminalShellSyntax.POSIX -> TerminalShellCapabilities.POSIX
                        TerminalShellSyntax.POWERSHELL -> TerminalShellCapabilities.POWERSHELL
                    },
            )
        val context = request.resolveCompletionContext(commandSpecs)
        return buildList {
            index.appendCandidates(
                request = request,
                context = context,
                destination = this,
            )
        }
    }

    private fun positive(
        commandLine: String,
        successCount: Int = 1,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = successCount,
            successCount = successCount,
        )
}
