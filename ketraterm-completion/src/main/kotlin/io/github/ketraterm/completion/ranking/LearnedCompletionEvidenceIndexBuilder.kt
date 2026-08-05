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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot

/** Builds immutable lookup indexes from one published learned-statistics snapshot. */
internal object LearnedCompletionEvidenceIndexBuilder {
    fun build(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
        outcomeResolver: TerminalCompletionOutcomeKeyResolver,
    ): LearnedCompletionEvidenceIndex {
        val exactRows = HashMap<LearnedCompletionOutcomeKey, MutableList<TerminalCommandCompletionStats>>()
        for (row in snapshot.commandStats) {
            val tokens = TerminalCommandLineTokenizer.parse(row.commandLine, row.commandLine.length, shellSyntax).tokens
            if (tokens.isEmpty()) continue
            outcomeResolver.learnedKey(row.commandLine, shellSyntax, NO_PATH_TOKEN, pathAware = false)?.let { key ->
                exactRows.getOrPut(key, ::ArrayList).add(row)
            }
            for (tokenIndex in tokens.indices) {
                outcomeResolver.learnedKey(row.commandLine, shellSyntax, tokenIndex, pathAware = true)?.let { key ->
                    exactRows.getOrPut(key, ::ArrayList).add(row)
                }
            }
        }
        return LearnedCompletionEvidenceIndex(
            exactRows = exactRows.mapValues { (_, rows) -> rows.toList() },
            shapeIndex = ShapeRankingSnapshotIndex.from(snapshot.shapeStats),
            feedbackIndex = FeedbackRankingSnapshotIndex.from(snapshot.feedbackStats),
        )
    }

    private const val NO_PATH_TOKEN = -1
}
