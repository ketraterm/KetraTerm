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

/** Stable reciprocal-rank and source-prior policy for global evidence fusion. */
internal object GlobalFusionScoring {
    fun reciprocalRank(localRank: Int): Long = RECIPROCAL_RANK_SCALE / (RECIPROCAL_RANK_OFFSET + localRank)

    fun sourcePrior(priority: Int): Int = priority.coerceIn(MIN_SOURCE_PRIOR, MAX_SOURCE_PRIOR)

    private const val MIN_SOURCE_PRIOR = -20
    private const val MAX_SOURCE_PRIOR = 20
    private const val RECIPROCAL_RANK_SCALE = 10_000L
    private const val RECIPROCAL_RANK_OFFSET = 60L
}
