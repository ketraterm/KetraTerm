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

import io.github.ketraterm.completion.internal.hasTerminalCompletionLineBreak
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.completion.spec.CommandSpecResolver

/**
 * Spec-aware command-line classifier used by completion ranking.
 *
 * The classifier recognizes known executable and nested subcommand paths from
 * [TerminalCommandSpec] while treating unknown positional values as private
 * arguments. It performs no I/O and never stores raw argument values.
 */
internal object TerminalCommandLineClassifier {
    /**
     * Classifies [commandLine] using [specs].
     *
     * Blank, multi-line, assignment-only, and missing-executable inputs return
     * `null`. Unknown executables fall back to the generic
     * [TerminalCommandLineShape.fromCommandLine] classifier with private
     * positional argument categories.
     *
     * @param commandLine full command line to classify.
     * @param specs command specs used to recognize executable and subcommand paths.
     * @return privacy-preserving classification, or `null` when no command exists.
     */
    @JvmStatic
    fun classify(
        commandLine: String,
        specs: List<TerminalCommandSpec>,
    ): TerminalCommandLineClassification? {
        if (commandLine.isBlank() || commandLine.hasTerminalCompletionLineBreak()) return null
        val tokens = TerminalCommandLineTokenizer.parse(commandLine, commandLine.length).tokens
        var tokenIndex = tokens.firstCommandTokenIndex()
        if (tokenIndex >= tokens.size) return null

        val executableToken = normalizeTerminalCommandToken(tokens[tokenIndex].text)
        if (executableToken.isBlank()) return null
        val rootSpec = CommandSpecResolver.findSpec(specs, executableToken) ?: return classifyWithoutSpec(commandLine)

        tokenIndex++
        var currentSpec = rootSpec
        val subcommands = ArrayList<String>(TERMINAL_COMMAND_LIST_CAPACITY)
        val optionNames = ArrayList<String>(TERMINAL_COMMAND_LIST_CAPACITY)
        val arguments = ArrayList<TerminalCommandArgumentShape>(TERMINAL_COMMAND_LIST_CAPACITY)
        var expectingOptionValue: String? = null
        var acceptingSubcommands = true
        var optionsEnabled = true

        while (tokenIndex < tokens.size) {
            val normalized = normalizeTerminalCommandToken(tokens[tokenIndex].text)
            if (normalized.isBlank()) {
                tokenIndex++
                continue
            }

            val optionValueFor = expectingOptionValue
            when {
                optionValueFor != null -> {
                    arguments += TerminalCommandArgumentShape(TerminalCommandArgumentKind.OPTION_VALUE, optionValueFor)
                    expectingOptionValue = null
                }
                normalized == TERMINAL_COMMAND_OPTION_TERMINATOR -> {
                    acceptingSubcommands = false
                    optionsEnabled = false
                }
                optionsEnabled && normalized.isTerminalOptionToken() -> {
                    val optionName = normalized.substringBefore("=")
                    optionNames += optionName
                    if (!normalized.contains("=") &&
                        CommandSpecResolver.optionRequiresSeparateValue(optionName, rootSpec, subcommands)
                    ) {
                        expectingOptionValue = optionName
                    }
                }
                acceptingSubcommands -> {
                    val next = CommandSpecResolver.findSpec(currentSpec.subcommands, normalized)
                    if (next != null) {
                        subcommands += normalizeTerminalCommandToken(next.name)
                        currentSpec = next
                    } else {
                        arguments += TerminalCommandArgumentShape(TerminalCommandArgumentKind.POSITIONAL)
                        acceptingSubcommands = false
                    }
                }
                optionsEnabled -> {
                    arguments += TerminalCommandArgumentShape(TerminalCommandArgumentKind.POSITIONAL)
                }
                else -> {
                    arguments += TerminalCommandArgumentShape(TerminalCommandArgumentKind.OPTION_TERMINATED_POSITIONAL)
                }
            }
            tokenIndex++
        }

        return TerminalCommandLineClassification(
            shape =
                TerminalCommandLineShape(
                    executable = normalizeTerminalCommandToken(rootSpec.name),
                    subcommands = subcommands,
                    optionNames = optionNames.sorted(),
                    positionalArgumentCount =
                        arguments.count {
                            it.kind == TerminalCommandArgumentKind.POSITIONAL ||
                                it.kind == TerminalCommandArgumentKind.OPTION_TERMINATED_POSITIONAL
                        },
                    optionValueCount = arguments.count { it.kind == TerminalCommandArgumentKind.OPTION_VALUE },
                ),
            arguments = arguments,
            matchedSpec = true,
        )
    }

    private fun classifyWithoutSpec(commandLine: String): TerminalCommandLineClassification? {
        val shape = TerminalCommandLineShape.fromCommandLine(commandLine) ?: return null
        val arguments =
            buildList(shape.positionalArgumentCount + shape.optionValueCount) {
                repeat(shape.optionValueCount) {
                    add(TerminalCommandArgumentShape(TerminalCommandArgumentKind.OPTION_VALUE, optionName = UNKNOWN_OPTION_NAME))
                }
                repeat(shape.positionalArgumentCount) {
                    add(TerminalCommandArgumentShape(TerminalCommandArgumentKind.POSITIONAL))
                }
            }
        return TerminalCommandLineClassification(
            shape = shape,
            arguments = arguments,
            matchedSpec = false,
        )
    }

    private const val UNKNOWN_OPTION_NAME = "<unknown>"
}
