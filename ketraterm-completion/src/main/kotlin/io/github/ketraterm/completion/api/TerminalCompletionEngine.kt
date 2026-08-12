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

/**
 * Pure completion engine contract.
 *
 * Engines merge and rank one or more [TerminalCompletionSource] instances for a
 * host request. Implementations are deterministic and perform no shell, UI,
 * disk, or network work.
 */
fun interface TerminalCompletionEngine {
    /**
     * Returns a bounded, best-first candidate list for [request].
     *
     * Implementations must not mutate terminal state, block on shell I/O, or
     * perform UI work. Slow sources should maintain a ready in-memory index and
     * answer from that index.
     *
     * @param request command-line completion context.
     * @return ordered completion candidates.
     */
    suspend fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate>

    companion object {
        /**
         * Engine that returns no candidates.
         */
        @JvmField
        val NONE: TerminalCompletionEngine = TerminalCompletionEngine { emptyList() }
    }
}
