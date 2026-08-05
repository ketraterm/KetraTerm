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

import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri

/** Source-local scoring policy shared by session command and observed-token indexes. */
internal object SessionCompletionRelevance {
    fun score(
        baseScore: Int,
        useCount: Int,
        lastUsedSequence: Long,
        profileId: String?,
        workingDirectoryUri: String?,
        request: TerminalCompletionRequest,
    ): Int {
        var score = baseScore
        score += minOf(useCount, MAX_USE_COUNT_SCORE_UNITS) * USE_COUNT_SCORE
        score += minOf(lastUsedSequence, MAX_RECENCY_SCORE).toInt()
        if (profileId != null && profileId == request.profileId) score += PROFILE_MATCH_SCORE
        if (workingDirectoryUri != null &&
            request.workingDirectoryUri != null &&
            canonicalizeWorkingDirectoryUri(workingDirectoryUri) ==
            canonicalizeWorkingDirectoryUri(request.workingDirectoryUri)
        ) {
            score += WORKING_DIRECTORY_MATCH_SCORE
        }
        return score
    }

    private const val USE_COUNT_SCORE = 30
    private const val MAX_USE_COUNT_SCORE_UNITS = 20
    private const val MAX_RECENCY_SCORE = 100L
    private const val PROFILE_MATCH_SCORE = 60
    private const val WORKING_DIRECTORY_MATCH_SCORE = 90
}
