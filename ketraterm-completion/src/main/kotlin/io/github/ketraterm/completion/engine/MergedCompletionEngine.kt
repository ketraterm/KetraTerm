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
import io.github.ketraterm.completion.internal.boundedTo
import io.github.ketraterm.completion.internal.hasValidReplacementRangeFor
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.ranking.CompletionSourceCandidates
import io.github.ketraterm.completion.ranking.GlobalCompletionRanker
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
    learningStore: TerminalCompletionLearningStore? = null,
    clockEpochMillis: () -> Long = System::currentTimeMillis,
    private val sourceFailureHandler: TerminalCompletionSourceFailureHandler =
        TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER,
) : TerminalCompletionEngine {
    private val commandSpecs = commandSpecs.toList()
    private val sources =
        buildList(sources.size + 1) {
            if (this@MergedCompletionEngine.commandSpecs.isNotEmpty()) {
                add(
                    TerminalCompletionSourceEntry(
                        source = SpecCompletionSource(this@MergedCompletionEngine.commandSpecs),
                        priority = TerminalCompletionSourcePrior.STATIC_SPECIFICATION,
                    ),
                )
            }
            addAll(sources)
        }
    private val ranker = GlobalCompletionRanker(this.commandSpecs, learningStore, clockEpochMillis)
    private val pathCommandSpecCandidateProjector = PathCommandSpecCandidateProjector(this.commandSpecs)

    override fun completions(request: TerminalCompletionRequest): Flow<List<TerminalCompletionCandidate>> =
        channelFlow {
            if (sources.isEmpty()) {
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
            val rankingState =
                ranker.createRequestState(
                    request = request,
                    context = completionContext,
                    resultLimit = REQUEST_CANDIDATE_LIMIT,
                )
            var lastPublished: List<TerminalCompletionCandidate>? = null

            // 1. Fast in-memory evaluation (Synchronous on the caller coroutine)
            var hasAsyncSources = false
            for (sourceIndex in sources.indices) {
                val entry = sources[sourceIndex]
                if (entry.source.isFastInMemory) {
                    val candidates =
                        try {
                            entry.source
                                .complete(request, completionContext, SOURCE_CANDIDATE_LIMIT)
                                .let { pathCommandSpecCandidateProjector.project(request, completionContext, it) }
                                .filter { it.hasValidReplacementRangeFor(request) }
                                .boundedTo(SOURCE_CANDIDATE_LIMIT)
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
                                presentationRole = entry.source.presentationRole,
                                candidates = candidates,
                            ),
                        )
                    }
                } else {
                    hasAsyncSources = true
                }
            }

            // Immediately emit the initial synchronous ranking (specs + MRU history)
            val initialRanked = rankingState.rankedCandidates()
            if (initialRanked.isNotEmpty() || !hasAsyncSources) {
                lastPublished = initialRanked
                send(initialRanked)
            }

            // If there are no async/suspending sources (e.g. pure CLI spec / history typing), finish immediately!
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
                                    asyncEntry.entry.source
                                        .complete(request, completionContext, SOURCE_CANDIDATE_LIMIT)
                                        .let { pathCommandSpecCandidateProjector.project(request, completionContext, it) }
                                        .filter { it.hasValidReplacementRangeFor(request) }
                                        .boundedTo(SOURCE_CANDIDATE_LIMIT)
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
                                    presentationRole = asyncEntry.entry.source.presentationRole,
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
                                    presentationRole = completed.presentationRole,
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
        val presentationRole: TerminalCompletionSourcePresentationRole,
        val candidates: List<TerminalCompletionCandidate>,
    )

    private data class IndexedSourceEntry(
        val sourceIndex: Int,
        val entry: TerminalCompletionSourceEntry,
    )

    private companion object {
        private const val SOURCE_CANDIDATE_LIMIT = 256
        private const val REQUEST_CANDIDATE_LIMIT = 256
    }
}
