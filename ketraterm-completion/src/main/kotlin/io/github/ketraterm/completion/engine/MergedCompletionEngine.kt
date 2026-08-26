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
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.TerminalCompletionContextResolver
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.boundedTo
import io.github.ketraterm.completion.internal.hasValidReplacementRangeFor
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.ranking.CompletionSourceCandidates
import io.github.ketraterm.completion.ranking.GlobalCompletionRanker
import io.github.ketraterm.completion.source.appendLearnedHistoryCandidates
import io.github.ketraterm.completion.spec.PathCommandSpecCandidateProjector
import io.github.ketraterm.completion.spec.SpecCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Coordinates bounded source collection and delegates deterministic fusion to [GlobalCompletionRanker]. */
internal class MergedCompletionEngine(
    sources: List<TerminalCompletionSourceEntry>,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val learningStore: TerminalCompletionLearningStore? = null,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
    private val sourceFailureHandler: TerminalCompletionSourceFailureHandler =
        TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER,
) : TerminalCompletionEngine {
    private val commandSpecs = commandSpecs
    private val sources =
        buildList(sources.size + 1) {
            if (this@MergedCompletionEngine.commandSpecs.isNotEmpty()) {
                add(
                    TerminalCompletionSourceEntry(
                        source = SpecCompletionSource(this@MergedCompletionEngine.commandSpecs),
                        priority = STATIC_SPECIFICATION_PRIORITY,
                    ),
                )
            }
            addAll(sources)
        }
    private val learnedSourceIndex = this.sources.size
    private val ranker = GlobalCompletionRanker()
    private val pathCommandSpecCandidateProjector = PathCommandSpecCandidateProjector(this.commandSpecs)

    override fun completions(request: TerminalCompletionRequest): Flow<List<TerminalCompletionCandidate>> =
        channelFlow {
            if (sources.isEmpty() && learningStore == null) {
                send(emptyList())
                return@channelFlow
            }

            val commandLineContext =
                TerminalCommandLineTokenizer.parse(
                    request.commandLine,
                    request.cursorOffset,
                    request.shellCapabilities.syntax,
                )
            val completionContext =
                TerminalCompletionContextResolver.resolve(
                    commandLine = request.commandLine,
                    lineContext = commandLineContext,
                    commandSpecs = commandSpecs,
                )
            if (completionContext.activePosition == TerminalCompletionActivePosition.OPERATOR) {
                send(emptyList())
                return@channelFlow
            }
            val learningIndexes = learningStore?.indexesFor(request.shellCapabilities.syntax)
            val nowEpochMillis = clockEpochMillis().coerceAtLeast(0L)
            val rankingState =
                ranker.createRequestState(
                    request = request,
                    context = completionContext,
                    resultLimit = REQUEST_CANDIDATE_LIMIT,
                    learnedIndex = learningIndexes?.evidence,
                    nowEpochMillis = nowEpochMillis,
                )
            var lastPublished: List<TerminalCompletionCandidate>? = null

            learningIndexes
                ?.takeUnless { completionContext.commandLineContext.precededByOperator }
                ?.let { indexes ->
                    val learnedCandidates =
                        ArrayList<TerminalCompletionCandidate>()
                            .apply {
                                appendLearnedHistoryCandidates(
                                    request = request,
                                    lineContext = completionContext.commandLineContext,
                                    completionContext = completionContext,
                                    index = indexes.history,
                                    nowEpochMillis = nowEpochMillis,
                                    destination = this,
                                )
                                indexes.observed.appendCandidates(
                                    request = request,
                                    context = completionContext,
                                    destination = this,
                                )
                                sortWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
                            }.let { candidates ->
                                pathCommandSpecCandidateProjector
                                    .project(request, completionContext, candidates)
                                    .filter { it.hasValidReplacementRangeFor(request) }
                                    .boundedTo(SOURCE_CANDIDATE_LIMIT)
                            }
                    if (learnedCandidates.isNotEmpty()) {
                        rankingState.ingest(
                            CompletionSourceCandidates(
                                sourceIndex = learnedSourceIndex,
                                priority = LEARNED_PRIORITY,
                                isFallback = true,
                                candidates = learnedCandidates,
                            ),
                        )
                    }
                }

            // 1. Fast in-memory evaluation (synchronous on the caller coroutine).
            var hasAsyncSources = false
            for (sourceIndex in sources.indices) {
                val entry = sources[sourceIndex]
                if (entry.source.isFastInMemory) {
                    val candidates =
                        try {
                            completeSource(
                                entry = entry,
                                request = request,
                                context = completionContext,
                            )
                        } catch (cancellation: CancellationException) {
                            if (!coroutineContext.isActive) throw cancellation
                            emptyList()
                        } catch (failure: Exception) {
                            reportSourceFailure(sourceIndex, entry, failure)
                            emptyList()
                        }
                    if (candidates.isNotEmpty()) {
                        rankingState.ingest(
                            CompletionSourceCandidates(
                                sourceIndex = sourceIndex,
                                priority = entry.priority,
                                candidates = candidates,
                            ),
                        )
                    }
                } else {
                    hasAsyncSources = true
                }
            }

            // Immediately emit the initial synchronous ranking from specs and learned data.
            val initialRanked = rankingState.rankedCandidates()
            if (initialRanked.isNotEmpty() || !hasAsyncSources) {
                lastPublished = initialRanked
                send(initialRanked)
            }

            // If there are no suspending sources, the synchronous ranking is final.
            if (!hasAsyncSources) {
                return@channelFlow
            }

            // 2. Asynchronous background evaluation strictly for suspending/IO sources (paths, Git branches, etc.)
            val asyncSources = ArrayList<IndexedSourceEntry>(sources.size)
            for (sourceIndex in sources.indices) {
                val entry = sources[sourceIndex]
                if (!entry.source.isFastInMemory) {
                    asyncSources += IndexedSourceEntry(sourceIndex, entry)
                }
            }

            val completions = Channel<SourceCompletion>(asyncSources.size)
            try {
                supervisorScope {
                    for (asyncEntry in asyncSources) {
                        launch {
                            val candidates =
                                try {
                                    completeSource(
                                        entry = asyncEntry.entry,
                                        request = request,
                                        context = completionContext,
                                    )
                                } catch (cancellation: CancellationException) {
                                    if (!coroutineContext.isActive) throw cancellation
                                    emptyList()
                                } catch (failure: Exception) {
                                    reportSourceFailure(asyncEntry.sourceIndex, asyncEntry.entry, failure)
                                    emptyList()
                                }
                            completions.send(
                                SourceCompletion(
                                    sourceIndex = asyncEntry.sourceIndex,
                                    priority = asyncEntry.entry.priority,
                                    candidates = candidates,
                                ),
                            )
                        }
                    }

                    repeat(asyncSources.size) {
                        val completed = completions.receive()
                        if (completed.candidates.isNotEmpty()) {
                            rankingState.ingest(
                                CompletionSourceCandidates(
                                    sourceIndex = completed.sourceIndex,
                                    priority = completed.priority,
                                    candidates = completed.candidates,
                                ),
                            )
                        }
                        val ranked = rankingState.rankedCandidates()
                        if (ranked != lastPublished) {
                            lastPublished = ranked
                            send(ranked)
                        }
                    }
                }
            } finally {
                completions.cancel()
            }
        }

    private suspend fun completeSource(
        entry: TerminalCompletionSourceEntry,
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): List<TerminalCompletionCandidate> {
        val candidates = entry.source.complete(request, context, SOURCE_CANDIDATE_LIMIT)
        return pathCommandSpecCandidateProjector
            .project(request, context, candidates)
            .filter { it.hasValidReplacementRangeFor(request) }
            .boundedTo(SOURCE_CANDIDATE_LIMIT)
    }

    private fun reportSourceFailure(
        sourceIndex: Int,
        source: TerminalCompletionSourceEntry,
        failure: Throwable,
    ) {
        try {
            sourceFailureHandler.sourceFailed(sourceIndex, source, failure)
        } catch (diagnosticFailure: RuntimeException) {
            diagnosticFailure.addSuppressed(failure)
            TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER.sourceFailed(sourceIndex, source, diagnosticFailure)
        }
    }

    private data class SourceCompletion(
        val sourceIndex: Int,
        val priority: Int,
        val candidates: List<TerminalCompletionCandidate>,
    )

    private data class IndexedSourceEntry(
        val sourceIndex: Int,
        val entry: TerminalCompletionSourceEntry,
    )

    private companion object {
        private const val STATIC_SPECIFICATION_PRIORITY = 0
        private const val LEARNED_PRIORITY = 8
        private const val SOURCE_CANDIDATE_LIMIT = 256
        private const val REQUEST_CANDIDATE_LIMIT = 256
    }
}
