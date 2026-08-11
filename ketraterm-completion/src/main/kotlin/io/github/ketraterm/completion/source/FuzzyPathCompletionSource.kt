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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.ContextAwareCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCompletionActivePosition
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.commandline.resolveCompletionContext
import io.github.ketraterm.completion.internal.BoundedCompletionCandidateCollector
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/**
 * Materializes already-matched host paths using terminal-safe semantics.
 *
 * The host supplies a ready snapshot whose entries are already relative to the
 * request working directory and relevance-ordered for the active prefix. This
 * source never indexes files, performs I/O, or repeats the host's fuzzy match.
 * It normally complements direct directory completion by requiring a non-empty
 * prefix, while small context-specific providers may opt into empty-prefix matching.
 */
internal class FuzzyPathCompletionSource(
    private val sourceId: String,
    private val entriesProvider: TerminalFuzzyPathProvider,
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
        for (entry in entriesProvider.entries(prefix)) {
            if (!context.expectedPathKind.acceptsPathEntry(entry.isDirectory)) continue
            if (!context.expectedHiddenPathPolicy.acceptsPath(entry.path, prefix)) continue
            val rawPath = if (pathSeparator == '\\') entry.path.replace('/', '\\') else entry.path
            val rawReplacement = if (entry.isDirectory) rawPath + pathSeparator else rawPath
            if (!ShellReplacementText.canEncode(rawReplacement, context.activeTokenQuote, request.shellCapabilities.quoting)) {
                continue
            }
            val candidateScore = FUZZY_PATH_BASE_SCORE - orderIndex++
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

    private companion object {
        private const val FUZZY_PATH_BASE_SCORE = 200
    }
}

/** Matches a ready static snapshot once for hosts without a queryable index. */
internal class SnapshotFuzzyPathProvider(
    private val entriesProvider: () -> List<TerminalFuzzyPathEntry>,
) : TerminalFuzzyPathProvider {
    override fun entries(prefix: String): List<TerminalFuzzyPathEntry> =
        entriesProvider()
            .mapNotNull { entry -> fuzzyScore(entry.path, prefix)?.let { score -> ScoredEntry(entry, score) } }
            .sortedWith(ENTRY_ORDER)
            .map(ScoredEntry::entry)

    private fun fuzzyScore(
        path: String,
        prefix: String,
    ): Int? {
        val fileNameStart = path.lastIndexOf('/') + 1
        return when {
            path.regionMatches(fileNameStart, prefix, 0, prefix.length, ignoreCase = true) -> 4_000 - path.length
            path.startsWith(prefix, ignoreCase = true) -> 3_000 - path.length
            else -> subsequenceScore(path, prefix, fileNameStart)?.plus(2_000) ?: subsequenceScore(path, prefix)
        }
    }

    private fun subsequenceScore(
        value: String,
        query: String,
        startIndex: Int = 0,
    ): Int? {
        var valueIndex = startIndex
        var queryIndex = 0
        var gaps = 0
        var previousMatch = startIndex - 1
        while (valueIndex < value.length && queryIndex < query.length) {
            if (value[valueIndex].equals(query[queryIndex], ignoreCase = true)) {
                gaps += valueIndex - previousMatch - 1
                previousMatch = valueIndex
                queryIndex++
            }
            valueIndex++
        }
        return if (queryIndex == query.length) 1_000 - gaps * 3 - value.length else null
    }

    private data class ScoredEntry(
        val entry: TerminalFuzzyPathEntry,
        val score: Int,
    )

    private companion object {
        private val ENTRY_ORDER =
            compareByDescending<ScoredEntry> { it.score }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.path }
                .thenBy { it.entry.path }
    }
}
