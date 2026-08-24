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

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot

/** Immutable direct-lookup view of exact command learning in one snapshot. */
internal class LearnedCompletionEvidenceIndex private constructor(
    private val exactEvidence: Map<ExactEvidenceKey, LearnedEvidenceCounts>,
) {
    fun adjustment(
        outcome: ResolvedCompletionOutcome?,
        requestContext: CompletionLearningContextKey,
        nowEpochMillis: Long,
    ): Int = outcome?.let { exactAdjustment(it.learnedKey, requestContext, nowEpochMillis) } ?: 0

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
            return LearnedCompletionEvidenceIndex(
                exactEvidence = exactRows.mapValues { (_, rows) -> LearnedEvidenceCounts.fromCommands(rows) },
            )
        }

        private const val NO_PATH_TOKEN = -1
    }
}

private data class ExactEvidenceKey(
    val outcome: LearnedCompletionOutcomeKey,
    val context: CompletionLearningContextKey,
)
