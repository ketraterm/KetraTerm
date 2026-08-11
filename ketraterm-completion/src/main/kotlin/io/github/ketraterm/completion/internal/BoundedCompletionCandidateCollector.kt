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

/** Fixed-capacity top-candidate collector that avoids materializing candidates below its score cutoff. */
internal class BoundedCompletionCandidateCollector(
    private val capacity: Int,
) {
    private val candidates = ArrayList<TerminalCompletionCandidate>(capacity)
    private var worstIndex = -1
    private var worstScore = Int.MIN_VALUE

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    /** Returns whether a candidate with [score] can beat or tie the retained cutoff. */
    fun shouldMaterialize(score: Int): Boolean = candidates.size < capacity || score >= worstScore

    /** Retains [candidate] when it belongs to the comparator-defined top [capacity]. */
    fun offer(candidate: TerminalCompletionCandidate) {
        if (candidates.size < capacity) {
            candidates += candidate
            if (worstIndex < 0 || TERMINAL_COMPLETION_CANDIDATE_ORDER.compare(candidate, candidates[worstIndex]) > 0) {
                worstIndex = candidates.lastIndex
                worstScore = candidate.score
            }
            return
        }
        if (TERMINAL_COMPLETION_CANDIDATE_ORDER.compare(candidate, candidates[worstIndex]) >= 0) return
        candidates[worstIndex] = candidate
        findWorst()
    }

    /** Returns the retained mutable storage sorted best-first; the collector must not be used afterward. */
    fun finish(): List<TerminalCompletionCandidate> {
        candidates.sortWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
        return candidates
    }

    private fun findWorst() {
        var index = 1
        var currentWorst = 0
        while (index < candidates.size) {
            if (TERMINAL_COMPLETION_CANDIDATE_ORDER.compare(candidates[index], candidates[currentWorst]) > 0) {
                currentWorst = index
            }
            index++
        }
        worstIndex = currentWorst
        worstScore = candidates[currentWorst].score
    }
}
