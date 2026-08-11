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
package io.github.ketraterm.benchmark

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures pure static completion through the full POSIX segment-aware engine path. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalCompletionBenchmark {
    private val commandSpecs = TerminalCommandSpecs.defaults()
    private val engine =
        TerminalCompletionEngines.fromSources(
            sources = listOf(TerminalCompletionSourceEntry(TerminalCompletionSources.fromSpecs(commandSpecs))),
            commandSpecs = commandSpecs,
        )

    @Param("64", "512", "4096", "32768")
    var commandLineLength: Int = 0

    private lateinit var chainedRequest: TerminalCompletionRequest
    private lateinit var unclosedQuoteRequest: TerminalCompletionRequest
    private lateinit var fusionRequest: TerminalCompletionRequest
    private lateinit var realisticSources: List<TerminalCompletionSourceEntry>
    private lateinit var learnedSnapshot: TerminalCommandCompletionStatsSnapshot
    private lateinit var coldStartFusionEngine: TerminalCompletionEngine
    private lateinit var learnedFusionEngine: TerminalCompletionEngine
    private lateinit var indexedPersistedHistoryEngine: TerminalCompletionEngine
    private lateinit var duplicateFusionEngine: TerminalCompletionEngine
    private lateinit var hostileFusionEngine: TerminalCompletionEngine
    private lateinit var fuzzyPathEngine: TerminalCompletionEngine
    private lateinit var fuzzyPathRequest: TerminalCompletionRequest

    @Setup(Level.Trial)
    open fun setUp() {
        chainedRequest = requestFor("git status && ", "git sw")
        unclosedQuoteRequest = requestFor("git status && cd ", "\"Idea Pro")
        fusionRequest =
            TerminalCompletionRequest(
                commandLine = "git switch ma",
                cursorOffset = "git switch ma".length,
                maxCandidates = 8,
                profileId = "benchmark",
                workingDirectoryUri = "file:///repo",
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )
        realisticSources = List(8) { sourceIndex -> sourceEntry(sourceIndex, 32, duplicateMain = true) }
        coldStartFusionEngine = TerminalCompletionEngines.fromSources(realisticSources, commandSpecs)
        learnedSnapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    List(2_048) { index ->
                        TerminalCommandCompletionStats(
                            commandLine = if (index == 0) "git switch main" else "git switch branch-$index",
                            profileId = "benchmark",
                            workingDirectoryUri = "file:///repo",
                            useCount = index % 20,
                            successCount = index % 17,
                            acceptedCount = index % 7,
                            lastUsedEpochMillis = 2_000_000_000_000L - index,
                        )
                    },
            )
        learnedFusionEngine =
            TerminalCompletionEngines.fromSources(
                realisticSources,
                commandSpecs,
                learnedStatsProvider = { learnedSnapshot },
            )
        val persistedStatsSource = TerminalCompletionSources.commandStats(capacity = 2_048, commandSpecs = commandSpecs)
        persistedStatsSource.replaceSnapshot(learnedSnapshot)
        val sessionMru =
            TerminalCompletionSources.sessionMru(
                commandSpecs = commandSpecs,
                learnedStatsProvider = persistedStatsSource::snapshotAll,
            )
        sessionMru.recordSuccessfulCommand(
            commandLine = "git switch main",
            profileId = "benchmark",
            workingDirectoryUri = "file:///repo",
        )
        indexedPersistedHistoryEngine =
            TerminalCompletionEngines.fromSources(
                sources =
                    listOf(
                        TerminalCompletionSourceEntry(
                            source = sessionMru,
                            priority = TerminalCompletionSourcePrior.SESSION_MRU,
                        ),
                    ),
                commandSpecs = commandSpecs,
                learnedStatsProvider = persistedStatsSource::snapshotAll,
            )
        indexedPersistedHistoryEngine.complete(fusionRequest)
        duplicateFusionEngine = TerminalCompletionEngines.fromSources(List(8) { sourceEntry(it, 32, duplicateMain = true) }, commandSpecs)
        hostileFusionEngine = TerminalCompletionEngines.fromSources(List(10) { sourceEntry(it, 256, duplicateMain = false) }, commandSpecs)
        val fuzzyPaths =
            List(FUZZY_PATH_ENTRY_COUNT) { index ->
                TerminalFuzzyPathEntry(
                    path = "src/module-${index and 63}/GeneratedFile$index.kt",
                    isDirectory = false,
                )
            }
        fuzzyPathEngine =
            TerminalCompletionEngines.fromSources(
                sources =
                    listOf(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSources.fuzzyPath(
                                sourceId = "benchmark-project-path",
                                entriesProvider = { fuzzyPaths },
                                commandSpecs = commandSpecs,
                            ),
                        ),
                    ),
                commandSpecs = commandSpecs,
            )
        fuzzyPathRequest =
            TerminalCompletionRequest(
                commandLine = "git add GenF327",
                cursorOffset = "git add GenF327".length,
                maxCandidates = 8,
                profileId = "benchmark",
                workingDirectoryUri = "file:///repo",
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )
    }

    @Benchmark
    open fun completeChainedCommand(blackhole: Blackhole) {
        blackhole.consume(engine.complete(chainedRequest))
    }

    @Benchmark
    open fun completeUnclosedQuote(blackhole: Blackhole) {
        blackhole.consume(engine.complete(unclosedQuoteRequest))
    }

    @Benchmark
    open fun completeColdStartFusion(blackhole: Blackhole) {
        blackhole.consume(coldStartFusionEngine.complete(fusionRequest))
    }

    @Benchmark
    open fun completeLearnedFusion(blackhole: Blackhole) {
        blackhole.consume(learnedFusionEngine.complete(fusionRequest))
    }

    /** Measures cold construction of the 2,048-row learned-evidence index and one ranked completion. */
    @Benchmark
    open fun buildLearnedIndexAndComplete(blackhole: Blackhole) {
        val coldEngine =
            TerminalCompletionEngines.fromSources(
                sources = realisticSources,
                commandSpecs = commandSpecs,
                learnedStatsProvider = { learnedSnapshot },
            )
        blackhole.consume(coldEngine.complete(fusionRequest))
    }

    /** Measures hot indexed lookup across a full 2,048-row learned snapshot. */
    @Benchmark
    open fun completeIndexedPersistedHistory(blackhole: Blackhole) {
        blackhole.consume(indexedPersistedHistoryEngine.complete(fusionRequest))
    }

    @Benchmark
    open fun completeDuplicateHeavyFusion(blackhole: Blackhole) {
        blackhole.consume(duplicateFusionEngine.complete(fusionRequest))
    }

    @Benchmark
    open fun completeHostileFusion(blackhole: Blackhole) {
        blackhole.consume(hostileFusionEngine.complete(fusionRequest))
    }

    /** Measures allocation and throughput while scanning a realistic immutable project-path snapshot. */
    @Benchmark
    open fun completeFuzzyProjectPath(blackhole: Blackhole) {
        blackhole.consume(fuzzyPathEngine.complete(fuzzyPathRequest))
    }

    private fun sourceEntry(
        sourceIndex: Int,
        count: Int,
        duplicateMain: Boolean,
    ): TerminalCompletionSourceEntry =
        TerminalCompletionSourceEntry(
            source =
                TerminalCompletionSource {
                    List(count) { candidateIndex ->
                        val value = if (duplicateMain && candidateIndex == 0) "main" else "match-$sourceIndex-$candidateIndex"
                        TerminalCompletionCandidate(
                            replacementText = value,
                            replacementStartOffset = 11,
                            replacementEndOffset = 13,
                            source = "benchmark-$sourceIndex",
                            kind = TerminalCompletionCandidateKind.ARGUMENT,
                            score = count - candidateIndex,
                            valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        )
                    }
                },
            priority = sourceIndex.coerceAtMost(15),
        )

    private fun requestFor(
        prefix: String,
        suffix: String,
    ): TerminalCompletionRequest {
        val paddingUnit = "echo x && "
        val paddingLength = (commandLineLength - prefix.length - suffix.length).coerceAtLeast(0)
        val padding =
            buildString(paddingLength) {
                while (length + paddingUnit.length <= paddingLength) append(paddingUnit)
                while (length < paddingLength) append(' ')
            }
        val commandLine = prefix + padding + suffix
        return TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
    }

    private companion object {
        private const val FUZZY_PATH_ENTRY_COUNT = 32_768
    }
}
