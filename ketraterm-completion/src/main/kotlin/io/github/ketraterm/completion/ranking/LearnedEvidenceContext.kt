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

import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats

/** Profile and directory dimensions shared by every learned evidence family. */
internal data class LearnedEvidenceContext(
    val profileId: String?,
    val workingDirectoryUri: String?,
)

/** Selects the single most-specific request context and computes its bounded boost. */
internal object LearnedEvidenceContextSelector {
    fun <T> mostSpecific(
        rows: List<T>,
        request: TerminalCompletionRequest,
        context: (T) -> LearnedEvidenceContext,
    ): List<T> {
        var bestSpecificity = NO_MATCH
        val selected = ArrayList<T>()
        for (row in rows) {
            val specificity = specificity(context(row), request)
            if (specificity == NO_MATCH) continue
            when {
                specificity > bestSpecificity -> {
                    bestSpecificity = specificity
                    selected.clear()
                    selected += row
                }
                specificity == bestSpecificity -> selected += row
            }
        }
        return selected
    }

    fun boost(
        context: LearnedEvidenceContext,
        request: TerminalCompletionRequest,
        profile: Int,
        directory: Int,
    ): Int {
        var score = 0
        if (context.profileId != null && context.profileId == request.profileId) score += profile
        if (context.workingDirectoryUri.matches(request.workingDirectoryUri)) score += directory
        return score
    }

    private fun specificity(
        context: LearnedEvidenceContext,
        request: TerminalCompletionRequest,
    ): Int {
        var value = 0
        if (context.profileId != null) {
            if (context.profileId != request.profileId) return NO_MATCH
            value += PROFILE_SPECIFICITY
        }
        if (context.workingDirectoryUri != null) {
            if (!context.workingDirectoryUri.matches(request.workingDirectoryUri)) return NO_MATCH
            value += DIRECTORY_SPECIFICITY
        }
        return value
    }

    private fun String?.matches(other: String?): Boolean =
        this != null && other != null && canonicalizeWorkingDirectoryUri(this) == canonicalizeWorkingDirectoryUri(other)

    private const val NO_MATCH = -1
    private const val PROFILE_SPECIFICITY = 1
    private const val DIRECTORY_SPECIFICITY = 2
}

internal fun TerminalCommandCompletionStats.evidenceContext(): LearnedEvidenceContext =
    LearnedEvidenceContext(profileId, workingDirectoryUri)

internal fun TerminalCommandShapeStats.evidenceContext(): LearnedEvidenceContext = LearnedEvidenceContext(profileId, workingDirectoryUri)

internal fun TerminalCompletionFeedbackStats.evidenceContext(): LearnedEvidenceContext =
    LearnedEvidenceContext(profileId, workingDirectoryUri)
