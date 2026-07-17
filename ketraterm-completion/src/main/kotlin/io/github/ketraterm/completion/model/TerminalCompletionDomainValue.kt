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
package io.github.ketraterm.completion.model

/**
 * Immutable host-provided value for one dynamic completion domain.
 *
 * Hosts publish bounded snapshots of these values from their own caches. The
 * shared engine performs prefix matching, replacement-range selection, shell
 * quoting, and ranking without invoking host I/O.
 *
 * @property value literal value inserted into the command line.
 * @property displayText popup label, independent from inserted text.
 * @property detail optional host-owned description.
 * @property scoreAdjustment bounded host relevance adjustment within a source.
 */
data class TerminalCompletionDomainValue
@JvmOverloads
constructor(
    val value: String,
    val displayText: String = value,
    val detail: String = "",
    val scoreAdjustment: Int = 0,
) {
    init {
        require(value.isNotBlank()) { "value must not be blank" }
        require(displayText.isNotBlank()) { "displayText must not be blank" }
        require(scoreAdjustment in MIN_SCORE_ADJUSTMENT..MAX_SCORE_ADJUSTMENT) {
            "scoreAdjustment must be in $MIN_SCORE_ADJUSTMENT..$MAX_SCORE_ADJUSTMENT, was $scoreAdjustment"
        }
    }

    companion object {
        /** Minimum host relevance adjustment accepted by the shared engine. */
        const val MIN_SCORE_ADJUSTMENT: Int = -1_000

        /** Maximum host relevance adjustment accepted by the shared engine. */
        const val MAX_SCORE_ADJUSTMENT: Int = 1_000
    }
}
