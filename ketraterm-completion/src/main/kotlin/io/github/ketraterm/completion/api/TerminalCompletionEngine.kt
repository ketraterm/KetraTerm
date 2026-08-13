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
package io.github.ketraterm.completion.api

import kotlinx.coroutines.flow.Flow

/**
 * Pure completion engine contract.
 *
 * Engines merge and rank one or more [TerminalCompletionSource] instances for a
 * host request. The engine owns no shell, UI, disk, or network infrastructure;
 * host-backed sources may perform bounded suspending work inside the request
 * scope.
 */
fun interface TerminalCompletionEngine {
    /**
     * Returns a cold progressive stream of best-first rankings for [request].
     *
     * Each emission contains the globally reranked union of every source that
     * has completed so far. Implementations must not mutate terminal state or
     * perform UI work. Cancelling collection must cancel all source work.
     *
     * @param request command-line completion context.
     * @return cold stream of ordered completion snapshots.
     */
    fun completions(request: TerminalCompletionRequest): Flow<List<TerminalCompletionCandidate>>
}
