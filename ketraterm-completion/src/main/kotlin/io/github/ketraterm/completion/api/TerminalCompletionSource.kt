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
 * Pure completion source contract for one bounded provider such as static
 * command specs, session MRU, indexed history, path completion, or IDE context.
 */
fun interface TerminalCompletionSource {
    /**
     * Returns candidates produced by this source for [request].
     *
     * Implementations must be deterministic for a stable source snapshot and
     * must not perform shell I/O, UI work, disk I/O, or network I/O. Expensive
     * sources should maintain ready in-memory indexes outside this callback.
     *
     * @param request command-line completion context.
     * @return ordered candidates from this source.
     */
    fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate>
}

/**
 * Source registration consumed by merged completion engines.
 *
 * @property source completion source to query.
 * @property priority small cold-start prior added to this source's reciprocal-rank
 * contribution. The merged engine clamps values to `[-20, 20]`.
 */
data class TerminalCompletionSourceEntry
    @JvmOverloads
    constructor(
        val source: TerminalCompletionSource,
        val priority: Int = 0,
    )
