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
import io.github.ketraterm.completion.model.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TerminalCompletionLearningStoreTest {
    @Test
    fun `complete snapshot identity remains stable until mutation`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            val before = source.snapshot()
            assertSame(before, source.snapshot())

            source.recordCommandResult("git status", true, null, null, 1)

            val after = source.snapshot()
            assertNotSame(before, after)
            assertSame(after, source.snapshot())
        }

    @Test
    fun `learning index compilation occurs outside the mutation monitor`() {
        val source = TerminalCompletionLearningStore(commandSpecs = emptyList())
        source.recordCommandResult("git status", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 1)
        val monitorHeldDuringCompilation = AtomicBoolean(false)

        source.indexesFor(
            shellSyntax = TerminalShellSyntax.POSIX,
            commandSpecs = MonitorProbeCommandSpecs(source, monitorHeldDuringCompilation),
        )

        assertFalse(monitorHeldDuringCompilation.get())
    }

    @Test
    fun `successful command result supplies token-local learned history evidence`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                commandLine = "git status",
                successful = true,
                profileId = "pwsh",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 1_000,
            )

            val candidates =
                learnedHistory(source).complete(request("git s", profileId = "pwsh", workingDirectoryUri = "file:///repo"))

            assertEquals(listOf("status"), candidates.map { it.replacementText })
            assertEquals("mru", candidates.single().source)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.single().kind)
            assertEquals(4, candidates.single().replacementStartOffset)
            assertEquals(5, candidates.single().replacementEndOffset)
        }

    @Test
    fun `records compact success and failure counts for one normalized command`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            source.recordCommandResult(
                "Git Status",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = null,
                usedAtEpochMillis = 10,
            )
            source.recordCommandResult(
                "git status",
                successful = false,
                profileId = "bash",
                workingDirectoryUri = null,
                usedAtEpochMillis = 20,
            )

            assertEquals(
                listOf(
                    TerminalCommandCompletionStats(
                        commandLine = "git status",
                        profileId = "bash",
                        workingDirectoryUri = null,
                        useCount = 2,
                        successCount = 1,
                        failureCount = 1,
                        acceptedCount = 0,
                        dismissedCount = 0,
                        lastUsedEpochMillis = 20,
                    ),
                ),
                source.snapshot().commandStats,
            )
        }

    @Test
    fun `accepted feedback boosts candidate above dismissed candidate`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordCommandResult(
                "git switch main",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            repeat(4) {
                source.recordSuggestionFeedback(
                    commandLine = "git switch main",
                    feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                    profileId = null,
                    workingDirectoryUri = null,
                    feedbackAtEpochMillis = 200 + it.toLong(),
                )
            }
            repeat(4) {
                source.recordSuggestionFeedback(
                    commandLine = "git status",
                    feedback = TerminalCompletionFeedbackKind.DISMISSED,
                    profileId = null,
                    workingDirectoryUri = null,
                    feedbackAtEpochMillis = 200 + it.toLong(),
                )
            }

            val candidates = learnedHistory(source).complete(request("git s"))

            assertEquals(listOf("switch main", "status"), candidates.map { it.replacementText })
            assertTrue(candidates[0].score > candidates[1].score)
        }

    @Test
    fun `exact command ranking caps learned counter contribution`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            TerminalCommandCompletionStats(
                                commandLine = "git alpha",
                                useCount = 50,
                                successCount = 50,
                                acceptedCount = 50,
                                lastUsedEpochMillis = 60_000,
                            ),
                            TerminalCommandCompletionStats(
                                commandLine = "git beta",
                                useCount = 500,
                                successCount = 500,
                                acceptedCount = 500,
                                lastUsedEpochMillis = 60_000,
                            ),
                        ),
                ),
            )

            val candidates = learnedHistory(source).complete(request("git "))

            assertEquals(listOf("alpha", "beta"), candidates.map { it.replacementText })
            assertEquals(candidates[0].score, candidates[1].score)
        }

    @Test
    fun `profile and working directory matches affect ranking`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "npm test",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo-a",
                usedAtEpochMillis = 100,
            )
            source.recordCommandResult(
                "npm update",
                successful = true,
                profileId = "pwsh",
                workingDirectoryUri = "file:///repo-b",
                usedAtEpochMillis = 100,
            )

            val candidates =
                learnedHistory(source).complete(
                    request("npm ", profileId = "pwsh", workingDirectoryUri = "file:///repo-b"),
                )

            assertEquals(listOf("update", "test"), candidates.map { it.replacementText })
            assertTrue(candidates[0].score > candidates[1].score)
        }

    @Test
    fun `exact command prefix is not suggested`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            assertTrue(learnedHistory(source).complete(request("git status")).isEmpty())
        }

    @Test
    fun `does not replace a chained command segment with whole-line history`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            val candidates =
                learnedHistory(source).complete(
                    request(
                        "echo ready && git s",
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `failure only and dismissed only rows are tracked but not suggested`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = false,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordSuggestionFeedback(
                commandLine = "git switch main",
                feedback = TerminalCompletionFeedbackKind.DISMISSED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 200,
            )

            assertEquals(listOf("git switch main", "git status"), source.snapshot().commandStats.map { it.commandLine })
            assertTrue(learnedHistory(source).complete(request("git s")).isEmpty())
        }

    @Test
    fun `blank multiline and negative timestamp events are ignored`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            val before = source.snapshot()

            source.recordCommandResult(
                "   ",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordCommandResult(
                "git status\ngit log",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = -1,
            )
            source.mergeSnapshot(TerminalCommandCompletionStatsSnapshot.EMPTY)

            assertTrue(source.snapshot().commandStats.isEmpty())
            assertSame(before, source.snapshot())
        }

    @Test
    fun `capacity keeps most relevant recent records`() =
        runBlocking {
            val source = TerminalCompletionLearningStore(capacity = 2)

            source.recordCommandResult(
                "one",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )
            source.recordCommandResult(
                "two",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 2,
            )
            source.recordCommandResult(
                "three",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 3,
            )

            assertEquals(listOf("three", "two"), source.snapshot().commandStats.map { it.commandLine })
        }

    @Test
    fun `replace snapshot deduplicates by normalized command profile and directory`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            source.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            stats(
                                "Git Status",
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 10,
                            ),
                            stats(
                                "git status",
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 20,
                            ),
                            stats(
                                "git status",
                                profileId = "pwsh",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 5,
                            ),
                        ),
                ),
            )

            assertEquals(
                listOf(
                    stats("git status", profileId = "bash", workingDirectoryUri = "file:///repo/", lastUsedEpochMillis = 20),
                    stats("git status", profileId = "pwsh", workingDirectoryUri = "file:///repo/", lastUsedEpochMillis = 5),
                ),
                source.snapshot().commandStats,
            )
        }

    @Test
    fun `constructor creates bounded command stats store`() =
        runBlocking {
            val source = TerminalCompletionLearningStore(capacity = 1)

            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )

            assertEquals(listOf("git status"), source.snapshot().commandStats.map { it.commandLine })
        }

    @Test
    fun `custom command specs classify nested command shapes`() =
        runBlocking {
            val source =
                TerminalCompletionLearningStore(
                    commandSpecs =
                        listOf(
                            TerminalCommandSpec(
                                name = "tool",
                                subcommands =
                                    listOf(
                                        TerminalCommandSpec(
                                            name = "alpha",
                                            subcommands = listOf(TerminalCommandSpec("beta", "run beta workflow")),
                                        ),
                                    ),
                            ),
                        ),
                )

            source.recordCommandResult(
                commandLine = "tool alpha beta private-branch",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )

            val shape =
                source
                    .snapshot()
                    .shapeStats
                    .single()
                    .shape
            assertEquals("tool", shape.executable)
            assertEquals(listOf("alpha", "beta"), shape.subcommands)
            assertEquals(1, shape.positionalArgumentCount)
            assertTrue("private-branch" !in shape.normalizedShapeKey)
        }

    @Test
    fun `custom command specs canonicalize aliased subcommands`() =
        runBlocking {
            val source =
                TerminalCompletionLearningStore(
                    commandSpecs =
                        listOf(
                            TerminalCommandSpec(
                                name = "git",
                                subcommands = listOf(TerminalCommandSpec("checkout", aliases = listOf("co"))),
                            ),
                        ),
                )

            source.recordCommandResult(
                commandLine = "git co main",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )

            val shape =
                source
                    .snapshot()
                    .shapeStats
                    .single()
                    .shape
            assertEquals(listOf("checkout"), shape.subcommands)
            assertEquals(1, shape.positionalArgumentCount)
            assertTrue("main" !in shape.normalizedShapeKey)
        }

    @Test
    fun `empty command specs fall back to generic private shape classification`() =
        runBlocking {
            val source = TerminalCompletionLearningStore(commandSpecs = emptyList())

            source.recordCommandResult(
                commandLine = "docker compose up secret-project",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )

            val shape =
                source
                    .snapshot()
                    .shapeStats
                    .single()
                    .shape
            assertEquals("docker", shape.executable)
            assertEquals(listOf("compose"), shape.subcommands)
            assertEquals(2, shape.positionalArgumentCount)
            assertTrue("secret-project" !in shape.normalizedShapeKey)
        }

    @Test
    fun `records source-specific feedback context without command text`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                feedbackAtEpochMillis = 100,
                context =
                    TerminalCompletionFeedbackContext(
                        source = "spec",
                        candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    ),
            )
            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.DISMISSED,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                feedbackAtEpochMillis = 200,
                context =
                    TerminalCompletionFeedbackContext(
                        source = "spec",
                        candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    ),
            )

            assertEquals(
                listOf(
                    TerminalCompletionFeedbackStats(
                        source = "spec",
                        candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo/",
                        acceptedCount = 1,
                        dismissedCount = 1,
                        lastUsedEpochMillis = 200,
                    ),
                ),
                source.snapshot().feedbackStats,
            )
            assertTrue(
                source
                    .snapshot()
                    .feedbackStats
                    .single()
                    .source != "git status",
            )
        }

    @Test
    fun `replace snapshot keeps newest duplicate feedback context`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            source.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    feedbackStats =
                        listOf(
                            feedbackStats(lastUsedEpochMillis = 10, acceptedCount = 1),
                            feedbackStats(lastUsedEpochMillis = 20, dismissedCount = 1),
                            feedbackStats(source = "stats", lastUsedEpochMillis = 5, acceptedCount = 1),
                        ),
                ),
            )

            assertEquals(listOf(20L, 5L), source.snapshot().feedbackStats.map { it.lastUsedEpochMillis })
            assertEquals(listOf("spec", "stats"), source.snapshot().feedbackStats.map { it.source })
        }

    @Test
    fun `recorded counters saturate at integer maximum`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.replaceSnapshot(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats =
                        listOf(
                            TerminalCommandCompletionStats(
                                commandLine = "git status",
                                useCount = Int.MAX_VALUE,
                                successCount = Int.MAX_VALUE,
                                acceptedCount = Int.MAX_VALUE,
                                lastUsedEpochMillis = 10,
                            ),
                        ),
                ),
            )

            source.recordCommandResult(
                commandLine = "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 20,
            )
            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 30,
            )

            val stats = source.snapshot().commandStats.single()
            assertEquals(Int.MAX_VALUE, stats.useCount)
            assertEquals(Int.MAX_VALUE, stats.successCount)
            assertEquals(Int.MAX_VALUE, stats.acceptedCount)
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

    private fun learnedHistory(source: TerminalCompletionLearningStore) = TerminalCompletionSources.sessionMru(learningStore = source)

    private fun stats(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        lastUsedEpochMillis: Long,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = 0,
            successCount = 0,
            failureCount = 0,
            acceptedCount = 0,
            dismissedCount = 0,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )

    private class MonitorProbeCommandSpecs(
        private val source: TerminalCompletionLearningStore,
        private val monitorHeldDuringHash: AtomicBoolean,
    ) : AbstractList<TerminalCommandSpec>() {
        override val size: Int = 0

        override fun get(index: Int): TerminalCommandSpec = throw IndexOutOfBoundsException(index)

        override fun hashCode(): Int {
            if (source.isMutationMonitorHeldByCurrentThread()) monitorHeldDuringHash.set(true)
            return super.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as MonitorProbeCommandSpecs

            if (size != other.size) return false
            if (source != other.source) return false
            if (monitorHeldDuringHash != other.monitorHeldDuringHash) return false

            return true
        }
    }

    private fun feedbackStats(
        source: String = "spec",
        lastUsedEpochMillis: Long,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
    ): TerminalCompletionFeedbackStats =
        TerminalCompletionFeedbackStats(
            source = source,
            candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
