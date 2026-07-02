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
package io.github.ketraterm.completion.spec

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.spec.CommandSpecResolver.findSpec

internal class SpecCompletionSource(
    specs: List<TerminalCommandSpec>,
) : TerminalCompletionSource {
    private val specs = specs.toList()

    init {
        require(specs.none { it.name.isBlank() }) { "specs must not contain blank command names" }
    }

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        if (specs.isEmpty()) return emptyList()
        val context = TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset)
        val commandTokenIndex = context.tokens.firstCommandTokenIndex()
        val candidates =
            if (context.activeTokenIndex <= commandTokenIndex) {
                completeCommands(context)
            } else {
                completeCommandBody(context, commandTokenIndex)
            }
        return candidates
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(request.maxCandidates)
    }

    private fun completeCommands(context: TerminalCommandLineContext): List<TerminalCompletionCandidate> =
        specs
            .asSequence()
            .filter { matchesCompletablePrefix(it.name, context.activePrefix) }
            .mapIndexed { orderIndex, spec ->
                candidate(
                    replacementText = spec.name,
                    displayText = spec.name,
                    detail = spec.description,
                    kind = TerminalCompletionCandidateKind.COMMAND,
                    context = context,
                    score = score(spec.name, context.activePrefix, COMMAND_BASE_SCORE, orderIndex),
                )
            }.toList()

    private fun completeCommandBody(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
    ): List<TerminalCompletionCandidate> {
        val resolved = resolvePath(context, commandTokenIndex) ?: return emptyList()
        return if (context.activePrefix.startsWith("-")) {
            completeOptions(resolved, context)
        } else {
            completeSubcommands(resolved.current, context)
        }
    }

    private fun completeSubcommands(
        command: TerminalCommandSpec,
        context: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> =
        command.subcommands
            .asSequence()
            .filter { matchesCompletablePrefix(it.name, context.activePrefix) }
            .mapIndexed { orderIndex, spec ->
                candidate(
                    replacementText = spec.name,
                    displayText = spec.name,
                    detail = spec.description,
                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    context = context,
                    score = score(spec.name, context.activePrefix, SUBCOMMAND_BASE_SCORE, orderIndex),
                )
            }.toList()

    private fun completeOptions(
        resolved: ResolvedCommandPath,
        context: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        val options = ArrayList<TerminalOptionSpec>()
        for (command in resolved.commands) {
            options += command.options
        }

        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (option in options) {
            for (name in option.names) {
                if (matchesCompletablePrefix(name, context.activePrefix)) {
                    candidates +=
                        candidate(
                            replacementText = name,
                            displayText = name,
                            detail = option.description,
                            kind = TerminalCompletionCandidateKind.OPTION,
                            context = context,
                            score = score(name, context.activePrefix, OPTION_BASE_SCORE, orderIndex),
                        )
                }
                orderIndex++
            }
        }
        return candidates
    }

    private fun resolvePath(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
    ): ResolvedCommandPath? {
        val tokens = context.tokens
        if (commandTokenIndex >= tokens.size) return null
        val root = findSpec(specs, normalizeTerminalCommandToken(tokens[commandTokenIndex].text)) ?: return null
        val commands = ArrayList<TerminalCommandSpec>()
        commands += root
        var current = root
        var index = commandTokenIndex + 1
        while (index < context.activeTokenIndex) {
            val token = tokens[index].text
            if (token.startsWith("-")) {
                val normalizedOption = normalizeTerminalCommandToken(token)
                val option =
                    commands.asReversed().firstNotNullOfOrNull { spec ->
                        spec.options.firstOrNull { option ->
                            option.names.any { normalizeTerminalCommandToken(it) == normalizedOption }
                        }
                    }
                if (option?.requiresValue == true) index++
                index++
                continue
            }

            val next = findSpec(current.subcommands, normalizeTerminalCommandToken(token)) ?: break
            current = next
            commands += current
            index++
        }
        return ResolvedCommandPath(commands)
    }

    private fun candidate(
        replacementText: String,
        displayText: String,
        detail: String,
        kind: TerminalCompletionCandidateKind,
        context: TerminalCommandLineContext,
        score: Int,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacementText,
            replacementStartOffset = context.replacementStartOffset,
            replacementEndOffset = context.replacementEndOffset,
            displayText = displayText,
            detail = detail,
            source = SOURCE_SPEC,
            kind = kind,
            score = score,
        )

    private data class ResolvedCommandPath(
        val commands: List<TerminalCommandSpec>,
    ) {
        val current: TerminalCommandSpec get() = commands.last()
    }

    private companion object {
        private const val SOURCE_SPEC = "spec"
        private const val COMMAND_BASE_SCORE = 300
        private const val SUBCOMMAND_BASE_SCORE = 250
        private const val OPTION_BASE_SCORE = 220

        private fun matchesCompletablePrefix(
            value: String,
            prefix: String,
        ): Boolean = prefix.isEmpty() || (value.startsWith(prefix, ignoreCase = true) && !value.equals(prefix, ignoreCase = true))

        private fun score(
            value: String,
            prefix: String,
            base: Int,
            orderIndex: Int,
        ): Int {
            if (prefix.isEmpty()) return base - orderIndex
            val caseBonus = if (value.startsWith(prefix)) 40 else 20
            val completionLengthPenalty = value.length - prefix.length
            return base + caseBonus - completionLengthPenalty - orderIndex
        }
    }
}
