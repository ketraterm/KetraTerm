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
package io.github.ketraterm.completion.ranking

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec

/**
 * Deterministic global evidence-fusion ranker for one merged completion engine.
 *
 * This component groups projected outcomes, chooses representatives, and orders
 * the fused results. Outcome resolution, context relevance, numeric policy, and
 * learned evidence indexing are delegated to their owning collaborators.
 */
internal class GlobalCompletionRanker(
    commandSpecs: List<TerminalCommandSpec>,
    private val learnedStatsProvider: () -> TerminalCommandCompletionStatsSnapshot,
    private val clockEpochMillis: () -> Long,
) {
    private val outcomeResolver = TerminalCompletionOutcomeKeyResolver(commandSpecs)
    private val learnedIndexCache = LearnedCompletionEvidenceIndexCache(outcomeResolver)

    fun rank(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        sourceCandidates: List<CompletionSourceCandidates>,
    ): List<TerminalCompletionCandidate> {
        val rankingContext = TerminalCompletionRankingContext(context)
        val aggregates = groupByOutcome(request, context, sourceCandidates, rankingContext)
        if (aggregates.isEmpty()) return emptyList()

        val learnedIndex = learnedIndexCache.indexFor(learnedStatsProvider(), request.shellCapabilities.syntax)
        val now = clockEpochMillis().coerceAtLeast(0L)
        return aggregates.values
            .map { aggregate -> aggregate.finish(request, learnedIndex, now) }
            .sortedWith(FUSED_ORDER)
            .take(request.maxCandidates)
            .map(FusedCandidate::toPublicCandidate)
    }

    private fun groupByOutcome(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        sourceCandidates: List<CompletionSourceCandidates>,
        rankingContext: TerminalCompletionRankingContext,
    ): Map<Any, MutableOutcomeAggregate> {
        val aggregates = LinkedHashMap<Any, MutableOutcomeAggregate>()
        for (sourceResult in sourceCandidates) {
            val locallyRanked = sourceResult.candidates.sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            val bestByOutcome = LinkedHashMap<Any, RankedContribution>()
            for (candidateIndex in locallyRanked.indices) {
                val candidate = locallyRanked[candidateIndex]
                val resolved = outcomeResolver.resolve(request, candidate, context)
                val key: Any = resolved?.groupKey ?: FallbackOutcomeKey(candidate)
                val contribution =
                    RankedContribution(
                        candidate = candidate,
                        resolved = resolved,
                        sourceIndex = sourceResult.sourceIndex,
                        candidateIndex = candidateIndex,
                        localRank = candidateIndex + 1,
                        sourcePrior = GlobalFusionScoring.sourcePrior(sourceResult.priority),
                        contextAdjustment = rankingContext.priorityAdjustment(candidate),
                    )
                bestByOutcome.putIfAbsent(key, contribution)
            }
            for ((key, contribution) in bestByOutcome) {
                aggregates.getOrPut(key, ::MutableOutcomeAggregate).add(contribution)
            }
        }
        return aggregates
    }

    private class MutableOutcomeAggregate {
        private val contributions = ArrayList<RankedContribution>(2)

        fun add(contribution: RankedContribution) {
            contributions += contribution
        }

        fun finish(
            request: TerminalCompletionRequest,
            learnedIndex: LearnedCompletionEvidenceIndex,
            now: Long,
        ): FusedCandidate {
            val representative = contributions.minWith(REPRESENTATIVE_ORDER)
            var score = 0L
            var strongestContext = Int.MIN_VALUE
            for (contribution in contributions) {
                score += GlobalFusionScoring.reciprocalRank(contribution.localRank)
                score += contribution.sourcePrior
                score += learnedIndex.providerAdjustment(contribution.candidate, request)
                strongestContext = maxOf(strongestContext, contribution.contextAdjustment)
            }
            score += strongestContext
            representative.resolved?.let { resolved ->
                score += learnedIndex.exactAdjustment(resolved.learnedKey, request, now)
                score += learnedIndex.shapeAdjustment(resolved.shape, request)
            }
            return FusedCandidate(
                candidate = representative.candidate,
                score = score,
                strongestContext = strongestContext,
                bestLocalRank = contributions.minOf { it.localRank },
                sourceIndex = representative.sourceIndex,
                candidateIndex = representative.candidateIndex,
            )
        }
    }

    private data class RankedContribution(
        val candidate: TerminalCompletionCandidate,
        val resolved: ResolvedCompletionOutcome?,
        val sourceIndex: Int,
        val candidateIndex: Int,
        val localRank: Int,
        val sourcePrior: Int,
        val contextAdjustment: Int,
    ) {
        val replacementLength: Int = candidate.replacementEndOffset - candidate.replacementStartOffset
    }

    private data class FallbackOutcomeKey(
        val replacementText: String,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
    ) {
        constructor(candidate: TerminalCompletionCandidate) : this(
            candidate.replacementText,
            candidate.replacementStartOffset,
            candidate.replacementEndOffset,
        )
    }

    private data class FusedCandidate(
        val candidate: TerminalCompletionCandidate,
        val score: Long,
        val strongestContext: Int,
        val bestLocalRank: Int,
        val sourceIndex: Int,
        val candidateIndex: Int,
    ) {
        fun toPublicCandidate(): TerminalCompletionCandidate =
            candidate.copy(score = score.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }

    private companion object {
        private val REPRESENTATIVE_ORDER =
            compareByDescending<RankedContribution> { it.contextAdjustment }
                .thenBy { it.replacementLength }
                .thenByDescending { it.sourcePrior }
                .thenBy { it.localRank }
                .thenBy { it.sourceIndex }
                .thenBy { it.candidateIndex }

        private val FUSED_ORDER =
            compareByDescending<FusedCandidate> { it.score }
                .thenByDescending { it.strongestContext }
                .thenBy { it.bestLocalRank }
                .thenBy { it.candidate.displayText }
                .thenBy { it.candidate.replacementText }
                .thenBy { it.sourceIndex }
                .thenBy { it.candidateIndex }
    }
}
