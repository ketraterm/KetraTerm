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
package io.github.ketraterm.completion.commandline

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSource

/** Internal fast path for sources that can consume a shared lexical context. */
internal interface ContextAwareCompletionSource : TerminalCompletionSource {
    fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate>
}

/**
 * Internal fast path for sources that accept a bounded candidate surplus before final ranking.
 */
internal interface CandidateCollectingCompletionSource : ContextAwareCompletionSource {
    fun collectCandidates(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
        collectionLimit: Int,
    ): List<TerminalCompletionCandidate>
}

internal fun TerminalCompletionSource.complete(
    request: TerminalCompletionRequest,
    commandLineContext: TerminalCommandLineContext,
): List<TerminalCompletionCandidate> =
    when (this) {
        is ContextAwareCompletionSource -> complete(request, commandLineContext)
        else -> complete(request)
    }

internal fun TerminalCompletionSource.collectCandidates(
    request: TerminalCompletionRequest,
    commandLineContext: TerminalCommandLineContext,
    collectionLimit: Int,
): List<TerminalCompletionCandidate> =
    when (this) {
        is CandidateCollectingCompletionSource -> collectCandidates(request, commandLineContext, collectionLimit)
        else -> {
            val collectionRequest =
                if (request.maxCandidates == collectionLimit) {
                    request
                } else {
                    request.copy(maxCandidates = collectionLimit)
                }
            complete(collectionRequest, commandLineContext)
        }
    }
