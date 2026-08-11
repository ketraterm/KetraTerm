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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalFuzzyPathEntry
import io.github.ketraterm.completion.commandline.ContextAwareCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCompletionActivePosition
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.commandline.resolveCompletionContext
import io.github.ketraterm.completion.internal.BoundedCompletionCandidateCollector
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/**
 * Completes bounded host-indexed paths using case-insensitive fuzzy matching.
 *
 * The host supplies a ready snapshot whose entries are already relative to the
 * request working directory. This source never indexes files or performs I/O.
 * It normally complements direct directory completion by requiring a non-empty
 * prefix, while small context-specific providers may opt into empty-prefix matching.
 */
internal class FuzzyPathCompletionSource(
    private val sourceId: String,
    private val entriesProvider: () -> List<TerminalFuzzyPathEntry>,
    private val requiresNonEmptyPrefix: Boolean,
    private val allowedCommandNames: Set<String>,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
) : ContextAwareCompletionSource {
    override val commandSpecs = commandSpecs.toList()

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(request, request.resolveCompletionContext(commandSpecs))

    override fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): List<TerminalCompletionCandidate> {
        if (request.workingDirectoryUri == null) return emptyList()
        if (allowedCommandNames.isNotEmpty() && context.currentCommand?.name !in allowedCommandNames) {
            return emptyList()
        }
        val prefix = context.activePrefix
        if (!allowsPathCompletion(context.activePosition, context.expectedPathKind, prefix) ||
            (requiresNonEmptyPrefix && prefix.isEmpty())
        ) {
            return emptyList()
        }

        val pathSeparator = if (prefix.contains('\\')) '\\' else '/'
        val candidates = BoundedCompletionCandidateCollector(request.maxCandidates)
        var orderIndex = 0
        for (entry in entriesProvider()) {
            if (!context.expectedPathKind.acceptsPathEntry(entry.isDirectory)) continue
            if (!context.expectedHiddenPathPolicy.acceptsPath(entry.path, prefix)) continue
            val fuzzyScore = fuzzyScore(entry.path, prefix) ?: continue
            val rawPath = if (pathSeparator == '\\') entry.path.replace('/', '\\') else entry.path
            val rawReplacement = if (entry.isDirectory) rawPath + pathSeparator else rawPath
            if (!ShellReplacementText.canEncode(rawReplacement, context.activeTokenQuote, request.shellCapabilities.quoting)) {
                continue
            }
            val candidateScore = FUZZY_PATH_BASE_SCORE + fuzzyScore - orderIndex++
            if (!candidates.shouldMaterialize(candidateScore)) continue
            val replacementText =
                ShellReplacementText.encode(
                    value = rawReplacement,
                    activeTokenQuote = context.activeTokenQuote,
                    policy = request.shellCapabilities.quoting,
                ) ?: continue
            candidates.offer(
                TerminalCompletionCandidate(
                    replacementText = replacementText,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    displayText = entry.path + if (entry.isDirectory) "/" else "",
                    detail = entry.detail ?: if (entry.isDirectory) "project directory" else "project file",
                    source = sourceId,
                    kind = TerminalCompletionCandidateKind.PATH,
                    score = candidateScore,
                ),
            )
        }
        return candidates.finish()
    }

    private fun allowsPathCompletion(
        activePosition: TerminalCompletionActivePosition,
        expectedPathKind: TerminalPathArgumentKind,
        prefix: String,
    ): Boolean {
        if (activePosition == TerminalCompletionActivePosition.OPERATOR ||
            activePosition == TerminalCompletionActivePosition.OPTION_NAME
        ) {
            return false
        }
        if (activePosition == TerminalCompletionActivePosition.COMMAND) return isPathLike(prefix)
        return expectedPathKind != TerminalPathArgumentKind.NONE || isPathLike(prefix)
    }

    private fun fuzzyScore(
        path: String,
        prefix: String,
    ): Int? {
        val fileNameStart = path.lastIndexOf('/') + 1
        val fileNameLength = path.length - fileNameStart
        return when {
            path.regionMatches(fileNameStart, prefix, 0, prefix.length, ignoreCase = true) ->
                EXACT_FILE_NAME_PREFIX_SCORE - fileNameLength
            path.startsWith(prefix, ignoreCase = true) -> EXACT_PATH_PREFIX_SCORE - path.length
            else -> {
                val fileNameMatch = subsequenceScore(path, prefix, fileNameStart)
                if (fileNameMatch >= 0) {
                    FILE_NAME_SUBSEQUENCE_SCORE + fileNameMatch
                } else {
                    val pathMatch = subsequenceScore(path, prefix)
                    if (pathMatch >= 0) PATH_SUBSEQUENCE_SCORE + pathMatch else null
                }
            }
        }
    }

    private fun subsequenceScore(
        value: String,
        query: String,
        startIndex: Int = 0,
    ): Int {
        var valueIndex = startIndex
        var queryIndex = 0
        var firstMatch = -1
        var previousMatch = -1
        var gaps = 0
        while (valueIndex < value.length && queryIndex < query.length) {
            if (value[valueIndex].equals(query[queryIndex], ignoreCase = true)) {
                if (firstMatch < 0) firstMatch = valueIndex
                if (previousMatch >= 0) gaps += valueIndex - previousMatch - 1
                previousMatch = valueIndex
                queryIndex++
            }
            valueIndex++
        }
        if (queryIndex != query.length) return -1
        return CONTIGUOUS_MATCH_SCORE -
            (firstMatch - startIndex) -
            gaps * GAP_PENALTY -
            (value.length - startIndex - query.length)
    }

    private companion object {
        private const val FUZZY_PATH_BASE_SCORE = 200
        private const val EXACT_FILE_NAME_PREFIX_SCORE = 180
        private const val EXACT_PATH_PREFIX_SCORE = 150
        private const val FILE_NAME_SUBSEQUENCE_SCORE = 120
        private const val PATH_SUBSEQUENCE_SCORE = 80
        private const val CONTIGUOUS_MATCH_SCORE = 60
        private const val GAP_PENALTY = 3
    }
}
