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

internal class MergedCompletionEngine(
    sources: List<TerminalCompletionSourceEntry>,
) : TerminalCompletionEngine {
    private val sources = sources.toList()

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        if (sources.isEmpty()) return emptyList()

        val deduplicated = LinkedHashMap<CandidateKey, RankedCandidate>()
        for (sourceIndex in sources.indices) {
            val entry = sources[sourceIndex]
            val candidates = entry.source.complete(request)
            for (candidateIndex in candidates.indices) {
                val candidate = candidates[candidateIndex]
                val ranked =
                    RankedCandidate(
                        candidate = candidate,
                        sourcePriority = entry.priority,
                        sourceIndex = sourceIndex,
                        candidateIndex = candidateIndex,
                    )
                val key = CandidateKey(candidate)
                val existing = deduplicated[key]
                if (existing == null || RANKING.compare(ranked, existing) < 0) {
                    deduplicated[key] = ranked
                }
            }
        }

        if (deduplicated.isEmpty()) return emptyList()
        return deduplicated.values
            .sortedWith(RANKING)
            .take(request.maxCandidates)
            .map { it.candidate }
    }

    private data class CandidateKey(
        val replacementText: String,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
    ) {
        constructor(candidate: TerminalCompletionCandidate) : this(
            replacementText = candidate.replacementText,
            replacementStartOffset = candidate.replacementStartOffset,
            replacementEndOffset = candidate.replacementEndOffset,
        )
    }

    private data class RankedCandidate(
        val candidate: TerminalCompletionCandidate,
        val sourcePriority: Int,
        val sourceIndex: Int,
        val candidateIndex: Int,
    )

    private companion object {
        private val RANKING =
            compareByDescending<RankedCandidate> { it.sourcePriority }
                .thenByDescending { it.candidate.score }
                .thenBy { it.candidate.displayText }
                .thenBy { it.candidate.replacementText }
                .thenBy { it.sourceIndex }
                .thenBy { it.candidateIndex }
    }
}
