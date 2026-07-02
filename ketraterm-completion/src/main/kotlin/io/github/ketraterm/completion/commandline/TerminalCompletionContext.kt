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

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

internal enum class TerminalCompletionActivePosition {
    COMMAND,
    SUBCOMMAND,
    OPTION_NAME,
    OPTION_VALUE,
    POSITIONAL_ARGUMENT,
}

internal data class TerminalCompletionContext(
    val commandLineContext: TerminalCommandLineContext,
    val commandTokenIndex: Int,
    val command: TerminalCommandSpec?,
    val commandPath: List<TerminalCommandSpec>,
    val activePosition: TerminalCompletionActivePosition,
    val activeOption: TerminalOptionSpec?,
    val expectedPathKind: TerminalPathArgumentKind,
    val staticValueCandidates: List<String>,
    val activeTokenQuote: Char,
) {
    val activePrefix: String get() = commandLineContext.activePrefix
    val replacementStartOffset: Int get() = commandLineContext.replacementStartOffset
    val replacementEndOffset: Int get() = commandLineContext.replacementEndOffset
    val currentCommand: TerminalCommandSpec? get() = commandPath.lastOrNull()
}

internal object TerminalCompletionContextResolver {
    fun resolve(
        commandLine: String,
        cursorOffset: Int,
        commandSpecs: List<TerminalCommandSpec>,
    ): TerminalCompletionContext {
        val lineContext = TerminalCommandLineTokenizer.parse(commandLine, cursorOffset)
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
                expectedPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                staticValueCandidates = emptyList(),
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
                command = null,
                commandPath = emptyList(),
                activePosition =
                    if (lineContext.activePrefix.isOptionNamePrefix()) {
                        TerminalCompletionActivePosition.OPTION_NAME
                    } else {
                        TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
                    },
                activeOption = null,
                expectedPathKind = TerminalPathArgumentKind.NONE,
                staticValueCandidates = emptyList(),
                activeTokenQuote = activeTokenQuote,
            )
        }

        val commandPath = resolveCommandPath(lineContext, commandTokenIndex, root)
        val activeOption = optionBeforeActiveValue(lineContext, commandTokenIndex, commandPath)
        val activePosition =
            when {
                activeOption != null -> TerminalCompletionActivePosition.OPTION_VALUE
                lineContext.activePrefix.isOptionNamePrefix() -> TerminalCompletionActivePosition.OPTION_NAME
                commandPath.last().positionalArgumentPathKind != TerminalPathArgumentKind.NONE ->
                    TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
                commandPath.last().subcommands.isNotEmpty() -> TerminalCompletionActivePosition.SUBCOMMAND
                else -> TerminalCompletionActivePosition.POSITIONAL_ARGUMENT
            }
        val expectedPathKind =
            if (activeOption != null && activeOption.valuePathKind != TerminalPathArgumentKind.NONE) {
                activeOption.valuePathKind
            } else {
                commandPath.last().positionalArgumentPathKind
            }

        return TerminalCompletionContext(
            commandLineContext = lineContext,
            commandTokenIndex = commandTokenIndex,
            command = root,
            commandPath = commandPath,
            activePosition = activePosition,
            activeOption = activeOption,
            expectedPathKind = expectedPathKind,
            staticValueCandidates = activeOption?.valueCandidates ?: emptyList(),
            activeTokenQuote = activeTokenQuote,
        )
    }

    private fun resolveCommandPath(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
        root: TerminalCommandSpec,
    ): List<TerminalCommandSpec> {
        val commands = ArrayList<TerminalCommandSpec>()
        commands += root
        var current = root
        var index = commandTokenIndex + 1
        while (index < context.activeTokenIndex) {
            val token = context.tokens[index].text
            if (token.isTerminalOptionToken()) {
                val option = findOption(commands, token)
                if (option?.requiresValue == true) index++
                index++
                continue
            }

            val next = findSpec(current.subcommands, normalizeTerminalCommandToken(token)) ?: break
            current = next
            commands += current
            index++
        }
        return commands
    }

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
        return if (option.requiresValue) option else null
    }

    private fun findOption(
        commands: List<TerminalCommandSpec>,
        token: String,
    ): TerminalOptionSpec? {
        val normalized = normalizeTerminalCommandToken(token)
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
}

private fun String.isOptionNamePrefix(): Boolean = startsWith("-") && this != ""
