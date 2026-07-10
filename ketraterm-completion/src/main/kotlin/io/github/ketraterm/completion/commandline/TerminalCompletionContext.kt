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

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.model.*

internal enum class TerminalCompletionActivePosition {
    OPERATOR,
    COMMAND,
    SUBCOMMAND,
    OPTION_NAME,
    OPTION_VALUE,
    POSITIONAL_ARGUMENT,
}

internal data class AttachedOptionValue(
    val option: TerminalOptionSpec,
    val prefix: String,
    val replacementStartOffset: Int,
    val quote: Char,
)

internal data class TerminalCompletionContext(
    val commandLineContext: TerminalCommandLineContext,
    val commandTokenIndex: Int,
    val command: TerminalCommandSpec?,
    val commandPath: List<TerminalCommandSpec>,
    val activePosition: TerminalCompletionActivePosition,
    val activeOption: TerminalOptionSpec?,
    val usedOptionExclusiveGroupIds: Set<String>,
    val optionsTerminated: Boolean,
    val expectedPathKind: TerminalPathArgumentKind,
    val expectedHiddenPathPolicy: TerminalHiddenPathPolicy,
    val expectedValueDomain: TerminalCompletionValueDomain,
    val subcommandCandidateSource: TerminalCommandSpec?,
    val staticValueCandidates: List<String>,
    val activeTokenQuote: Char,
    private val attachedOptionValue: AttachedOptionValue?,
) {
    val activePrefix: String get() = attachedOptionValue?.prefix ?: commandLineContext.activePrefix
    val replacementStartOffset: Int get() = attachedOptionValue?.replacementStartOffset ?: commandLineContext.replacementStartOffset
    val replacementEndOffset: Int get() = commandLineContext.replacementEndOffset
    val currentCommand: TerminalCommandSpec? get() = commandPath.lastOrNull()
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
                commandTokenIndex = 0,
                command = null,
                commandPath = emptyList(),
                activePosition = TerminalCompletionActivePosition.OPERATOR,
                activeOption = null,
                usedOptionExclusiveGroupIds = emptySet(),
                optionsTerminated = false,
                expectedPathKind = TerminalPathArgumentKind.NONE,
                expectedHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
                expectedValueDomain = TerminalCompletionValueDomain.NONE,
                subcommandCandidateSource = null,
                staticValueCandidates = emptyList(),
                activeTokenQuote = NO_QUOTE,
                attachedOptionValue = null,
            )
        }
        val commandTokenIndex = lineContext.tokens.firstCommandTokenIndex()
        val isCommandPosition = lineContext.activeTokenIndex <= commandTokenIndex
        val activeTokenQuote = activeTokenQuote(commandLine, lineContext.replacementStartOffset)
        if (isCommandPosition) {
            return TerminalCompletionContext(
                commandLineContext = lineContext,
                commandTokenIndex = commandTokenIndex,
                command = null,
                commandPath = emptyList(),
                activePosition = TerminalCompletionActivePosition.COMMAND,
                activeOption = null,
                usedOptionExclusiveGroupIds = emptySet(),
                optionsTerminated = false,
                expectedPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                expectedHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
                expectedValueDomain = TerminalCompletionValueDomain.NONE,
                subcommandCandidateSource = null,
                staticValueCandidates = emptyList(),
                activeTokenQuote = activeTokenQuote,
                attachedOptionValue = null,
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
                command = null,
                commandPath = emptyList(),
                activePosition =
                    if (lineContext.activePrefix.isOptionNamePrefix()) {
                        TerminalCompletionActivePosition.OPTION_NAME
                    } else {
                        TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
                    },
                activeOption = null,
                usedOptionExclusiveGroupIds = emptySet(),
                optionsTerminated = false,
                expectedPathKind = TerminalPathArgumentKind.NONE,
                expectedHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
                expectedValueDomain = TerminalCompletionValueDomain.NONE,
                subcommandCandidateSource = null,
                staticValueCandidates = emptyList(),
                activeTokenQuote = activeTokenQuote,
                attachedOptionValue = null,
            )
        }

        val optionsTerminated = lineContext.hasPassedOptionTerminator(commandTokenIndex)
        val resolvedCommandPath = resolveCommandPath(lineContext, commandTokenIndex, root)
        val commandPath = resolvedCommandPath.commandPath
        val attachedOptionValue =
            if (optionsTerminated) {
                null
            } else {
                attachedOptionValue(commandLine, lineContext, commandPath)
            }
        val activeOption =
            attachedOptionValue?.option
                ?: if (optionsTerminated) {
                    null
                } else {
                    optionBeforeActiveValue(lineContext, commandTokenIndex, commandPath)
                }
        val usedOptionExclusiveGroupIds = resolvedCommandPath.usedOptionExclusiveGroupIds
        val subcommandCandidateSource = if (optionsTerminated) null else subcommandCandidateSource(commandPath)
        val activePosition =
            when {
                optionsTerminated -> TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
                activeOption != null -> TerminalCompletionActivePosition.OPTION_VALUE
                lineContext.activePrefix.isOptionNamePrefix() -> TerminalCompletionActivePosition.OPTION_NAME
                commandPath.last().positionalArgumentPathKind != TerminalPathArgumentKind.NONE ->
                    TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
                subcommandCandidateSource != null -> TerminalCompletionActivePosition.SUBCOMMAND
                else -> TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
            }
        val expectedPathKind =
            if (activeOption != null && activeOption.valuePathKind != TerminalPathArgumentKind.NONE) {
                activeOption.valuePathKind
            } else {
                commandPath.last().positionalArgumentPathKind
            }
        val expectedValueDomain =
            if (activeOption != null && activeOption.valueDomain != TerminalCompletionValueDomain.NONE) {
                activeOption.valueDomain
            } else {
                commandPath.last().positionalArgumentValueDomain
            }
        val expectedHiddenPathPolicy =
            if (activeOption != null && activeOption.valuePathKind != TerminalPathArgumentKind.NONE) {
                activeOption.valueHiddenPathPolicy
            } else {
                commandPath.last().positionalArgumentHiddenPathPolicy
            }

        return TerminalCompletionContext(
            commandLineContext = lineContext,
            commandTokenIndex = commandTokenIndex,
            command = root,
            commandPath = commandPath,
            activePosition = activePosition,
            activeOption = activeOption,
            usedOptionExclusiveGroupIds = usedOptionExclusiveGroupIds,
            optionsTerminated = optionsTerminated,
            expectedPathKind = expectedPathKind,
            expectedHiddenPathPolicy = expectedHiddenPathPolicy,
            expectedValueDomain = expectedValueDomain,
            subcommandCandidateSource = subcommandCandidateSource,
            staticValueCandidates = activeOption?.valueCandidates ?: emptyList(),
            activeTokenQuote = attachedOptionValue?.quote ?: activeTokenQuote,
            attachedOptionValue = attachedOptionValue,
        )
    }

    private fun subcommandCandidateSource(commandPath: List<TerminalCommandSpec>): TerminalCommandSpec? {
        val current = commandPath.last()
        if (current.subcommands.isNotEmpty()) return current
        if (commandPath.size < 2) return null

        return commandPath.asReversed().firstOrNull { it.repeatableSubcommands }
    }

    private fun resolveCommandPath(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
        root: TerminalCommandSpec,
    ): ResolvedCommandPath {
        val commands = ArrayList<TerminalCommandSpec>()
        var usedExclusiveGroupIds: LinkedHashSet<String>? = null
        commands += root
        var current = root
        var index = commandTokenIndex + 1
        while (index < context.activeTokenIndex) {
            val token = context.tokens[index].text
            if (token == TERMINAL_COMMAND_OPTION_TERMINATOR) break
            if (token.isTerminalOptionToken()) {
                val option = findOption(commands, token)
                if (option != null) {
                    if (option.exclusiveGroupIds.isNotEmpty()) {
                        if (usedExclusiveGroupIds == null) {
                            usedExclusiveGroupIds = LinkedHashSet(option.exclusiveGroupIds.size)
                        }
                        usedExclusiveGroupIds.addAll(option.exclusiveGroupIds)
                    }
                    if (option.requiresValue && !token.hasAttachedOptionValue()) index++
                }
                index++
                continue
            }

            val next = findNextSubcommand(commands, current, normalizeTerminalCommandToken(token)) ?: break
            current = next
            commands += current
            index++
        }
        return ResolvedCommandPath(
            commandPath = commands,
            usedOptionExclusiveGroupIds = usedExclusiveGroupIds ?: emptySet(),
        )
    }

    private fun findNextSubcommand(
        commands: List<TerminalCommandSpec>,
        current: TerminalCommandSpec,
        normalizedToken: String,
    ): TerminalCommandSpec? =
        findSpec(current.subcommands, normalizedToken)
            ?: commands
                .asReversed()
                .firstOrNull { it.repeatableSubcommands }
                ?.let { repeatableSource -> findSpec(repeatableSource.subcommands, normalizedToken) }

    private fun optionBeforeActiveValue(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
        commands: List<TerminalCommandSpec>,
    ): TerminalOptionSpec? {
        val optionIndex = context.activeTokenIndex - 1
        if (optionIndex <= commandTokenIndex) return null
        val optionToken = context.tokens.getOrNull(optionIndex) ?: return null
        if (!optionToken.text.isTerminalOptionToken()) return null
        val option = findOption(commands, optionToken.text) ?: return null
        return if (option.requiresValue && !optionToken.text.hasAttachedOptionValue()) option else null
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
        val option = findOption(commands, commandLine.substring(activeToken.startOffset, separatorOffset)) ?: return null
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

    private fun findOption(
        commands: List<TerminalCommandSpec>,
        token: String,
    ): TerminalOptionSpec? {
        val separatorIndex = token.indexOf(OPTION_VALUE_SEPARATOR)
        val normalized = normalizeTerminalCommandToken(if (separatorIndex < 0) token else token.substring(0, separatorIndex))
        return commands.asReversed().firstNotNullOfOrNull { command ->
            command.options.firstOrNull { option ->
                option.names.any { name -> normalizeTerminalCommandToken(name) == normalized }
            }
        }
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

    private const val NO_QUOTE = '\u0000'
    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private const val OPTION_VALUE_SEPARATOR = '='

    private data class ResolvedCommandPath(
        val commandPath: List<TerminalCommandSpec>,
        val usedOptionExclusiveGroupIds: Set<String>,
    )
}

private fun TerminalCommandLineContext.hasPassedOptionTerminator(commandTokenIndex: Int): Boolean {
    var index = commandTokenIndex + 1
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.startOffset >= cursorOffset) break
        if (token.endOffset <= cursorOffset && token.text == TERMINAL_COMMAND_OPTION_TERMINATOR) return true
        index++
    }
    return false
}

private fun String.isOptionNamePrefix(): Boolean = startsWith("-") && this != ""

private fun String.hasAttachedOptionValue(): Boolean = indexOf('=') > 1
