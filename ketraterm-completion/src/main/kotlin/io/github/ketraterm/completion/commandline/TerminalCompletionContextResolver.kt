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

import io.github.ketraterm.completion.api.TerminalCompletionActivePosition
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.model.*

internal data class AttachedOptionValue(
    val option: TerminalOptionSpec,
    val prefix: String,
    val replacementStartOffset: Int,
    val quote: Char,
)

/** Parses and resolves one request against the engine's single command-spec set. */
internal fun TerminalCompletionRequest.resolveCompletionContext(commandSpecs: List<TerminalCommandSpec>): TerminalCompletionContext {
    val lineContext = TerminalCommandLineTokenizer.parse(commandLine, cursorOffset, shellCapabilities.syntax)
    return TerminalCompletionContextResolver.resolve(commandLine, lineContext, commandSpecs)
}

internal object TerminalCompletionContextResolver {
    fun resolve(
        commandLine: String,
        cursorOffset: Int,
        commandSpecs: List<TerminalCommandSpec>,
        shellSyntax: TerminalShellSyntax = TerminalShellSyntax.PLAIN,
    ): TerminalCompletionContext =
        resolve(
            commandLine = commandLine,
            lineContext = TerminalCommandLineTokenizer.parse(commandLine, cursorOffset, shellSyntax),
            commandSpecs = commandSpecs,
        )

    fun resolve(
        commandLine: String,
        lineContext: TerminalCommandLineContext,
        commandSpecs: List<TerminalCommandSpec>,
    ): TerminalCompletionContext {
        if (lineContext.cursorRegion == TerminalCommandLineCursorRegion.OPERATOR) {
            return TerminalCompletionContext(
                commandLineContext = lineContext,
                activePosition = TerminalCompletionActivePosition.OPERATOR,
            )
        }
        val commandTokenIndex = lineContext.tokens.firstCommandTokenIndex()
        val isCommandPosition = lineContext.activeTokenIndex <= commandTokenIndex
        val activeTokenQuote = activeTokenQuote(commandLine, lineContext.replacementStartOffset)
        if (isCommandPosition) {
            return TerminalCompletionContext(
                commandLineContext = lineContext,
                commandTokenIndex = commandTokenIndex,
                activePosition = TerminalCompletionActivePosition.COMMAND,
                expectedPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                activeTokenQuote = activeTokenQuote,
            )
        }

        val commandToken = lineContext.tokens.getOrNull(commandTokenIndex)
        val root =
            commandToken
                ?.let { findSpec(commandSpecs, normalizeTerminalCommandToken(it.text)) }
        if (root == null) {
            return TerminalCompletionContext(
                commandLineContext = lineContext,
                commandTokenIndex = commandTokenIndex,
                activePosition =
                    if (lineContext.activePrefix.isOptionNamePrefix()) {
                        TerminalCompletionActivePosition.OPTION_NAME
                    } else {
                        TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
                    },
                activeTokenQuote = activeTokenQuote,
            )
        }

        val analysis =
            analyzeCommandTokens(
                tokens = lineContext.tokens,
                startIndex = commandTokenIndex + 1,
                endIndexExclusive = lineContext.activeTokenIndex,
                rootSpec = root,
            )
        val optionsTerminated = analysis.optionsTerminated
        val commandPath = analysis.commandPath
        val attachedOptionValue =
            if (optionsTerminated) {
                null
            } else {
                attachedOptionValue(commandLine, lineContext, commandPath)
            }
        val activeOption =
            attachedOptionValue?.option ?: if (optionsTerminated) null else analysis.pendingOptionValue
        val activePositionalArgument =
            if (activeOption == null) {
                commandPath.last().positionalArgumentAt(analysis.positionalArgumentCount)
            } else {
                null
            }
        val usedOptionExclusiveGroupIds = analysis.usedOptionExclusiveGroupIds
        val subcommandCandidateSource = if (optionsTerminated) null else subcommandCandidateSource(commandPath)
        val lastCommand = commandPath.last()
        val activePosition =
            determineActivePosition(
                optionsTerminated = optionsTerminated,
                activeOption = activeOption,
                activePrefix = lineContext.activePrefix,
                activePositionalArgument = activePositionalArgument,
                lastCommand = lastCommand,
                subcommandCandidateSource = subcommandCandidateSource,
            )
        val expectedPathKind = determineExpectedPathKind(activeOption, activePositionalArgument, lastCommand)
        val expectedValueDomain = determineExpectedValueDomain(activeOption, activePositionalArgument, lastCommand)
        val expectedHiddenPathPolicy = determineExpectedHiddenPathPolicy(activeOption, activePositionalArgument, lastCommand)

        return TerminalCompletionContext(
            commandLineContext = lineContext,
            commandTokenIndex = commandTokenIndex,
            command = root,
            commandPath = commandPath,
            activePosition = activePosition,
            activeOption = activeOption,
            activePositionalArgument = activePositionalArgument,
            usedOptionExclusiveGroupIds = usedOptionExclusiveGroupIds,
            optionsTerminated = optionsTerminated,
            expectedPathKind = expectedPathKind,
            expectedHiddenPathPolicy = expectedHiddenPathPolicy,
            expectedValueDomain = expectedValueDomain,
            subcommandCandidateSource = subcommandCandidateSource,
            staticValueCandidates = activeOption?.valueCandidates ?: activePositionalArgument?.valueCandidates ?: emptyList(),
            activeTokenQuote = attachedOptionValue?.quote ?: activeTokenQuote,
            attachedOptionValue = attachedOptionValue,
        )
    }

