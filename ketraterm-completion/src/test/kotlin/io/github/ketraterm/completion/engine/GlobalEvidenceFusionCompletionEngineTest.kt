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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalEvidenceFusionCompletionEngineTest {
    @Test
    fun `mru and path outcomes fuse across quoting and trailing separator`() {
        val engine =
            engine(
                sources =
                    listOf(
                        entry(source(candidate("cd build", 0, 3, "mru", TerminalCompletionCandidateKind.HISTORY)), 8),
                        entry(source(candidate("\"build/\"", 3, 3, "path", TerminalCompletionCandidateKind.PATH)), 12),
                    ),
            )

        val candidates = engine.complete(request("cd "))

        assertEquals(1, candidates.size)
        assertEquals("\"build/\"", candidates.single().replacementText)
        assertEquals("path", candidates.single().source)
    }

    @Test
    fun `same provider contributes only its best local rank to one outcome`() {
        val duplicateSource =
            source(
                candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 10),
            )
        val duplicateScore = engine(listOf(entry(duplicateSource, 12))).complete(request("cd ")).single().score
        val singleScore =
            engine(
                listOf(entry(source(candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100)), 12)),
            ).complete(request("cd ")).single().score

        assertEquals(singleScore, duplicateScore)
    }

    @Test
    fun `accepted exact outcome promotes matching path candidate`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "cd build",
                            profileId = "profile",
                            workingDirectoryUri = "file:///repo",
                            acceptedCount = 8,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val engine =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate("cache/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                                candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 1),
                            ),
                            12,
                        ),
                    ),
                snapshot = snapshot,
            )

        val candidates = engine.complete(request("cd ", workingDirectoryUri = "file:///repo"))

        assertEquals("build/", candidates.first().replacementText)
    }

    @Test
    fun `successful directory history promotes the equivalent path outcome`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "cd build",
                            workingDirectoryUri = "file:///repo",
                            useCount = 12,
                            successCount = 12,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val candidates =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate("cache/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                                candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 1),
                            ),
                            12,
                        ),
                    ),
                snapshot = snapshot,
            ).complete(request("cd "))

        assertEquals("build/", candidates.first().replacementText)
    }

    @Test
    fun `Gradle task supported by history and statistics outranks an unused sibling`() {
        val commandLine = "./gradlew :app:"
        val task =
            candidate(
                ":app:test",
                10,
                commandLine.length,
                "gradle-tasks",
                TerminalCompletionCandidateKind.SUBCOMMAND,
            )
        val sibling = task.copy(replacementText = ":app:check", displayText = ":app:check", score = 100)
        val remembered =
            candidate(
                "./gradlew :app:test",
                0,
                commandLine.length,
                "mru",
                TerminalCompletionCandidateKind.HISTORY,
            )
        val statistical = remembered.copy(source = "command-stats")
        val candidates =
            engine(
                listOf(
                    entry(source(sibling, task), 15),
                    entry(source(remembered), 8),
                    entry(source(statistical), 6),
                ),
            ).complete(request(commandLine))

        assertEquals("./gradlew :app:test", candidates.first().let { commandLineAfter(it, commandLine) })
        assertEquals(2, candidates.size)
    }

    @Test
    fun `relative path learning does not cross working directories`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "cd build",
                            profileId = "profile",
                            workingDirectoryUri = "file:///other",
                            acceptedCount = 100,
                            successCount = 100,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val engine =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate("cache/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                                candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 1),
                            ),
                            12,
                        ),
                    ),
                snapshot = snapshot,
            )

        val candidates = engine.complete(request("cd ", workingDirectoryUri = "file:///repo"))

        assertEquals("cache/", candidates.first().replacementText)
    }

    @Test
    fun `explicit dismissal lowers one exact outcome`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "git switch main",
                            dismissedCount = 12,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val engine =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate(
                                    "main",
                                    11,
                                    13,
                                    "branches",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                    score = 100,
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                candidate(
                                    "maint",
                                    11,
                                    13,
                                    "branches",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                    score = 1,
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                            15,
                        ),
                    ),
                snapshot = snapshot,
            )

        val candidates = engine.complete(request("git switch ma"))

        assertEquals("maint", candidates.first().replacementText)
    }

    @Test
    fun `successful executions help and failed executions hurt`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "git switch main",
                            useCount = 10,
                            successCount = 10,
                            lastUsedEpochMillis = NOW,
                        ),
                        TerminalCommandCompletionStats(
                            commandLine = "git switch maint",
                            useCount = 10,
                            failureCount = 10,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val candidates =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                branchCandidate("maint", score = 100),
                                branchCandidate("main", score = 1),
                            ),
                            15,
                        ),
                    ),
                snapshot = snapshot,
            ).complete(request("git switch ma"))

        assertEquals("main", candidates.first().replacementText)
    }

    @Test
    fun `one learned event cannot overwhelm a stronger semantic context`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "cd remembered",
                            acceptedCount = 1,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val candidates =
            engine(
                sources =
                    listOf(
                        entry(
                            source(candidate("cd remembered", 0, 3, "mru", TerminalCompletionCandidateKind.HISTORY)),
                            8,
                        ),
                        entry(source(candidate("src/", 3, 3, "path", TerminalCompletionCandidateKind.PATH)), 12),
                    ),
                snapshot = snapshot,
            ).complete(request("cd "))

        assertEquals("src/", candidates.first().replacementText)
    }

    @Test
    fun `exact outcome evidence outweighs lower-weight command shape evidence`() {
        val statusShape =
            TerminalCommandLineShape(
                executable = "git",
                subcommands = listOf("status"),
            )
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(TerminalCommandCompletionStats(commandLine = "git switch main", acceptedCount = 1)),
                shapeStats = listOf(TerminalCommandShapeStats(shape = statusShape, acceptedCount = 1)),
            )
        val candidates =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate("git status", 0, 1, "history", TerminalCompletionCandidateKind.HISTORY, score = 100),
                                candidate("git switch main", 0, 1, "history", TerminalCompletionCandidateKind.HISTORY, score = 1),
                            ),
                            0,
                        ),
                    ),
                snapshot = snapshot,
            ).complete(request("g"))

        assertEquals("git switch main", candidates.first().replacementText)
    }

    @Test
    fun `provider feedback is source and context specific`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                feedbackStats =
                    listOf(
                        TerminalCompletionFeedbackStats(
                            source = "branches",
                            candidateKind = TerminalCompletionCandidateKind.ARGUMENT,
                            tokenPosition = TerminalCompletionTokenPosition.ARGUMENT,
                            profileId = "profile",
                            acceptedCount = 20,
                        ),
                        TerminalCompletionFeedbackStats(
                            source = "other",
                            candidateKind = TerminalCompletionCandidateKind.ARGUMENT,
                            tokenPosition = TerminalCompletionTokenPosition.ARGUMENT,
                            profileId = "profile",
                            dismissedCount = 20,
                        ),
                    ),
            )
        val engine =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate(
                                    "main",
                                    11,
                                    13,
                                    "branches",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                            0,
                        ),
                        entry(
                            source(
                                candidate(
                                    "maint",
                                    11,
                                    13,
                                    "other",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                            0,
                        ),
                    ),
                snapshot = snapshot,
            )

        assertEquals("main", engine.complete(request("git switch ma")).first().replacementText)
    }

    @Test
    fun `recent evidence receives a larger boost than old evidence`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(commandLine = "git switch main", useCount = 1, lastUsedEpochMillis = NOW),
                        TerminalCommandCompletionStats(
                            commandLine = "git switch maint",
                            useCount = 1,
                            lastUsedEpochMillis =
                                NOW - FORTY_DAYS,
                        ),
                    ),
            )
        val engine =
            engine(
                sources =
                    listOf(
                        entry(
                            source(
                                candidate(
                                    "maint",
                                    11,
                                    13,
                                    "branches",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                    score = 100,
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                candidate(
                                    "main",
                                    11,
                                    13,
                                    "branches",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                    score = 1,
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                            0,
                        ),
                    ),
                snapshot = snapshot,
            )

        assertEquals("main", engine.complete(request("git switch ma")).first().replacementText)
    }

    @Test
    fun `case sensitive path values remain separate outcomes`() {
        val engine =
            engine(
                listOf(
                    entry(source(candidate("Build/", 3, 3, "mru", TerminalCompletionCandidateKind.PATH)), 0),
                    entry(source(candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH)), 0),
                ),
            )

        val candidates = engine.complete(request("cd "))

        assertEquals(2, candidates.size)
        assertTrue(candidates.map { it.replacementText }.containsAll(listOf("Build/", "build/")))
    }

    @Test
    fun `trailing separators are not normalized for non-path values`() {
        val candidates =
            engine(
                listOf(
                    entry(source(branchCandidate("feature", replacementEndOffset = 11)), 15),
                    entry(source(branchCandidate("feature/", replacementEndOffset = 11)), 15),
                ),
            ).complete(request("git switch "))

        assertEquals(2, candidates.size)
    }

    @Test
    fun `different arguments and reordered options remain distinct outcomes`() {
        val commandLine = "t"
        val candidates =
            engine(
                listOf(
                    entry(
                        source(
                            candidate("tool --alpha --beta one", 0, 1, "one", TerminalCompletionCandidateKind.HISTORY),
                            candidate("tool --alpha --beta two", 0, 1, "two", TerminalCompletionCandidateKind.HISTORY),
                            candidate("tool --beta --alpha one", 0, 1, "reordered", TerminalCompletionCandidateKind.HISTORY),
                        ),
                        0,
                    ),
                ),
            ).complete(request(commandLine))

        assertEquals(3, candidates.size)
    }

    @Test
    fun `equivalent commands merge even when contributors replace different ranges`() {
        val commandLine = "git st"
        val candidates =
            engine(
                listOf(
                    entry(
                        source(candidate("git status", 0, commandLine.length, "history", TerminalCompletionCandidateKind.HISTORY)),
                        8,
                    ),
                    entry(
                        source(candidate("status", 4, commandLine.length, "spec", TerminalCompletionCandidateKind.SUBCOMMAND)),
                        0,
                    ),
                ),
            ).complete(request(commandLine))

        assertEquals(1, candidates.size)
        assertEquals("git status", commandLineAfter(candidates.single(), commandLine))
    }

    @Test
    fun `invalid replacement ranges use stable fallback grouping`() {
        val malformed = candidate("status", 20, 30, "first", TerminalCompletionCandidateKind.SUBCOMMAND)
        val candidates =
            engine(
                listOf(
                    entry(source(malformed), 0),
                    entry(source(malformed.copy(source = "second")), 0),
                    entry(source(malformed.copy(replacementStartOffset = 21, source = "third")), 0),
                ),
            ).complete(request("git s"))

        assertEquals(2, candidates.size)
        assertEquals("first", candidates.first().source)
    }

    @Test
    fun `maximum counters future timestamps and extreme priorities remain bounded`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        TerminalCommandCompletionStats(
                            commandLine = "git switch main",
                            useCount = Int.MAX_VALUE,
                            successCount = Int.MAX_VALUE,
                            failureCount = Int.MAX_VALUE,
                            acceptedCount = Int.MAX_VALUE,
                            dismissedCount = Int.MAX_VALUE,
                            lastUsedEpochMillis = Long.MAX_VALUE,
                        ),
                    ),
            )
        val candidates =
            engine(
                sources =
                    listOf(
                        entry(source(branchCandidate("main", score = Int.MAX_VALUE)), Int.MAX_VALUE),
                        entry(source(branchCandidate("maint", score = Int.MIN_VALUE)), Int.MIN_VALUE),
                    ),
                snapshot = snapshot,
            ).complete(request("git switch ma"))

        assertEquals(2, candidates.size)
        assertEquals(candidates.map { it.score }.sortedDescending(), candidates.map { it.score })
    }

    private fun engine(
        sources: List<TerminalCompletionSourceEntry>,
        snapshot: TerminalCommandCompletionStatsSnapshot = TerminalCommandCompletionStatsSnapshot.EMPTY,
    ): TerminalCompletionEngine =
        MergedCompletionEngine(
            sources = sources,
            learnedStatsProvider = { snapshot },
            clockEpochMillis = { NOW },
        )

    private fun entry(
        source: TerminalCompletionSource,
        priority: Int,
    ) = TerminalCompletionSourceEntry(source, priority)

    private fun source(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { candidates.toList() }

    private fun candidate(
        replacement: String,
        start: Int,
        end: Int,
        source: String,
        kind: TerminalCompletionCandidateKind,
        score: Int = 0,
        domain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
    ) = TerminalCompletionCandidate(
        replacementText = replacement,
        replacementStartOffset = start,
        replacementEndOffset = end,
        source = source,
        kind = kind,
        score = score,
        valueDomain = domain,
    )

    private fun branchCandidate(
        replacement: String,
        score: Int = 0,
        replacementEndOffset: Int = 13,
    ): TerminalCompletionCandidate =
        candidate(
            replacement = replacement,
            start = 11,
            end = replacementEndOffset,
            source = "branches",
            kind = TerminalCompletionCandidateKind.ARGUMENT,
            score = score,
            domain = TerminalCompletionValueDomain.GIT_BRANCH,
        )

    private fun commandLineAfter(
        candidate: TerminalCompletionCandidate,
        commandLine: String,
    ): String =
        commandLine.substring(0, candidate.replacementStartOffset) +
            candidate.replacementText +
            commandLine.substring(candidate.replacementEndOffset)

    private fun request(
        commandLine: String,
        workingDirectoryUri: String = "file:///repo",
    ) = TerminalCompletionRequest(
        commandLine = commandLine,
        cursorOffset = commandLine.length,
        maxCandidates = 8,
        profileId = "profile",
        workingDirectoryUri = workingDirectoryUri,
        shellCapabilities = TerminalShellCapabilities.POSIX,
    )

    private companion object {
        private const val NOW = 2_000_000_000_000L
        private const val FORTY_DAYS = 40L * 24L * 60L * 60L * 1_000L
    }
}
