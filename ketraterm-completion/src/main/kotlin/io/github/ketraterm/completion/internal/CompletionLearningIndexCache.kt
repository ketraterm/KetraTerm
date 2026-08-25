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
package io.github.ketraterm.completion.internal

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.ranking.LearnedCompletionEvidenceIndex
import io.github.ketraterm.completion.ranking.TerminalCompletionOutcomeKeyResolver
import io.github.ketraterm.completion.source.LearnedHistoryCandidateIndex

/** Compiles and shares derived learning indexes for one current snapshot. */
internal class CompletionLearningIndexCache {
    private val lock = Any()
    private var cachedSnapshot: TerminalCommandCompletionStatsSnapshot? = null
    private val indexesBySyntax = arrayOfNulls<CompletionLearningIndexes>(TerminalShellSyntax.entries.size)

    fun indexesFor(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): CompletionLearningIndexes =
        synchronized(lock) {
            if (cachedSnapshot !== snapshot) {
                cachedSnapshot = snapshot
                indexesBySyntax.fill(null)
            }
            val syntaxIndex = shellSyntax.ordinal
            indexesBySyntax[syntaxIndex]
                ?: buildIndexes(snapshot, shellSyntax).also { indexesBySyntax[syntaxIndex] = it }
        }

    private fun buildIndexes(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): CompletionLearningIndexes {
        val parsedRows = ArrayList<ParsedLearnedStatsRow>(snapshot.commandStats.size)
        for (stats in snapshot.commandStats) {
            val line = TerminalCommandLineTokenizer.parse(stats.commandLine, stats.commandLine.length, shellSyntax)
            parsedRows += ParsedLearnedStatsRow(stats, line)
        }
        return CompletionLearningIndexes(
            evidence = LearnedCompletionEvidenceIndex.build(parsedRows, LEARNED_KEY_RESOLVER),
            history = LearnedHistoryCandidateIndex.build(parsedRows),
        )
    }

    private companion object {
        private val LEARNED_KEY_RESOLVER = TerminalCompletionOutcomeKeyResolver()
    }
}

/** All derived learning data for one shell syntax. */
internal class CompletionLearningIndexes(
    val evidence: LearnedCompletionEvidenceIndex,
    val history: LearnedHistoryCandidateIndex,
)

/** One learned row tokenized for both derived indexes. */
internal class ParsedLearnedStatsRow(
    val stats: TerminalCommandCompletionStats,
    val lineContext: TerminalCommandLineContext,
)
