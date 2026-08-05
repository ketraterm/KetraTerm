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
import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.spec.findCommandSpec

/** Bounded observed-token index for command families not covered by static specs. */
internal class SessionObservedTokenIndex(
    private val capacity: Int,
    private val commandSpecs: List<TerminalCommandSpec>,
) {
    private val entries = ArrayList<Entry>(capacity)

    fun record(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        nextSequence: () -> Long,
    ) {
        val tokens = TerminalCommandLineTokenizer.parse(commandLine, commandLine.length).tokens
        var tokenIndex = tokens.firstCommandTokenIndex()
        if (tokenIndex >= tokens.size) return
        val executable = normalizeTerminalCommandToken(tokens[tokenIndex].text)
        if (executable.isBlank() || findCommandSpec(commandSpecs, executable) != null) return

        val context = StringBuilder(executable)
        tokenIndex++
        var observedFirstArgument = false
        var encounteredOption = false
        while (tokenIndex < tokens.size) {
            val token = normalizeTerminalCommandToken(tokens[tokenIndex].text)
            when {
                token.isBlank() -> Unit
                token in COMMAND_OPERATORS || token == TERMINAL_COMMAND_OPTION_TERMINATOR -> return
                token.isTerminalOptionToken() -> {
                    token.observedOptionToken()?.let { option ->
                        retain(context.toString(), option, profileId, workingDirectoryUri, nextSequence())
                    }
                    context.appendToken(token)
                    encounteredOption = true
                }
                !observedFirstArgument && !encounteredOption -> {
                    retain(context.toString(), token, profileId, workingDirectoryUri, nextSequence())
                    context.appendToken(token)
                    observedFirstArgument = true
                }
                else -> return
            }
            tokenIndex++
        }
    }

    fun appendCandidates(
        request: TerminalCompletionRequest,
        context: TerminalCommandLineContext,
        destination: MutableList<TerminalCompletionCandidate>,
    ) {
        val observedContext = context.observedContext() ?: return
        val normalizedPrefix = normalizeTerminalCommandToken(context.activePrefix)
        for (entry in entries) {
            if (entry.context != observedContext || !entry.normalizedToken.startsWith(normalizedPrefix)) continue
            if (entry.normalizedToken == normalizedPrefix) continue
            destination += entry.toCandidate(request, context.replacementStartOffset, context.replacementEndOffset)
        }
    }

    fun clear() = entries.clear()

    private fun retain(
        context: String,
        token: String,
        profileId: String?,
        workingDirectoryUri: String?,
        sequence: Long,
    ) {
        if (token.isBlank()) return
        val index =
            entries.indexOfFirst { entry ->
                entry.context == context &&
                    entry.normalizedToken == token &&
                    entry.profileId == profileId &&
                    entry.workingDirectoryUri == workingDirectoryUri
            }
        if (index >= 0) {
            val entry = entries.removeAt(index)
            entries +=
                entry.copy(
                    token = token,
                    useCount = saturatedCompletionCounterIncrement(entry.useCount),
                    lastUsedSequence = sequence,
                )
            return
        }
        if (entries.size == capacity) entries.removeAt(0)
        entries += Entry(context, token, token, profileId, workingDirectoryUri, useCount = 1, lastUsedSequence = sequence)
    }

    private fun TerminalCommandLineContext.observedContext(): String? {
        if (activeTokenIndex <= 0) return null
        val context = StringBuilder()
        for (index in 0 until activeTokenIndex) {
            val token = normalizeTerminalCommandToken(tokens[index].text)
            if (token.isBlank()) return null
            context.appendToken(token)
        }
        return context.toString()
    }

    private fun Entry.toCandidate(
        request: TerminalCompletionRequest,
        replacementStartOffset: Int,
        replacementEndOffset: Int,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = token,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            displayText = token,
            detail = DETAIL,
            source = SOURCE_ID,
            kind = TerminalCompletionCandidateKind.ARGUMENT,
            score = sessionCompletionScore(BASE_SCORE, useCount, lastUsedSequence, profileId, workingDirectoryUri, request),
        )

    private fun StringBuilder.appendToken(token: String) {
        if (isNotEmpty()) append(CONTEXT_SEPARATOR)
        append(token)
    }

    private fun String.observedOptionToken(): String? =
        when {
            startsWith("--") -> substringBefore('=')
            length == SHORT_OPTION_LENGTH -> this
            else -> null
        }

    private data class Entry(
        val context: String,
        val token: String,
        val normalizedToken: String,
        val profileId: String?,
        val workingDirectoryUri: String?,
        val useCount: Int,
        val lastUsedSequence: Long,
    )

    private companion object {
        private const val SOURCE_ID = "observed"
        private const val DETAIL = "observed in this session"
        private const val BASE_SCORE = 760
        private const val CONTEXT_SEPARATOR = '\u0000'
        private const val SHORT_OPTION_LENGTH = 2
        private val COMMAND_OPERATORS = setOf("&&", "||", "|", "|&", ";", "&")
    }
}
