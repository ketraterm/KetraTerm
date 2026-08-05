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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.internal.completionCollectionLimit
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.ranking.CompletionSourceCandidates
import io.github.ketraterm.completion.ranking.GlobalCompletionRanker

/** Coordinates bounded source collection and delegates deterministic fusion to [GlobalCompletionRanker]. */
internal class MergedCompletionEngine(
    sources: List<TerminalCompletionSourceEntry>,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    learnedStatsProvider: () -> TerminalCommandCompletionStatsSnapshot = { TerminalCommandCompletionStatsSnapshot.EMPTY },
    clockEpochMillis: () -> Long = System::currentTimeMillis,
) : TerminalCompletionEngine {
    private val sources = sources.toList()
    private val commandSpecs = commandSpecs.toList()
    private val ranker = GlobalCompletionRanker(this.commandSpecs, learnedStatsProvider, clockEpochMillis)

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        if (sources.isEmpty()) return emptyList()

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
        if (completionContext.activePosition == TerminalCompletionActivePosition.OPERATOR) return emptyList()
        val collectionLimit = completionCollectionLimit(request.maxCandidates)
        val collected = ArrayList<CompletionSourceCandidates>(sources.size)
        var alternateContexts: MutableMap<List<TerminalCommandSpec>, TerminalCompletionContext>? = null
        for (sourceIndex in sources.indices) {
            val entry = sources[sourceIndex]
            val sourceSpecs = entry.source.contextCommandSpecs
            val sourceContext =
                if (sourceSpecs == null || sourceSpecs == commandSpecs) {
                    completionContext
                } else {
                    val contexts =
                        alternateContexts ?: HashMap<List<TerminalCommandSpec>, TerminalCompletionContext>().also {
                            alternateContexts = it
                        }
                    contexts.getOrPut(sourceSpecs) { request.resolveCompletionContext(sourceSpecs) }
                }
            val candidates = entry.source.collectCandidates(request, sourceContext, collectionLimit)
            if (candidates.isNotEmpty()) {
                collected += CompletionSourceCandidates(sourceIndex, entry.priority, candidates)
            }
        }
        return ranker.rank(request, completionContext, collected)
    }
}
