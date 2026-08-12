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
import io.github.ketraterm.completion.model.*
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures learned-ranking lookup and bounded multi-provider merge paths. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalLearnedRankingBenchmark {
    private lateinit var learnedEngine: TerminalCompletionEngine
    private lateinit var hostileMergeEngine: TerminalCompletionEngine
    private lateinit var learnedRequest: TerminalCompletionRequest
    private lateinit var hostileMergeRequest: TerminalCompletionRequest
    private lateinit var statsSource: TerminalCompletionLearningStore

    @Setup
    open fun setUp() {
        val commandSpecs = TerminalCommandSpecs.defaults()
        statsSource = TerminalCompletionSources.learningStore(capacity = STATS_CAPACITY, commandSpecs = commandSpecs)
        statsSource.replaceSnapshot(fullLearnedSnapshot())
        val specSource = TerminalCompletionSources.fromSpecs(commandSpecs)
        learnedEngine =
            TerminalCompletionEngines.fromSources(
                sources = listOf(TerminalCompletionSourceEntry(specSource)),
                commandSpecs = commandSpecs,
                learnedStatsProvider = statsSource::snapshotAll,
            )
        learnedRequest =
            TerminalCompletionRequest(
                commandLine = "git s",
                cursorOffset = 5,
                profileId = TARGET_PROFILE,
                workingDirectoryUri = TARGET_DIRECTORY,
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )

        hostileMergeEngine =
            TerminalCompletionEngines.fromSources(
                sources =
                    List(PROVIDER_COUNT) { providerIndex ->
                        TerminalCompletionSourceEntry(
                            source = fixedProvider(providerIndex),
                            priority = providerIndex,
                        )
                    },
                commandSpecs = commandSpecs,
            )
        hostileMergeRequest =
            TerminalCompletionRequest(
                commandLine = "unknown value",
                cursorOffset = "unknown value".length,
                maxCandidates = 8,
            )
    }

    @Benchmark
    open fun completeWithFullLearnedSnapshots(blackhole: Blackhole) {
        blackhole.consume(runBlocking { learnedEngine.complete(learnedRequest) })
    }

    @Benchmark
    open fun mergeHostileProviderResultsIntoTopEight(blackhole: Blackhole) {
        blackhole.consume(runBlocking { hostileMergeEngine.complete(hostileMergeRequest) })
    }

    @Benchmark
    open fun readPublishedLearnedSnapshots(blackhole: Blackhole) {
        blackhole.consume(statsSource.shapeSnapshot())
        blackhole.consume(statsSource.feedbackSnapshot())
        blackhole.consume(statsSource.snapshotAll())
    }

    private fun fullLearnedSnapshot(): TerminalCommandCompletionStatsSnapshot {
        val shapeStats = ArrayList<TerminalCommandShapeStats>(STATS_CAPACITY)
        val feedbackStats = ArrayList<TerminalCompletionFeedbackStats>(STATS_CAPACITY)
        var index = 0
        while (index < STATS_CAPACITY - 1) {
            shapeStats +=
                TerminalCommandShapeStats(
                    shape = TerminalCommandLineShape(executable = "tool-$index"),
                    profileId = "profile-$index",
                    workingDirectoryUri = "file:///workspace/$index",
                    successCount = 1,
                    lastUsedEpochMillis = index.toLong(),
                )
            feedbackStats +=
                TerminalCompletionFeedbackStats(
                    source = "provider-$index",
                    candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    tokenPosition = TerminalCompletionTokenPosition.SUBCOMMAND,
                    profileId = "profile-$index",
                    workingDirectoryUri = "file:///workspace/$index",
                    acceptedCount = 1,
                    lastUsedEpochMillis = index.toLong(),
                )
            index++
        }
        shapeStats +=
            TerminalCommandShapeStats(
                shape = TerminalCommandLineShape(executable = "git", subcommands = listOf("status")),
                profileId = TARGET_PROFILE,
                workingDirectoryUri = TARGET_DIRECTORY,
                acceptedCount = 8,
                lastUsedEpochMillis = STATS_CAPACITY.toLong(),
            )
        feedbackStats +=
            TerminalCompletionFeedbackStats(
                source = "spec",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                tokenPosition = TerminalCompletionTokenPosition.SUBCOMMAND,
                profileId = TARGET_PROFILE,
                workingDirectoryUri = TARGET_DIRECTORY,
                acceptedCount = 8,
                lastUsedEpochMillis = STATS_CAPACITY.toLong(),
            )
        return TerminalCommandCompletionStatsSnapshot(
            shapeStats = shapeStats,
            feedbackStats = feedbackStats,
        )
    }

    private fun fixedProvider(providerIndex: Int): TerminalCompletionSource {
        val candidates =
            List(CANDIDATES_PER_PROVIDER) { candidateIndex ->
                TerminalCompletionCandidate(
                    replacementText = "value-$providerIndex-$candidateIndex",
                    replacementStartOffset = 8,
                    replacementEndOffset = 13,
                    displayText = "candidate-${CANDIDATES_PER_PROVIDER - candidateIndex}",
                    source = "provider-$providerIndex",
                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                    score = (candidateIndex * 37) % 101,
                )
            }
        return TerminalCompletionSource { _, _, _ -> candidates }
    }

    private companion object {
        private const val STATS_CAPACITY = 2_048
        private const val PROVIDER_COUNT = 4
        private const val CANDIDATES_PER_PROVIDER = 256
        private const val TARGET_PROFILE = "benchmark-profile"
        private const val TARGET_DIRECTORY = "file:///benchmark"
    }
}
