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
import io.github.ketraterm.completion.model.TerminalCommandLineShape

/**
 * Generic privacy-preserving shape classifier used when no command spec matches.
 *
 * This classifier recognizes executable, a shallow subcommand, option names,
 * and aggregate argument counts without retaining raw positional argument text.
 */
internal object GenericCommandLineShapeClassifier {
    fun classify(commandLine: String): TerminalCommandLineShape? {
        if (commandLine.isBlank() || commandLine.hasTerminalCompletionLineBreak()) return null
        val tokens = TerminalCommandLineTokenizer.parse(commandLine, commandLine.length).tokens
        var tokenIndex = tokens.firstCommandTokenIndex()
        if (tokenIndex >= tokens.size) return null

        val executable = normalizeTerminalCommandToken(tokens[tokenIndex].text)
        if (executable.isBlank()) return null
        tokenIndex++

        val subcommands = ArrayList<String>(TERMINAL_COMMAND_LIST_CAPACITY)
        val optionNames = ArrayList<String>(TERMINAL_COMMAND_LIST_CAPACITY)
        var positionalArgumentCount = 0
        var optionValueCount = 0
        var expectingOptionValue = false
        var acceptingSubcommands = true
        var optionsEnabled = true

        while (tokenIndex < tokens.size) {
            val normalized = normalizeTerminalCommandToken(tokens[tokenIndex].text)
            if (normalized.isBlank()) {
                tokenIndex++
                continue
            }
            if (expectingOptionValue) {
                optionValueCount++
                expectingOptionValue = false
                acceptingSubcommands = false
            } else if (normalized == TERMINAL_COMMAND_OPTION_TERMINATOR) {
                acceptingSubcommands = false
                optionsEnabled = false
            } else if (optionsEnabled && normalized.isTerminalOptionToken()) {
                val optionName = normalized.substringBefore("=")
                optionNames.add(optionName)
                if (!normalized.contains("=") && optionName.optionUsuallyRequiresValue()) {
                    expectingOptionValue = true
                }
                acceptingSubcommands = false
            } else if (acceptingSubcommands && subcommands.size < MAX_SUBCOMMAND_DEPTH) {
                subcommands.add(normalized)
            } else {
                acceptingSubcommands = false
                positionalArgumentCount++
            }
            tokenIndex++
        }

        return TerminalCommandLineShape(
            executable = executable,
            subcommands = subcommands,
            optionNames = optionNames.sorted(),
            positionalArgumentCount = positionalArgumentCount,
            optionValueCount = optionValueCount,
        )
    }

    private fun String.optionUsuallyRequiresValue(): Boolean =
        when (this) {
            "-c", "-d", "-f", "-m", "-o", "-p", "-u" -> true
            else -> startsWith("--") && !BOOLEAN_LONG_OPTIONS.contains(this)
        }

    private const val MAX_SUBCOMMAND_DEPTH = 1
    private val BOOLEAN_LONG_OPTIONS =
        setOf(
            "--all",
            "--amend",
            "--debug",
            "--dry-run",
            "--force",
            "--global",
            "--help",
            "--info",
            "--json",
            "--offline",
            "--quiet",
            "--stat",
            "--verbose",
            "--version",
            "--watch",
        )
}
