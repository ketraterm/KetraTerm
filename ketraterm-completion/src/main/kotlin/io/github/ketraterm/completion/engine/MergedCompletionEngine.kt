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
            val completions = Channel<SourceCompletion>(sources.size)
            val rankingState =
                ranker.createRequestState(
                    request = request,
                    context = completionContext,
                    resultLimit = REQUEST_CANDIDATE_LIMIT,
                )
            var lastPublished: List<TerminalCompletionCandidate>? = null

            try {
                supervisorScope {
                    sources.forEachIndexed { sourceIndex, entry ->
                        launch {
                            val candidates =
                                try {
                                    entry.source
                                        .complete(request, completionContext, SOURCE_CANDIDATE_LIMIT)
                                        .filter { it.hasValidReplacementRangeFor(request) }
                                        .boundedTo(SOURCE_CANDIDATE_LIMIT)
                                } catch (cancellation: CancellationException) {
                                    if (!coroutineContext.isActive) throw cancellation
                                    emptyList()
                                } catch (failure: Exception) {
                                    reportSourceFailure(sourceIndex, entry, failure)
                                    emptyList()
                                }
                            completions.send(SourceCompletion(sourceIndex, entry.priority, candidates))
                        }
                    }

                    repeat(sources.size) {
                        val completed = completions.receive()
                        if (completed.candidates.isNotEmpty()) {
                            rankingState.ingest(
                                CompletionSourceCandidates(
                                    completed.sourceIndex,
                                    completed.priority,
                                    completed.candidates,
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
        val candidates: List<TerminalCompletionCandidate>,
    )

    private companion object {
        private const val SOURCE_CANDIDATE_LIMIT = 256
        private const val REQUEST_CANDIDATE_LIMIT = 256
    }
}
