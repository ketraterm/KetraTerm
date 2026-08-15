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
import io.github.ketraterm.completion.internal.hasTerminalCompletionLineBreak
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.spec.findCommandSpec

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
     * `null`. Unknown executables fall back to the generic shape classifier
     * with private positional argument categories.
     *
     * @param commandLine full command line to classify.
     * @param specs command specs used to recognize executable and subcommand paths.
     * @return privacy-preserving classification, or `null` when no command exists.
     */
    @JvmStatic
    fun classify(
        commandLine: String,
        specs: List<TerminalCommandSpec>,
        shellSyntax: TerminalShellSyntax = TerminalShellSyntax.PLAIN,
    ): TerminalCommandLineClassification? {
        if (commandLine.isBlank() || commandLine.hasTerminalCompletionLineBreak()) return null
        val tokens = TerminalCommandLineTokenizer.parse(commandLine, commandLine.length, shellSyntax).tokens
        return classify(tokens, specs)
    }

    /**
     * Classifies an already-tokenized active command segment.
     *
     * [commandLine] is retained only for rejecting blank or multi-line projected
     * outcomes. Reusing [tokens] avoids reparsing inside candidate ranking.
     *
     * @param commandLine projected command line used for input validation.
     * @param tokens tokens from the active command segment containing the candidate.
     * @param specs command specs used to recognize executable and subcommand paths.
     * @return privacy-preserving classification, or `null` when no command exists.
     */
    fun classify(
        commandLine: String,
        tokens: List<TerminalCommandLineToken>,
        specs: List<TerminalCommandSpec>,
    ): TerminalCommandLineClassification? {
        if (commandLine.isBlank() || commandLine.hasTerminalCompletionLineBreak()) return null
        return classify(tokens, specs)
    }

    private fun classify(
        tokens: List<TerminalCommandLineToken>,
        specs: List<TerminalCommandSpec>,
    ): TerminalCommandLineClassification? {
        val tokenIndex = tokens.firstCommandTokenIndex()
        if (tokenIndex >= tokens.size) return null

        val executableToken = normalizeTerminalCommandToken(tokens[tokenIndex].text)
        if (executableToken.isBlank()) return null
        val rootSpec = findCommandSpec(specs, executableToken) ?: return classifyWithoutSpec(tokens)

        val analysis = analyzeCommandTokens(tokens, tokenIndex + 1, tokens.size, rootSpec)
        val subcommands = analysis.commandPath.drop(1).map { normalizeTerminalCommandToken(it.name) }
        val arguments = analysis.arguments

        return TerminalCommandLineClassification(
            shape =
                TerminalCommandLineShape(
                    executable = normalizeTerminalCommandToken(rootSpec.name),
                    subcommands = subcommands,
                    optionNames = analysis.optionNames.sorted(),
                    positionalArgumentCount = analysis.positionalArgumentCount,
                    optionValueCount = arguments.count { it.kind == TerminalCommandArgumentKind.OPTION_VALUE },
                ),
            arguments = arguments,
            matchedSpec = true,
        )
    }

    private fun classifyWithoutSpec(tokens: List<TerminalCommandLineToken>): TerminalCommandLineClassification? {
        val shape = classifyGenericCommandLineShape(tokens) ?: return null
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
