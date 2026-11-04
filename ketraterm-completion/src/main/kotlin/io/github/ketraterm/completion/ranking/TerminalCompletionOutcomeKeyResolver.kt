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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineToken
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenIdentity
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.internal.commandLineAfterCandidate
import io.github.ketraterm.completion.internal.isRecordableTerminalCompletionCommand
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** Resolves source-independent keys for candidate outcomes and learned commands. */
internal class TerminalCompletionOutcomeKeyResolver {
    fun resolve(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
        context: TerminalCompletionContext,
    ): ResolvedCompletionOutcome? {
        val commandLine = request.commandLineAfterCandidate(candidate) ?: return null
        if (!isRecordableTerminalCompletionCommand(commandLine)) return null
        val pathAware =
            candidate.kind == TerminalCompletionCandidateKind.PATH ||
                context.expectedPathKind != TerminalPathArgumentKind.NONE

        // Outcome identity describes the edited input, not alias-expanded semantic tokens.
        val projectedContext =
            TerminalCommandLineTokenizer.parse(
                commandLine,
                candidate.replacementStartOffset + candidate.replacementText.length,
                request.shellCapabilities.syntax,
            )
        val tokens = projectedContext.tokens
        val activeIndex = projectedContext.activeTokenIndex
        // Whole-line history may append arguments; match the user's original word,
        // not the last word inserted by that candidate or an alias-generated word.
        val originalActiveIndex =
            TerminalCommandLineTokenizer
                .parse(
                    request.commandLine,
                    request.cursorOffset,
                    request.shellCapabilities.syntax,
                ).activeTokenIndex
        if (projectedContext.canReplay && tokens.firstCommandTokenIndex() >= tokens.size) return null
        val tokenIdentities =
            outcomeKey(
                tokens = tokens,
                pathTokenIndex = activeIndex,
                pathAware = pathAware,
                attachedOptionValue = context.attachedOptionValue != null && activeIndex == originalActiveIndex,
                shellSyntax = request.shellCapabilities.syntax,
            )
        val groupKey =
            if (projectedContext.canReplay) {
                tokenIdentities ?: return null
            } else {
                // Complex shell syntax remains a valid host result, but only exact text proves equivalence.
                listOf(TerminalCommandLineTokenIdentity(commandLine, rawText = commandLine))
            }
        return ResolvedCompletionOutcome(
            groupKey = groupKey,
            exactCommandLine = commandLine,
            activeValue =
                tokenIdentities?.getOrNull(originalActiveIndex)?.text?.let { value ->
                    if (context.attachedOptionValue == null) value else value.substringAfter('=')
                } ?: candidate.displayText,
        )
    }

    private fun outcomeKey(
        tokens: List<TerminalCommandLineToken>,
        pathTokenIndex: Int,
        pathAware: Boolean,
        attachedOptionValue: Boolean,
        shellSyntax: TerminalShellSyntax,
    ): List<TerminalCommandLineTokenIdentity>? {
        if (tokens.isEmpty()) return null
        val normalizedPathIndex = if (pathAware && pathTokenIndex in tokens.indices) pathTokenIndex else NO_PATH_TOKEN
        val resultTokens = ArrayList<TerminalCommandLineTokenIdentity>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            resultTokens +=
                if (index == normalizedPathIndex && token.rawText == null) {
                    TerminalCommandLineTokenIdentity(normalizePathToken(token.text, shellSyntax, attachedOptionValue))
                } else {
                    token.identity
                }
            index++
        }
        return resultTokens
    }

    private fun normalizePathToken(
        token: String,
        shellSyntax: TerminalShellSyntax,
        attachedOptionValue: Boolean,
    ): String {
        // An equals sign in a positional filename is literal, not an option/value boundary.
        val separatorIndex = if (attachedOptionValue) token.indexOf('=') else -1
        val windowsSeparators = shellSyntax == TerminalShellSyntax.POWERSHELL

        fun path(value: String): String {
            var normalized = if (windowsSeparators) value.replace('\\', '/') else value
            if (windowsSeparators) {
                while (normalized.length > 2 && normalized.startsWith("./")) normalized = normalized.substring(2)
            }
            return stripRedundantTrailingSeparators(normalized, windowsSeparators)
        }
        return if (separatorIndex > 0) {
            token.substring(0, separatorIndex + 1) + path(token.substring(separatorIndex + 1))
        } else {
            path(token)
        }
    }

    private fun stripRedundantTrailingSeparators(
        value: String,
        windowsSeparators: Boolean,
    ): String {
        var end = value.length
        // PowerShell backslashes are normalized above; remaining backslashes are literal filename characters.
        while (end > 1 && value[end - 1] == '/' && !isRoot(value, end, windowsSeparators)) end--
        return if (end == value.length) value else value.substring(0, end)
    }

    private fun isRoot(
        value: String,
        end: Int,
        windowsSeparators: Boolean,
    ): Boolean =
        end == 1 ||
            (windowsSeparators && end == WINDOWS_ROOT_LENGTH && value[1] == ':' && value[2] == '/') ||
            (end == UNC_ROOT_LENGTH && value[0] == '/' && value[1] == '/')

    private companion object {
        private const val NO_PATH_TOKEN = -1
        private const val WINDOWS_ROOT_LENGTH = 3
        private const val UNC_ROOT_LENGTH = 2
    }
}

internal data class ResolvedCompletionOutcome(
    val groupKey: List<TerminalCommandLineTokenIdentity>,
    val exactCommandLine: String,
    val activeValue: String?,
)
