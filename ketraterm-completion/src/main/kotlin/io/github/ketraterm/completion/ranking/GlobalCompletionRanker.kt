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
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TerminalCompletionActivePosition
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** One source's bounded candidate surplus plus stable composition metadata. */
internal data class CompletionSourceCandidates(
    val sourceIndex: Int,
    val priority: Int,
    val candidates: List<TerminalCompletionCandidate>,
)

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
        val aggregates = groupByOutcome(request, context, sourceCandidates)
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
                        sourcePrior = sourceResult.priority.coerceIn(MIN_SOURCE_PRIOR, MAX_SOURCE_PRIOR),
                        contextAdjustment = semanticAdjustment(context, candidate),
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
                score += reciprocalRank(contribution.localRank)
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
        private fun reciprocalRank(localRank: Int): Long = RECIPROCAL_RANK_SCALE / (RECIPROCAL_RANK_OFFSET + localRank)

        private fun semanticAdjustment(
            context: TerminalCompletionContext,
            candidate: TerminalCompletionCandidate,
        ): Int =
            when (context.activePosition) {
                TerminalCompletionActivePosition.OPERATOR -> 0
                TerminalCompletionActivePosition.COMMAND ->
                    when (candidate.kind) {
                        TerminalCompletionCandidateKind.COMMAND -> STRONG_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.PATH -> WEAK_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
                        else -> 0
                    }
                TerminalCompletionActivePosition.SUBCOMMAND ->
                    when (candidate.kind) {
                        TerminalCompletionCandidateKind.SUBCOMMAND -> STRONG_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
                        TerminalCompletionCandidateKind.PATH -> PATH_CONTEXT_PENALTY
                        else -> 0
                    }
                TerminalCompletionActivePosition.OPTION_NAME ->
                    when (candidate.kind) {
                        TerminalCompletionCandidateKind.OPTION -> STRONG_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
                        TerminalCompletionCandidateKind.PATH -> PATH_CONTEXT_PENALTY
                        else -> 0
                    }
                TerminalCompletionActivePosition.OPTION_VALUE -> optionValueAdjustment(context, candidate)
                TerminalCompletionActivePosition.POSITIONAL_ARGUMENT -> positionalAdjustment(context, candidate)
            }

        private fun optionValueAdjustment(
            context: TerminalCompletionContext,
            candidate: TerminalCompletionCandidate,
        ): Int =
            when (candidate.kind) {
                TerminalCompletionCandidateKind.ARGUMENT ->
                    if (candidate.matchesExpectedDomain(context)) DOMAIN_CONTEXT_BOOST else STRONG_CONTEXT_BOOST
                TerminalCompletionCandidateKind.PATH ->
                    if (context.expectedPathKind == TerminalPathArgumentKind.NONE) PATH_CONTEXT_PENALTY else STRONG_CONTEXT_BOOST
                TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
                else -> 0
            }

        private fun positionalAdjustment(
            context: TerminalCompletionContext,
            candidate: TerminalCompletionCandidate,
        ): Int =
            when (candidate.kind) {
                TerminalCompletionCandidateKind.ARGUMENT ->
                    if (candidate.matchesExpectedDomain(context)) DOMAIN_CONTEXT_BOOST else MEDIUM_CONTEXT_BOOST
                TerminalCompletionCandidateKind.PATH ->
                    if (context.expectedPathKind == TerminalPathArgumentKind.NONE) 0 else STRONG_CONTEXT_BOOST
                TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
                TerminalCompletionCandidateKind.SUBCOMMAND -> PATH_CONTEXT_PENALTY
                else -> 0
            }

        private fun TerminalCompletionCandidate.matchesExpectedDomain(context: TerminalCompletionContext): Boolean =
            context.expectedValueDomain != TerminalCompletionValueDomain.NONE && valueDomain == context.expectedValueDomain

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

        private const val MIN_SOURCE_PRIOR = -20
        private const val MAX_SOURCE_PRIOR = 20
        private const val RECIPROCAL_RANK_SCALE = 10_000L
        private const val RECIPROCAL_RANK_OFFSET = 60L
        private const val DOMAIN_CONTEXT_BOOST = 320
        private const val STRONG_CONTEXT_BOOST = 160
        private const val MEDIUM_CONTEXT_BOOST = 80
        private const val WEAK_CONTEXT_BOOST = 40
        private const val HISTORY_CONTEXT_PENALTY = -40
        private const val PATH_CONTEXT_PENALTY = -80
    }
}
