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
import io.github.ketraterm.completion.commandline.ContextAwareCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.TerminalCompletionContextResolver
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/**
 * Pure dynamic-domain source backed by a host-published immutable snapshot.
 *
 * The source performs no I/O and does not retain provider results. The host is
 * responsible for publishing bounded snapshots and for making
 * [valuesProvider] safe to invoke from the completion caller's thread.
 *
 * @property domain command-spec value domain served by this source.
 * @property sourceId stable candidate-source identifier used by feedback ranking.
 * @property valuesProvider provider of the latest ready in-memory snapshot.
 * @param commandSpecs command specifications used to resolve argument context;
 * the list is defensively copied during construction.
 * @throws IllegalArgumentException if [domain] is
 * [TerminalCompletionValueDomain.NONE] or [sourceId] is blank.
 */
internal class ValueDomainCompletionSource(
    private val domain: TerminalCompletionValueDomain,
    private val sourceId: String,
    private val valuesProvider: () -> List<TerminalCompletionDomainValue>,
    private val allowedCommandNames: Set<String>,
    commandSpecs: List<TerminalCommandSpec>,
) : ContextAwareCompletionSource {
    private val commandSpecs = commandSpecs.toList()

    init {
        require(domain != TerminalCompletionValueDomain.NONE) { "domain must not be NONE" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(allowedCommandNames.none(String::isBlank)) { "allowedCommandNames must not contain blank values" }
    }

    /**
     * Resolves command context and returns candidates for this source's domain.
     *
     * @param request immutable completion request.
     * @return ranked candidates bounded by
     * [TerminalCompletionRequest.maxCandidates], or an empty list when the
     * cursor does not expect [domain].
     */
    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(
                request.commandLine,
                request.cursorOffset,
                request.shellCapabilities.syntax,
            ),
        )

    /**
     * Returns candidates using an already-tokenized command-line context.
     *
     * @param request immutable completion request.
     * @param commandLineContext tokenized context corresponding to [request].
     * @return ranked candidates bounded by
     * [TerminalCompletionRequest.maxCandidates], or an empty list when the
     * cursor does not expect [domain].
     */
    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = request.commandLine,
                lineContext = commandLineContext,
                commandSpecs = commandSpecs,
            )
        if (context.expectedValueDomain != domain ||
            (allowedCommandNames.isNotEmpty() && context.currentCommand?.name !in allowedCommandNames)
        ) {
            return emptyList()
        }

        val prefix = context.activePrefix
        val values = valuesProvider()
        if (values.isEmpty()) return emptyList()
        val candidates = ArrayList<TerminalCompletionCandidate>(minOf(values.size, request.maxCandidates))
        for (index in values.indices) {
            val value = values[index]
            if (!matchesCompletablePrefix(value.value, prefix)) continue
            val replacement =
                ShellReplacementText.encode(
                    value = value.value,
                    activeTokenQuote = context.activeTokenQuote,
                    policy = request.shellCapabilities.quoting,
                ) ?: continue
            candidates +=
                TerminalCompletionCandidate(
                    replacementText = replacement,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    displayText = value.displayText,
                    detail = value.detail,
                    source = sourceId,
                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                    score = score(value, prefix, index),
                    valueDomain = domain,
                )
        }
        return candidates.sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER).take(request.maxCandidates)
    }

    private companion object {
        private const val BASE_SCORE = 260

        private fun matchesCompletablePrefix(
            value: String,
            prefix: String,
        ): Boolean =
            prefix.isEmpty() ||
                    (
                            value.startsWith(prefix, ignoreCase = true) &&
                                    !value.equals(
                                        prefix,
                                        ignoreCase = true,
                                    )
                            )

        private fun score(
            value: TerminalCompletionDomainValue,
            prefix: String,
            orderIndex: Int,
        ): Int {
            val caseBonus =
                if (prefix.isEmpty()) {
                    0
                } else if (value.value.startsWith(prefix)) {
                    40
                } else {
                    20
                }
            val lengthPenalty = value.value.length - prefix.length
            return BASE_SCORE + caseBonus - lengthPenalty - orderIndex + value.scoreAdjustment
        }
    }
}
