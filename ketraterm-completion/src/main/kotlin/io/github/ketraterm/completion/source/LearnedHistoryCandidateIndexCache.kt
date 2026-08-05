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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot

/** Thread-safe identity cache of learned-history candidate indexes by shell syntax. */
internal class LearnedHistoryCandidateIndexCache {
    private val lock = Any()

    @Volatile
    private var state = CacheState(snapshot = null, indexes = emptyMap())

    fun indexFor(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): LearnedHistoryCandidateIndex {
        val observed = state
        if (snapshot === observed.snapshot) observed.indexes[shellSyntax]?.let { return it }
        return synchronized(lock) {
            val current = state
            val indexes = if (snapshot === current.snapshot) current.indexes else emptyMap()
            indexes[shellSyntax]?.let { return@synchronized it }
            LearnedHistoryCandidateIndex.build(snapshot, shellSyntax).also { built ->
                state = CacheState(snapshot, indexes + (shellSyntax to built))
            }
        }
    }

    private data class CacheState(
        val snapshot: TerminalCommandCompletionStatsSnapshot?,
        val indexes: Map<TerminalShellSyntax, LearnedHistoryCandidateIndex>,
    )
}
