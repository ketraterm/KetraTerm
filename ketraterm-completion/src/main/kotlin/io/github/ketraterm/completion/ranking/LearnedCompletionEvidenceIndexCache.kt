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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot

/** Thread-safe identity cache of per-shell learned evidence indexes. */
internal class LearnedCompletionEvidenceIndexCache(
    private val outcomeResolver: TerminalCompletionOutcomeKeyResolver,
) {
    private val lock = Any()

    @Volatile
    private var state = CacheState(snapshot = null, indexes = emptyMap())

    fun indexFor(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): LearnedCompletionEvidenceIndex {
        val observedState = state
        if (snapshot === observedState.snapshot) {
            observedState.indexes[shellSyntax]?.let { return it }
        }
        return synchronized(lock) {
            val lockedState = state
            val currentIndexes =
                if (snapshot === lockedState.snapshot) {
                    lockedState.indexes
                } else {
                    emptyMap()
                }
            currentIndexes[shellSyntax]?.let { return@synchronized it }
            LearnedCompletionEvidenceIndexBuilder
                .build(snapshot, shellSyntax, outcomeResolver)
                .also { built ->
                    state = CacheState(snapshot, currentIndexes + (shellSyntax to built))
                }
        }
    }

    /** Atomically published cache view; readers never observe a new identity with old indexes. */
    private data class CacheState(
        val snapshot: TerminalCommandCompletionStatsSnapshot?,
        val indexes: Map<TerminalShellSyntax, LearnedCompletionEvidenceIndex>,
    )
}
