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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineClassifier
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.internal.commandLineAfterCandidate
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** Resolves source-independent keys for candidate outcomes and learned commands. */
internal class TerminalCompletionOutcomeKeyResolver(
    private val commandSpecs: List<TerminalCommandSpec>,
) {
    fun resolve(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
        context: TerminalCompletionContext,
    ): ResolvedCompletionOutcome? {
        val commandLine = request.commandLineAfterCandidate(candidate) ?: return null
        val classification =
            TerminalCommandLineClassifier.classify(
                commandLine,
                commandSpecs,
                request.shellCapabilities.syntax,
            ) ?: return null
        val pathAware =
            candidate.kind == TerminalCompletionCandidateKind.PATH ||
                context.expectedPathKind != TerminalPathArgumentKind.NONE
        val projectedCursorOffset =
            (candidate.replacementStartOffset + candidate.replacementText.length).coerceIn(0, commandLine.length)
        val projectedContext =
            TerminalCommandLineTokenizer.parse(
                commandLine,
                projectedCursorOffset,
                request.shellCapabilities.syntax,
            )
        val learnedKey =
            learnedKey(
                commandLine = commandLine,
                shellSyntax = request.shellCapabilities.syntax,
                pathTokenIndex = projectedContext.activeTokenIndex,
                pathAware = pathAware,
            ) ?: return null
        return ResolvedCompletionOutcome(
            groupKey = learnedKey.tokens,
            learnedKey = learnedKey,
            shape = classification.shape,
        )
    }

    fun learnedKey(
        commandLine: String,
        shellSyntax: TerminalShellSyntax,
        pathTokenIndex: Int,
        pathAware: Boolean,
    ): LearnedCompletionOutcomeKey? {
        val tokens = TerminalCommandLineTokenizer.parse(commandLine, commandLine.length, shellSyntax).tokens
        if (tokens.isEmpty()) return null
        val normalizedPathIndex = if (pathAware && pathTokenIndex in tokens.indices) pathTokenIndex else NO_PATH_TOKEN
        return LearnedCompletionOutcomeKey(
            tokens =
                tokens.mapIndexed { index, token ->
                    if (index == normalizedPathIndex) normalizePathToken(token.text) else token.text
                },
            pathTokenIndex = normalizedPathIndex,
        )
    }

    private fun normalizePathToken(token: String): String {
        val separatorIndex = token.indexOf('=')
        return if (separatorIndex > 0) {
            token.substring(0, separatorIndex + 1) + stripRedundantTrailingSeparators(token.substring(separatorIndex + 1))
        } else {
            stripRedundantTrailingSeparators(token)
        }
    }

    private fun stripRedundantTrailingSeparators(value: String): String {
        var end = value.length
        while (end > 1 && isPathSeparator(value[end - 1]) && !isRoot(value, end)) end--
        return if (end == value.length) value else value.substring(0, end)
    }

    private fun isRoot(
        value: String,
        end: Int,
    ): Boolean =
        end == 1 ||
            (end == WINDOWS_ROOT_LENGTH && value[1] == ':' && isPathSeparator(value[2])) ||
            (end == UNC_ROOT_LENGTH && isPathSeparator(value[0]) && isPathSeparator(value[1]))

    private fun isPathSeparator(value: Char): Boolean = value == '/' || value == '\\'

    private companion object {
        private const val NO_PATH_TOKEN = -1
        private const val WINDOWS_ROOT_LENGTH = 3
        private const val UNC_ROOT_LENGTH = 2
    }
}

internal data class ResolvedCompletionOutcome(
    val groupKey: List<String>,
    val learnedKey: LearnedCompletionOutcomeKey,
    val shape: io.github.ketraterm.completion.model.TerminalCommandLineShape?,
)

internal data class LearnedCompletionOutcomeKey(
    val tokens: List<String>,
    val pathTokenIndex: Int,
)
