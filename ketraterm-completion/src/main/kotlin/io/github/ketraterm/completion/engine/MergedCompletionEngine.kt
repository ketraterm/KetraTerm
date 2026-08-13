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
import io.github.ketraterm.completion.internal.completionCollectionLimit
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.ranking.CompletionSourceCandidates
import io.github.ketraterm.completion.ranking.GlobalCompletionRanker
import io.github.ketraterm.completion.spec.SpecCompletionSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Coordinates bounded source collection and delegates deterministic fusion to [GlobalCompletionRanker]. */
internal class MergedCompletionEngine(
    sources: List<TerminalCompletionSourceEntry>,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    learningStore: TerminalCompletionLearningStore? = null,
    clockEpochMillis: () -> Long = System::currentTimeMillis,
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

    override suspend fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        coroutineScope {
            if (sources.isEmpty()) return@coroutineScope emptyList()

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
            if (completionContext.activePosition == TerminalCompletionActivePosition.OPERATOR) return@coroutineScope emptyList()
            val collectionLimit = completionCollectionLimit(request.maxCandidates)
            val collected =
                sources
                    .mapIndexed { sourceIndex, entry ->
                        async {
                            val candidates = entry.source.complete(request, completionContext, collectionLimit)
                            if (candidates.isEmpty()) null else CompletionSourceCandidates(sourceIndex, entry.priority, candidates)
                        }
                    }.awaitAll()
                    .filterNotNull()
            ranker.rank(request, completionContext, collected)
        }
}
