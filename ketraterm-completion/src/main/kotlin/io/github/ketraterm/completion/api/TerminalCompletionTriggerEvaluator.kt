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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/**
 * Pure evaluation engine that determines if a command line should trigger completions.
 *
 * This evaluator is shared between all hosts (standalone app and IDE plugins)
 * to ensure consistent completion triggering policy across environments without
 * depending on Swing timers or session execution states.
 */
object TerminalCompletionTriggerEvaluator {
    /**
     * Returns whether the typed command line at the specified cursor offset should trigger suggestions.
     *
     * @param commandLine visible command text.
     * @param cursorOffset UTF-16 cursor position.
     * @param minimumNonWhitespaceCharacters minimum characters required for default typing.
     * @param commandSpecs command specs used for context-aware live triggers.
     * @param shellCapabilities resolved shell lexical and replacement policy.
     * @return `true` if suggestions should be requested.
     */
    @JvmOverloads
    fun shouldTrigger(
        commandLine: String,
        cursorOffset: Int,
        minimumNonWhitespaceCharacters: Int,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ): Boolean {
        val lineContext = TerminalCommandLineTokenizer.parse(commandLine, cursorOffset, shellCapabilities.syntax)
        if (lineContext.cursorRegion == TerminalCommandLineCursorRegion.OPERATOR) return false
        if (lineContext.activePrefix == OPTION_TERMINATOR) return false
        if (isLiveTrigger(commandLine, cursorOffset, commandSpecs, lineContext)) return true
        return nonWhitespaceCount(lineContext.commandPrefix(commandLine)) >= minimumNonWhitespaceCharacters
    }

    /**
     * Returns whether the cursor is positioned after a specific trigger token.
     *
     * Trigger tokens bypass character length checks, instantly requesting suggestions.
     *
     * @param commandLine visible command text.
     * @param cursorOffset UTF-16 cursor position.
     * @param commandSpecs command specs used for context-aware space triggers.
     * @param shellCapabilities resolved shell lexical and replacement policy.
     * @return `true` if cursor is immediately after a live trigger token.
     */
    @JvmOverloads
    fun isLiveTrigger(
        commandLine: String,
        cursorOffset: Int,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ): Boolean {
        val lineContext = TerminalCommandLineTokenizer.parse(commandLine, cursorOffset, shellCapabilities.syntax)
        if (lineContext.cursorRegion == TerminalCommandLineCursorRegion.OPERATOR) return false
        if (lineContext.activePrefix == OPTION_TERMINATOR) return false
        return isLiveTrigger(commandLine, cursorOffset, commandSpecs, lineContext)
    }

    private fun isLiveTrigger(
        commandLine: String,
        cursorOffset: Int,
        commandSpecs: List<TerminalCommandSpec>,
        lineContext: TerminalCommandLineContext,
    ): Boolean {
        if (cursorOffset <= 0) return false

        if (lineContext.precededByOperator && lineContext.commandPrefix(commandLine).isBlank()) return true

        val lastChar = commandLine.getOrNull(cursorOffset - 1) ?: return false

        // 1. Hyphen option trigger (e.g. '-')
        if (lastChar == '-') return true

        // 2. Attached option value trigger (e.g. '--output=')
        if (lastChar == '=') {
            return hasContextualValueCompletions(commandLine, lineContext, commandSpecs)
        }

        // 3. Path separator trigger (e.g. '/' or '\')
        if (lastChar == '/' || lastChar == '\\') return true

        // 4. Environment variable trigger (e.g. '$')
        if (lastChar == '$') return true

        // 5. Context-aware finished-word space trigger
        if (lastChar == ' ') {
            val prevChar = commandLine.getOrNull(cursorOffset - 2)
            if (prevChar != null && prevChar != ' ') {
                return isContextualSpaceTrigger(commandLine, lineContext, commandSpecs)
            }
        }

        return false
    }

    private fun isContextualSpaceTrigger(
        commandLine: String,
        lineContext: TerminalCommandLineContext,
        commandSpecs: List<TerminalCommandSpec>,
    ): Boolean {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = commandLine,
                lineContext = lineContext,
                commandSpecs = commandSpecs,
            )
        return when (context.activePosition) {
            TerminalCompletionActivePosition.OPERATOR -> false
            TerminalCompletionActivePosition.COMMAND -> false
            TerminalCompletionActivePosition.SUBCOMMAND -> context.subcommandCandidateSource != null
            TerminalCompletionActivePosition.OPTION_NAME -> false
            TerminalCompletionActivePosition.OPTION_VALUE ->
                context.staticValueCandidates.isNotEmpty() ||
                    context.expectedPathKind != TerminalPathArgumentKind.NONE ||
                    context.expectedValueDomain != TerminalCompletionValueDomain.NONE
            TerminalCompletionActivePosition.POSITIONAL_ARGUMENT ->
                context.expectedPathKind != TerminalPathArgumentKind.NONE ||
                    context.expectedValueDomain != TerminalCompletionValueDomain.NONE
        }
    }

    private fun hasContextualValueCompletions(
        commandLine: String,
        lineContext: TerminalCommandLineContext,
        commandSpecs: List<TerminalCommandSpec>,
    ): Boolean {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = commandLine,
                lineContext = lineContext,
                commandSpecs = commandSpecs,
            )
        return context.activePosition == TerminalCompletionActivePosition.OPTION_VALUE &&
            (
                context.staticValueCandidates.isNotEmpty() ||
                    context.expectedPathKind != TerminalPathArgumentKind.NONE ||
                    context.expectedValueDomain != TerminalCompletionValueDomain.NONE
            )
    }

    private fun nonWhitespaceCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            if (!text[index].isWhitespace()) count++
            index++
        }
        return count
    }

    private const val OPTION_TERMINATOR = "--"
}
