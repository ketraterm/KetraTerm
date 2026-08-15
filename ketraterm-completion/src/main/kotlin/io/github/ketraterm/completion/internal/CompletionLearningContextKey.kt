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

import io.github.ketraterm.completion.api.TerminalCompletionRequest

/** Canonical host context shared by every completion-learning signal. */
internal data class CompletionLearningContextKey(
    val profileId: String?,
    val workingDirectoryUri: String?,
) {
    /** Returns the signal-specific score boost represented by this matched context. */
    fun boost(
        profile: Int,
        directory: Int,
    ): Int =
        (if (profileId == null) 0 else profile) +
            (if (workingDirectoryUri == null) 0 else directory)

    /**
     * Resolves the first available value from most to least specific context.
     *
     * Directory-only evidence intentionally precedes profile-only evidence,
     * preserving the existing learning policy.
     */
    fun <T : Any> mostSpecific(lookup: (CompletionLearningContextKey) -> T?): CompletionLearningContextMatch<T>? {
        lookup(this)?.let { return CompletionLearningContextMatch(this, it) }

        if (profileId != null && workingDirectoryUri != null) {
            val directoryOnly = CompletionLearningContextKey(null, workingDirectoryUri)
            lookup(directoryOnly)?.let { return CompletionLearningContextMatch(directoryOnly, it) }
            val profileOnly = CompletionLearningContextKey(profileId, null)
            lookup(profileOnly)?.let { return CompletionLearningContextMatch(profileOnly, it) }
        }
        if (profileId != null || workingDirectoryUri != null) {
            lookup(GLOBAL)?.let { return CompletionLearningContextMatch(GLOBAL, it) }
        }
        return null
    }

    companion object {
        private val GLOBAL = CompletionLearningContextKey(null, null)

        fun of(
            profileId: String?,
            workingDirectoryUri: String?,
        ): CompletionLearningContextKey =
            if (profileId == null && workingDirectoryUri == null) {
                GLOBAL
            } else {
                CompletionLearningContextKey(
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri?.let(::canonicalizeWorkingDirectoryUri),
                )
            }

        fun from(request: TerminalCompletionRequest): CompletionLearningContextKey = of(request.profileId, request.workingDirectoryUri)
    }
}

/** Value selected together with the context specificity that selected it. */
internal data class CompletionLearningContextMatch<T : Any>(
    val context: CompletionLearningContextKey,
    val value: T,
)
