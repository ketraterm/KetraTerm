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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate

internal object TerminalCompletionCollectionBudget {
    private const val EXPANSION_FACTOR = 4L
    private const val MAX_EXTRA_CANDIDATES = 256L

    fun forFinalLimit(finalLimit: Int): Int {
        require(finalLimit > 0) { "finalLimit must be > 0, was $finalLimit" }
        val expandedLimit = finalLimit.toLong() * EXPANSION_FACTOR
        val boundedLimit = finalLimit.toLong() + MAX_EXTRA_CANDIDATES
        return minOf(expandedLimit, boundedLimit, Int.MAX_VALUE.toLong()).toInt()
    }
}

internal fun List<TerminalCompletionCandidate>.boundedTo(maxCandidates: Int): List<TerminalCompletionCandidate> {
    require(maxCandidates > 0) { "maxCandidates must be > 0, was $maxCandidates" }
    return if (size <= maxCandidates) this else subList(0, maxCandidates).toList()
}
