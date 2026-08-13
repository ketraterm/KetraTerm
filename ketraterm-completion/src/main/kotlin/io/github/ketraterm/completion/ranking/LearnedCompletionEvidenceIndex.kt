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
import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.model.*

/** Immutable direct-lookup view of every learned ranking signal in one snapshot. */
internal class LearnedCompletionEvidenceIndex private constructor(
    private val exactEvidence: Map<ExactEvidenceKey, LearnedEvidenceCounts>,
    private val shapeRows: Map<ShapeEvidenceKey, List<TerminalCommandShapeStats>>,
    private val providerEvidence: Map<ProviderEvidenceKey, LearnedEvidenceCounts>,
) {
    fun exactAdjustment(
        key: LearnedCompletionOutcomeKey,
        requestContext: CompletionLearningContextKey,
        nowEpochMillis: Long,
    ): Int {
        val match =
            requestContext.mostSpecific { context ->
                exactEvidence[ExactEvidenceKey(key, context)]
            } ?: return 0
        return LearnedEvidenceScoring.exact(
            counts = match.value,
            contextBoost = match.context.boost(profile = 20, directory = 30),
            nowEpochMillis = nowEpochMillis,
        )
    }

    fun shapeAdjustment(
        candidateShape: TerminalCommandLineShape?,
        requestContext: CompletionLearningContextKey,
    ): Int {
        if (candidateShape == null) return 0
        val match =
            requestContext.mostSpecific { context ->
                val rows = shapeRows[ShapeEvidenceKey(candidateShape.executable, context)] ?: return@mostSpecific null
                val matchingRows = rows.filter { it.shape.supports(candidateShape) }
                if (matchingRows.isEmpty()) null else LearnedEvidenceCounts.fromShapes(matchingRows)
            } ?: return 0
        return LearnedEvidenceScoring.shape(
            counts = match.value,
            contextBoost = match.context.boost(profile = 10, directory = 15),
        )
    }

    fun providerAdjustment(
        candidate: TerminalCompletionCandidate,
        requestContext: CompletionLearningContextKey,
    ): Int {
        val match =
            requestContext.mostSpecific { context ->
                providerEvidence[ProviderEvidenceKey(candidate.source, candidate.kind, context)]
            } ?: return 0
        return LearnedEvidenceScoring.provider(
            counts = match.value,
            contextBoost = match.context.boost(profile = 10, directory = 15),
        )
    }

    private fun TerminalCommandLineShape.supports(candidate: TerminalCommandLineShape): Boolean =
        executable == candidate.executable &&
            subcommands.startsWith(candidate.subcommands) &&
            optionNames.containsAll(candidate.optionNames) &&
            positionalArgumentCount >= candidate.positionalArgumentCount &&
            optionValueCount >= candidate.optionValueCount

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    companion object {
        fun build(
            snapshot: TerminalCommandCompletionStatsSnapshot,
            shellSyntax: TerminalShellSyntax,
            outcomeResolver: TerminalCompletionOutcomeKeyResolver,
        ): LearnedCompletionEvidenceIndex {
            val exactRows = HashMap<ExactEvidenceKey, MutableList<TerminalCommandCompletionStats>>()
            for (row in snapshot.commandStats) {
                val tokens = TerminalCommandLineTokenizer.parse(row.commandLine, row.commandLine.length, shellSyntax).tokens
                if (tokens.isEmpty()) continue
                val context = CompletionLearningContextKey.of(row.profileId, row.workingDirectoryUri)
                outcomeResolver.learnedKey(tokens, NO_PATH_TOKEN, pathAware = false)?.let { key ->
                    exactRows.getOrPut(ExactEvidenceKey(key, context), ::ArrayList).add(row)
                }
                for (tokenIndex in tokens.indices) {
                    outcomeResolver.learnedKey(tokens, tokenIndex, pathAware = true)?.let { key ->
                        exactRows.getOrPut(ExactEvidenceKey(key, context), ::ArrayList).add(row)
                    }
                }
            }

            val shapeRows = HashMap<ShapeEvidenceKey, MutableList<TerminalCommandShapeStats>>()
            for (row in snapshot.shapeStats) {
                val context = CompletionLearningContextKey.of(row.profileId, row.workingDirectoryUri)
                shapeRows.getOrPut(ShapeEvidenceKey(row.shape.executable, context), ::ArrayList).add(row)
            }

            val providerRows = HashMap<ProviderEvidenceKey, MutableList<TerminalCompletionFeedbackStats>>()
            for (row in snapshot.feedbackStats) {
                val context = CompletionLearningContextKey.of(row.profileId, row.workingDirectoryUri)
                val key = ProviderEvidenceKey(row.source, row.candidateKind, context)
                providerRows.getOrPut(key, ::ArrayList).add(row)
            }

            return LearnedCompletionEvidenceIndex(
                exactEvidence = exactRows.mapValues { (_, rows) -> LearnedEvidenceCounts.fromCommands(rows) },
                shapeRows = shapeRows.mapValues { (_, rows) -> rows.toList() },
                providerEvidence = providerRows.mapValues { (_, rows) -> LearnedEvidenceCounts.fromFeedback(rows) },
            )
        }

        private const val NO_PATH_TOKEN = -1
    }
}

private data class ExactEvidenceKey(
    val outcome: LearnedCompletionOutcomeKey,
    val context: CompletionLearningContextKey,
)

private data class ShapeEvidenceKey(
    val executable: String,
    val context: CompletionLearningContextKey,
)

private data class ProviderEvidenceKey(
    val source: String,
    val candidateKind: TerminalCompletionCandidateKind,
    val context: CompletionLearningContextKey,
)
