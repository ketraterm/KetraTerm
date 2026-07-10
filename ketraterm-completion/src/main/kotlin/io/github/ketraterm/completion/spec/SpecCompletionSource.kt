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
import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec

internal class SpecCompletionSource(
    specs: List<TerminalCommandSpec>,
) : ContextAwareCompletionSource {
    private val specs = specs.toList()

    init {
        require(specs.none { it.name.isBlank() }) { "specs must not contain blank command names" }
    }

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset, request.shellCapabilities.syntax),
        )

    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        if (specs.isEmpty()) return emptyList()
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = request.commandLine,
                lineContext = commandLineContext,
                commandSpecs = specs,
            )
        val candidates =
            when (context.activePosition) {
                TerminalCompletionActivePosition.OPERATOR -> emptyList()
                TerminalCompletionActivePosition.COMMAND -> completeCommands(context.commandLineContext)
                TerminalCompletionActivePosition.OPTION_NAME -> completeOptions(context)
                TerminalCompletionActivePosition.OPTION_VALUE -> completeOptionValues(context)
                TerminalCompletionActivePosition.SUBCOMMAND -> completeSubcommands(context)
                TerminalCompletionActivePosition.POSITIONAL_ARGUMENT -> emptyList()
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

    private fun completeSubcommands(context: TerminalCompletionContext): List<TerminalCompletionCandidate> =
        context.subcommandCandidateSource
            ?.subcommands
            .orEmpty()
            .asSequence()
            .filterNot { context.isAlreadyUsedRepeatableSubcommand(it) }
            .filter { matchesCompletablePrefix(it.name, context.activePrefix) }
            .mapIndexed { orderIndex, spec ->
                candidate(
                    replacementText = spec.name,
                    displayText = spec.name,
                    detail = spec.description,
                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    context = context.commandLineContext,
                    score = score(spec.name, context.activePrefix, SUBCOMMAND_BASE_SCORE, orderIndex),
                )
            }.toList()

    private fun TerminalCompletionContext.isAlreadyUsedRepeatableSubcommand(spec: TerminalCommandSpec): Boolean {
        val source = subcommandCandidateSource ?: return false
        if (!source.repeatableSubcommands) return false
        return commandPath.dropWhile { it != source }.drop(1).any { command ->
            command.name.equals(spec.name, ignoreCase = true)
        }
    }

    private fun completeOptions(context: TerminalCompletionContext): List<TerminalCompletionCandidate> {
        val options = ArrayList<TerminalOptionSpec>()
        for (command in context.commandPath) {
            options += command.options
        }

        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (option in options) {
            if (option.exclusiveGroupIds.any(context.usedOptionExclusiveGroupIds::contains)) continue
            for (name in option.names) {
                if (matchesCompletablePrefix(name, context.activePrefix)) {
                    candidates +=
                        candidate(
                            replacementText = name,
                            displayText = name,
                            detail = option.description,
                            kind = TerminalCompletionCandidateKind.OPTION,
                            context = context.commandLineContext,
                            score = score(name, context.activePrefix, OPTION_BASE_SCORE, orderIndex),
                        )
                }
                orderIndex++
            }
        }
        return candidates
    }

    private fun completeOptionValues(context: TerminalCompletionContext): List<TerminalCompletionCandidate> =
        context.staticValueCandidates
            .asSequence()
            .filter { matchesCompletablePrefix(it, context.activePrefix) }
            .mapIndexed { orderIndex, value ->
                candidate(
                    replacementText = value,
                    displayText = value,
                    detail = context.activeOption?.description.orEmpty(),
                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                    context = context,
                    score = score(value, context.activePrefix, OPTION_VALUE_BASE_SCORE, orderIndex),
                )
            }.toList()

    private fun candidate(
        replacementText: String,
        displayText: String,
        detail: String,
        kind: TerminalCompletionCandidateKind,
        context: TerminalCompletionContext,
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

    private companion object {
        private const val SOURCE_SPEC = "spec"
        private const val COMMAND_BASE_SCORE = 300
        private const val SUBCOMMAND_BASE_SCORE = 250
        private const val OPTION_BASE_SCORE = 220
        private const val OPTION_VALUE_BASE_SCORE = 210

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
