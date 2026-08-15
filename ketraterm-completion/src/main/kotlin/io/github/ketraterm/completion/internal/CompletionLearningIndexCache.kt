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
        private var entries: Array<CompilationEntry> = emptyArray()

        fun indexesFor(
            shellSyntax: TerminalShellSyntax,
            commandSpecs: List<TerminalCommandSpec>,
        ): CompletionLearningIndexes {
            val currentEntries = entries
            for (i in currentEntries.indices) {
                val entry = currentEntries[i]
                if (entry.shellSyntax == shellSyntax && (entry.commandSpecs === commandSpecs || entry.commandSpecs == commandSpecs)) {
                    return entry.indexes
                }
            }
            return synchronized(lock) {
                for (entry in entries) {
                    if (entry.shellSyntax == shellSyntax && (entry.commandSpecs === commandSpecs || entry.commandSpecs == commandSpecs)) {
                        return@synchronized entry.indexes
                    }
                }
                val built = buildIndexes(shellSyntax, commandSpecs)
                val newEntry = CompilationEntry(shellSyntax, commandSpecs, built)
                entries = entries + newEntry
                built
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

    private class CompilationEntry(
        val shellSyntax: TerminalShellSyntax,
        val commandSpecs: List<TerminalCommandSpec>,
        val indexes: CompletionLearningIndexes,
    )
}

/** All derived learning data for one shell syntax and command-spec vocabulary. */
internal data class CompletionLearningIndexes(
    val evidence: LearnedCompletionEvidenceIndex,
    val history: LearnedHistoryCandidateIndex,
)
