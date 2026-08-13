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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalSessionMruCompletionSourceTest {
    @Test
    fun `projects matching recent commands into the active completion range`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("git status")
            source.recordSuccessfulCommand("npm test")

            val candidates = source.complete(request("git s"))

            assertEquals(listOf("status"), candidates.map { it.replacementText })
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.single().kind)
            assertEquals("mru", candidates.single().source)
            assertEquals("recent command", candidates.single().detail)
            assertEquals(4, candidates.single().replacementStartOffset)
            assertEquals(5, candidates.single().replacementEndOffset)
        }

    @Test
    fun `matches prefixes case-insensitively but keeps latest command casing`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("Git Status")

            val candidates = source.complete(request("git s"))

            assertEquals(listOf("Status"), candidates.map { it.replacementText })
        }

    @Test
    fun `learns observed unknown-command tokens without claiming subcommand semantics`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("abc de -g")
            source.recordSuccessfulCommand("abc de -f")
            source.recordSuccessfulCommand("abc as")

            val firstTokens = observedCandidates(source, request("abc "))
            val options = observedCandidates(source, request("abc de "))

            assertEquals(listOf("de", "as"), firstTokens.map { it.replacementText })
            assertTrue(firstTokens.all { it.kind == TerminalCompletionCandidateKind.ARGUMENT })
            assertTrue(firstTokens.all { it.detail == "observed in this session" })
            assertEquals(listOf("-f", "-g"), options.map { it.replacementText }.sorted())
        }

    @Test
    fun `does not learn unknown positional or option values as observed tokens`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("abc de private-value")
            source.recordSuccessfulCommand("abc --config private-value")

            assertTrue(observedCandidates(source, request("abc de ")).isEmpty())
            assertTrue(observedCandidates(source, request("abc --config ")).isEmpty())
        }

    @Test
    fun `does not compete with static command specifications`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("git status")

            assertTrue(observedCandidates(source, request("git ")).isEmpty())
        }

    @Test
    fun `observed tokens prefer matching profile and working directory`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("abc dev", profileId = "bash", workingDirectoryUri = "file:///repo")
            source.recordSuccessfulCommand("abc deploy", profileId = "pwsh", workingDirectoryUri = "file:///other")

            val candidates =
                observedCandidates(
                    source,
                    request("abc d", profileId = "bash", workingDirectoryUri = "file:///repo"),
                )

            assertEquals(listOf("dev", "deploy"), candidates.map { it.replacementText })
        }

    @Test
    fun `observed token capacity evicts the least recent transition`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru(capacity = 2)
            source.recordSuccessfulCommand("abc de")
            source.recordSuccessfulCommand("abc as")
            source.recordSuccessfulCommand("abc be")

            val candidates = observedCandidates(source, request("abc "))

            assertEquals(listOf("be", "as"), candidates.map { it.replacementText })
        }

    @Test
    fun `observing a token again protects it from capacity eviction`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru(capacity = 2)
            source.recordSuccessfulCommand("abc de")
            source.recordSuccessfulCommand("abc as")
            source.recordSuccessfulCommand("abc de")
            source.recordSuccessfulCommand("abc be")

            val candidates = observedCandidates(source, request("abc "))

            assertEquals(listOf("de", "be"), candidates.map { it.replacementText })
        }

    @Test
    fun `does not suggest the exact already typed command`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("git status")

            val candidates = source.complete(request("git status"))

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `does not replace a chained command segment with whole-line history`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("git status")

            val candidates = source.complete(request("echo ready && git s", shellCapabilities = TerminalShellCapabilities.POSIX))

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `duplicate commands are promoted instead of duplicated`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("git status")
            source.recordSuccessfulCommand("git switch main")
            source.recordSuccessfulCommand("git status")

            val candidates = source.complete(request("git s"))

            assertEquals(listOf("status", "switch main"), candidates.map { it.replacementText })
        }

    @Test
    fun `capacity evicts least recent distinct command`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru(capacity = 2)
            source.recordSuccessfulCommand("git status")
            source.recordSuccessfulCommand("git switch main")
            source.recordSuccessfulCommand("git stash")

            val candidates = source.complete(request("git s"))

            assertEquals(listOf("stash", "switch main"), candidates.map { it.replacementText })
        }

    @Test
    fun `using a command again protects it from capacity eviction`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru(capacity = 2)
            source.recordSuccessfulCommand("git status")
            source.recordSuccessfulCommand("git switch main")
            source.recordSuccessfulCommand("git status")
            source.recordSuccessfulCommand("git stash")

            val candidates = source.complete(request("git s"))

            assertEquals(listOf("status", "stash"), candidates.map { it.replacementText })
        }

    @Test
    fun `profile and working-directory matches boost ranking`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand(
                commandLine = "git switch main",
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
            )
            source.recordSuccessfulCommand(
                commandLine = "git status",
                profileId = "pwsh",
                workingDirectoryUri = "file:///other",
            )

            val candidates =
                source.complete(
                    request(
                        commandLine = "git s",
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo",
                    ),
                )

            assertEquals(listOf("switch main", "status"), candidates.map { it.replacementText })
        }

    @Test
    fun `ignores blank and multiline commands`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("   ")
            source.recordSuccessfulCommand("git status\ngit log")

            val candidates = source.complete(request(""))

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `clear removes retained commands`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("abc de -g")

            source.clear()

            assertTrue(source.complete(request("abc ")).isEmpty())
        }

    @Test
    fun `factory creates mutable session mru source`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru(capacity = 1)
            source.recordSuccessfulCommand("git status")

            val candidates = source.complete(request("git"))

            assertEquals(listOf("git status"), candidates.map { it.replacementText })
        }

    @Test
    fun `filters out relative cd command matches when working directory differs`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("cd IdeaProjects/JvTerm/", workingDirectoryUri = "file:///C:/Users/gagik")
            source.recordSuccessfulCommand("cd /usr/bin", workingDirectoryUri = "file:///C:/Users/gagik")
            source.recordSuccessfulCommand("cd ..", workingDirectoryUri = "file:///C:/Users/gagik")
            source.recordSuccessfulCommand("cat relative/file.txt", workingDirectoryUri = "file:///C:/Users/gagik")

            // Request from different directory
            val candidates =
                source.complete(
                    request(
                        commandLine = "c",
                        workingDirectoryUri = "file:///C:/Users/gagik/IdeaProjects/JvTerm",
                    ),
                )

            val replacementTexts = candidates.map { it.replacementText }
            // Should NOT contain "cd IdeaProjects/JvTerm/"
            assertTrue("cd IdeaProjects/JvTerm/" !in replacementTexts)
            // Should contain absolute, traversal, and non-cd relative commands
            assertTrue("cd /usr/bin" in replacementTexts)
            assertTrue("cd .." in replacementTexts)
            assertTrue("cat relative/file.txt" in replacementTexts)
        }

    @Test
    fun `suggests relative cd command matches when working directory matches`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("cd IdeaProjects/JvTerm/", workingDirectoryUri = "file:///C:/Users/gagik")

            // Request from matching directory
            val candidates =
                source.complete(
                    request(
                        commandLine = "cd ",
                        workingDirectoryUri = "file:///C:/Users/gagik/",
                    ),
                )

            assertEquals(listOf("IdeaProjects/JvTerm/"), candidates.map { it.replacementText })
            assertEquals(TerminalCompletionCandidateKind.PATH, candidates.single().kind)
            assertEquals("recent directory", candidates.single().detail)
            assertEquals(3, candidates.single().replacementStartOffset)
            assertEquals(3, candidates.single().replacementEndOffset)
        }

    @Test
    fun `persisted statistics recover one token-local learned fallback`() =
        runBlocking {
            val stats = TerminalCompletionLearningStore()
            stats.recordCommandResult(
                commandLine = "cd IdeaProjects/KetraTerm/",
                successful = true,
                profileId = "pwsh",
                workingDirectoryUri = "file:///C:/Users/gagik",
                usedAtEpochMillis = 1_000,
            )
            val source = TerminalCompletionSources.sessionMru(learningStore = stats)

            val candidates =
                source.complete(
                    request(
                        commandLine = "cd I",
                        profileId = "pwsh",
                        workingDirectoryUri = "file:///C:/Users/gagik",
                    ),
                )

            assertEquals(listOf("IdeaProjects/KetraTerm/"), candidates.map { it.replacementText })
            assertEquals(listOf("mru"), candidates.map { it.source })
            assertEquals(listOf(TerminalCompletionCandidateKind.PATH), candidates.map { it.kind })
            assertEquals(listOf("learned directory"), candidates.map { it.detail })
        }

    @Test
    fun `persisted history applies directory boost across canonical URI variants`() =
        runBlocking {
            val stats = TerminalCompletionLearningStore(commandSpecs = emptyList())
            stats.recordCommandResult(
                commandLine = "tool z-match",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 1_000,
            )
            stats.recordCommandResult(
                commandLine = "tool a-other",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///other",
                usedAtEpochMillis = 1_000,
            )
            val source = TerminalCompletionSources.sessionMru(commandSpecs = emptyList(), learningStore = stats)

            val candidates =
                source.complete(
                    request(
                        commandLine = "tool ",
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo",
                    ),
                )

            assertEquals(listOf("z-match", "a-other"), candidates.map { it.replacementText })
        }

    @Test
    fun `learned completion in the middle preserves following arguments`() =
        runBlocking {
            val source = TerminalCompletionSources.sessionMru()
            source.recordSuccessfulCommand("git switch main")
            val commandLine = "git switch ma --detach"

            val candidates =
                source.complete(
                    TerminalCompletionRequest(
                        commandLine = commandLine,
                        cursorOffset = "git switch ma".length,
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )

            assertEquals(listOf("main"), candidates.map { it.replacementText })
            assertEquals("git switch ".length, candidates.single().replacementStartOffset)
            assertEquals("git switch ma".length, candidates.single().replacementEndOffset)
            assertEquals(
                "git switch main --detach",
                commandLine.replaceRange(
                    candidates.single().replacementStartOffset,
                    candidates.single().replacementEndOffset,
                    candidates.single().replacementText,
                ),
            )
        }

    @Test
    fun `persisted fallback recency uses age rather than absolute epoch`() =
        runBlocking {
            val now = 2_000_000_000_000L
            val snapshot =
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            TerminalCommandCompletionStats(
                                commandLine = "tool a-old",
                                successCount = 1,
                                lastUsedEpochMillis = now - 31L * 24L * 60L * 60L * 1_000L,
                            ),
                            TerminalCommandCompletionStats(
                                commandLine = "tool z-new",
                                successCount = 1,
                                lastUsedEpochMillis = now - 60L * 1_000L,
                            ),
                        ),
                )
            val source =
                SessionMruCompletionSourceImpl(
                    commandSpecs = emptyList(),
                    learningStore = TerminalCompletionLearningStore(commandSpecs = emptyList()).apply { replaceSnapshot(snapshot) },
                    clockEpochMillis = { now },
                )

            val candidates = source.complete(request("tool "))

            assertEquals(listOf("z-new", "a-old"), candidates.map { it.replacementText })
        }

    private fun request(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            shellCapabilities = shellCapabilities,
        )

    private suspend fun observedCandidates(
        source: TerminalSessionMruCompletionSource,
        request: TerminalCompletionRequest,
    ) = source.complete(request).filter { it.source == "observed" }
}