    private fun determineActivePosition(
        optionsTerminated: Boolean,
        activeOption: TerminalOptionSpec?,
        activePrefix: String,
        activePositionalArgument: TerminalArgumentSpec?,
        lastCommand: TerminalCommandSpec,
        subcommandCandidateSource: TerminalCommandSpec?,
    ): TerminalCompletionActivePosition =
        when {
            optionsTerminated -> TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
            activeOption != null -> TerminalCompletionActivePosition.OPTION_VALUE
            activePrefix.isOptionNamePrefix() -> TerminalCompletionActivePosition.OPTION_NAME
            activePositionalArgument?.pathKind?.let { it != TerminalPathArgumentKind.NONE } == true ||
                lastCommand.positionalArgumentPathKind != TerminalPathArgumentKind.NONE ->
                TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
            subcommandCandidateSource != null -> TerminalCompletionActivePosition.SUBCOMMAND
            else -> TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
        }

    private fun determineExpectedPathKind(
        activeOption: TerminalOptionSpec?,
        activePositionalArgument: TerminalArgumentSpec?,
        lastCommand: TerminalCommandSpec,
    ): TerminalPathArgumentKind =
        if (activeOption != null && activeOption.valuePathKind != TerminalPathArgumentKind.NONE) {
            activeOption.valuePathKind
        } else if (activePositionalArgument != null) {
            activePositionalArgument.pathKind
        } else {
            lastCommand.positionalArgumentPathKind
        }

    private fun determineExpectedValueDomain(
        activeOption: TerminalOptionSpec?,
        activePositionalArgument: TerminalArgumentSpec?,
        lastCommand: TerminalCommandSpec,
    ): TerminalCompletionValueDomain =
        if (activeOption != null && activeOption.valueDomain != TerminalCompletionValueDomain.NONE) {
            activeOption.valueDomain
        } else if (activePositionalArgument != null) {
            activePositionalArgument.valueDomain
        } else {
            lastCommand.positionalArgumentValueDomain
        }

    private fun determineExpectedHiddenPathPolicy(
        activeOption: TerminalOptionSpec?,
        activePositionalArgument: TerminalArgumentSpec?,
        lastCommand: TerminalCommandSpec,
    ): TerminalHiddenPathPolicy =
        if (activeOption != null && activeOption.valuePathKind != TerminalPathArgumentKind.NONE) {
            activeOption.valueHiddenPathPolicy
        } else if (activePositionalArgument != null) {
            activePositionalArgument.hiddenPathPolicy
        } else {
            lastCommand.positionalArgumentHiddenPathPolicy
        }

    private fun subcommandCandidateSource(commandPath: List<TerminalCommandSpec>): TerminalCommandSpec? {
        val current = commandPath.last()
        if (current.subcommands.isNotEmpty()) return current
        if (commandPath.size < 2) return null

        return commandPath.asReversed().firstOrNull { it.repeatableSubcommands }
    }

    private fun attachedOptionValue(
        commandLine: String,
        context: TerminalCommandLineContext,
        commands: List<TerminalCommandSpec>,
    ): AttachedOptionValue? {
        val activeToken = context.tokens.getOrNull(context.activeTokenIndex) ?: return null
        val separatorOffset = commandLine.indexOf(OPTION_VALUE_SEPARATOR, activeToken.startOffset)
        if (separatorOffset !in activeToken.startOffset until activeToken.endOffset || context.cursorOffset <= separatorOffset) {
            return null
        }
        val option =
            io.github.ketraterm.completion.spec.findOptionSpec(
                commands,
                commandLine.substring(activeToken.startOffset, separatorOffset),
            ) ?: return null
        if (!option.requiresValue) return null
        val prefixSeparatorIndex = context.activePrefix.indexOf(OPTION_VALUE_SEPARATOR)
        if (prefixSeparatorIndex < 0) return null
        val quote = commandLine.getOrNull(separatorOffset + 1)
        return AttachedOptionValue(
            option = option,
            prefix = context.activePrefix.substring(prefixSeparatorIndex + 1),
            replacementStartOffset = separatorOffset + 1,
            quote = if (quote == SINGLE_QUOTE || quote == DOUBLE_QUOTE) quote else NO_QUOTE,
        )
    }

    private fun findSpec(
        specs: List<TerminalCommandSpec>,
        normalizedToken: String,
    ): TerminalCommandSpec? =
        specs.firstOrNull { spec ->
            normalizeTerminalCommandToken(spec.name) == normalizedToken ||
                spec.aliases.any { normalizeTerminalCommandToken(it) == normalizedToken }
        }

    private fun activeTokenQuote(
        commandLine: String,
        replacementStartOffset: Int,
    ): Char {
        val quote = commandLine.getOrNull(replacementStartOffset)
        return if (quote == SINGLE_QUOTE || quote == DOUBLE_QUOTE) quote else NO_QUOTE
    }

    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private const val OPTION_VALUE_SEPARATOR = '='
}

private fun TerminalCommandSpec.positionalArgumentAt(position: Int): TerminalArgumentSpec? {
    if (positionalArguments.isEmpty() || position < 0) return null
    if (position < positionalArguments.size) return positionalArguments[position]
    val last = positionalArguments.last()
    return if (last.isVariadic) last else null
}

private fun String.isOptionNamePrefix(): Boolean = startsWith('-')

private const val NO_QUOTE = '\u0000'
