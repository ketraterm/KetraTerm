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
        if (isLiveTrigger(commandLine, cursorOffset, commandSpecs, lineContext)) return true
        return lineContext.commandPrefix(commandLine).count { !it.isWhitespace() } >= minimumNonWhitespaceCharacters
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

        if (lastChar == '-' || lastChar == '/' || lastChar == '\\' || lastChar == '$') return true
        if (lastChar != '=' && lastChar != ' ') return false
        val previousChar = commandLine.getOrNull(cursorOffset - 2)
        if (lastChar == ' ' && (previousChar == null || previousChar == ' ')) return false

        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = commandLine,
                lineContext = lineContext,
                commandSpecs = commandSpecs,
            )
        if (lastChar == '=') {
            return context.activePosition == TerminalCompletionActivePosition.OPTION_VALUE && context.hasValueCandidates()
        }
        return when (context.activePosition) {
            TerminalCompletionActivePosition.OPERATOR -> false
            TerminalCompletionActivePosition.COMMAND -> false
            TerminalCompletionActivePosition.SUBCOMMAND -> context.subcommandCandidateSource != null
            TerminalCompletionActivePosition.OPTION_NAME -> false
            TerminalCompletionActivePosition.OPTION_VALUE,
            TerminalCompletionActivePosition.POSITIONAL_ARGUMENT,
            -> context.hasValueCandidates()
        }
    }

    private fun TerminalCompletionContext.hasValueCandidates(): Boolean =
        staticValueCandidates.isNotEmpty() ||
            expectedPathKind != TerminalPathArgumentKind.NONE ||
            expectedValueDomain != TerminalCompletionValueDomain.NONE
}
