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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.ranking.LearnedCompletionEvidenceIndex
import io.github.ketraterm.completion.ranking.TerminalCompletionOutcomeKeyResolver
import io.github.ketraterm.completion.source.LearnedHistoryCandidateIndex

/** Compiles and shares derived learning indexes for one current snapshot. */
internal class CompletionLearningIndexCache {
    private val lock = Any()

    @Volatile
    private var current: SnapshotCompilation? = null

    fun indexesFor(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
        commandSpecs: List<TerminalCommandSpec>,
    ): CompletionLearningIndexes {
        val compilation = current?.takeIf { it.snapshot === snapshot } ?: compilationFor(snapshot)
        return compilation.indexesFor(shellSyntax, commandSpecs)
    }

    private fun compilationFor(snapshot: TerminalCommandCompletionStatsSnapshot): SnapshotCompilation =
        synchronized(lock) {
            current?.takeIf { it.snapshot === snapshot }
                ?: SnapshotCompilation(snapshot).also { current = it }
        }

    private class SnapshotCompilation(
        val snapshot: TerminalCommandCompletionStatsSnapshot,
    ) {
        private val lock = Any()

        @Volatile
        private var indexes: Map<CompilationKey, CompletionLearningIndexes> = emptyMap()

        fun indexesFor(
            shellSyntax: TerminalShellSyntax,
            commandSpecs: List<TerminalCommandSpec>,
        ): CompletionLearningIndexes {
            val key = CompilationKey(shellSyntax, commandSpecs)
            indexes[key]?.let { return it }
            return synchronized(lock) {
                indexes[key]
                    ?: buildIndexes(shellSyntax, commandSpecs).also { built ->
                        indexes = indexes + (key to built)
                    }
            }
        }

        private fun buildIndexes(
            shellSyntax: TerminalShellSyntax,
            commandSpecs: List<TerminalCommandSpec>,
        ): CompletionLearningIndexes =
            CompletionLearningIndexes(
                evidence =
                    LearnedCompletionEvidenceIndex.build(
                        snapshot = snapshot,
                        shellSyntax = shellSyntax,
                        outcomeResolver = TerminalCompletionOutcomeKeyResolver(commandSpecs),
                    ),
                history = LearnedHistoryCandidateIndex.build(snapshot, shellSyntax),
            )
    }

    private data class CompilationKey(
        val shellSyntax: TerminalShellSyntax,
        val commandSpecs: List<TerminalCommandSpec>,
    )
}

/** All derived learning data for one shell syntax and command-spec vocabulary. */
internal data class CompletionLearningIndexes(
    val evidence: LearnedCompletionEvidenceIndex,
    val history: LearnedHistoryCandidateIndex,
)
