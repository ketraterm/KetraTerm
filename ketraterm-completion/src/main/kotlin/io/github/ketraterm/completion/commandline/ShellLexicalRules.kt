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

/** Lexical rules for shell syntaxes supported by the shared completion engine. */
internal object ShellLexicalRules {
    /** Whether decoding this source character preserves its shell meaning during learned replay. */
    enum class ReplayCharacter {
        LITERAL,
        EXPRESSION,
        UNSUPPORTED,
    }

    /**
     * Classifies replay-sensitive syntax without evaluating expansions or extending token boundaries.
     * Substitutions and grouping need a richer grammar than this tokenizer and cannot be projected safely.
     */
    fun replayCharacter(
        commandLine: String,
        index: Int,
        quote: Char,
        syntax: TerminalShellSyntax,
    ): ReplayCharacter {
        val ch = commandLine[index]
        val next = commandLine.getOrNull(index + 1)
        if (syntax == TerminalShellSyntax.PLAIN && ch in "\$`~*?[]{}()<>|;&") return ReplayCharacter.UNSUPPORTED
        if (quote == SINGLE_QUOTE) return ReplayCharacter.LITERAL
        if (isEscapePair(commandLine, index, quote, syntax)) {
            return if (syntax == TerminalShellSyntax.POWERSHELL && next != null && next in "0abefnrtuv") {
                ReplayCharacter.EXPRESSION
            } else {
                ReplayCharacter.LITERAL
            }
        }
        if (ch == '\\' && syntax != TerminalShellSyntax.POWERSHELL && index == commandLine.lastIndex) {
            return ReplayCharacter.UNSUPPORTED
        }
        if (ch == '$') {
            return if (next == '(' || next == '{' || next == SINGLE_QUOTE || next == DOUBLE_QUOTE) {
                ReplayCharacter.UNSUPPORTED
            } else {
                ReplayCharacter.EXPRESSION
            }
        }
        if (ch == BACKTICK) return ReplayCharacter.UNSUPPORTED
        if (syntax == TerminalShellSyntax.POSIX && ch == '!') return ReplayCharacter.UNSUPPORTED
        if (quote == DOUBLE_QUOTE) return ReplayCharacter.LITERAL
        if (syntax == TerminalShellSyntax.POWERSHELL && ch in "[],") return ReplayCharacter.UNSUPPORTED
        if (ch in "(){}<>|;&") return ReplayCharacter.UNSUPPORTED
        val atWordStart = index == 0 || commandLine[index - 1].isWhitespace()
        if (syntax == TerminalShellSyntax.POSIX) {
            if (ch in "*?[") return ReplayCharacter.EXPRESSION
            if (ch == '~' && (atWordStart || commandLine[index - 1] == '=' || commandLine[index - 1] == ':')) {
                return ReplayCharacter.EXPRESSION
            }
        }
        if (ch == '#' && atWordStart) return ReplayCharacter.UNSUPPORTED
        if (syntax == TerminalShellSyntax.POWERSHELL &&
            ch == '@' &&
            (atWordStart || next == '(' || next == '{' || next == SINGLE_QUOTE || next == DOUBLE_QUOTE)
        ) {
            return ReplayCharacter.UNSUPPORTED
        }
        return ReplayCharacter.LITERAL
    }

    fun operatorLength(
        commandLine: String,
        index: Int,
        syntax: TerminalShellSyntax,
    ): Int =
        when (syntax) {
            TerminalShellSyntax.PLAIN -> 0
            TerminalShellSyntax.POSIX ->
                when (commandLine[index]) {
                    AMPERSAND -> if (commandLine.getOrNull(index + 1) == AMPERSAND) 2 else 1
                    PIPE -> if (commandLine.getOrNull(index + 1) == PIPE) 2 else 1
                    SEMICOLON -> 1
                    else -> 0
                }
            TerminalShellSyntax.POWERSHELL ->
                when (commandLine[index]) {
                    AMPERSAND -> if (commandLine.getOrNull(index + 1) == AMPERSAND) 2 else 0
                    PIPE -> if (commandLine.getOrNull(index + 1) == PIPE) 2 else 1
                    SEMICOLON -> 1
                    else -> 0
                }
        }

    fun isEscapePair(
        commandLine: String,
        index: Int,
        quote: Char,
        syntax: TerminalShellSyntax,
    ): Boolean {
        if (index + 1 >= commandLine.length || quote == SINGLE_QUOTE) return false
        val ch = commandLine[index]
        val next = commandLine[index + 1]
        return when (syntax) {
            TerminalShellSyntax.POWERSHELL -> ch == BACKTICK
            TerminalShellSyntax.PLAIN -> ch == BACKSLASH
            TerminalShellSyntax.POSIX ->
                ch == BACKSLASH &&
                    (
                        quote != DOUBLE_QUOTE ||
                            next == DOLLAR ||
                            next == BACKTICK ||
                            next == DOUBLE_QUOTE ||
                            next == BACKSLASH ||
                            next == NEWLINE
                    )
        }
    }

    fun isDoubledQuote(
        commandLine: String,
        index: Int,
        quote: Char,
        syntax: TerminalShellSyntax,
    ): Boolean =
        syntax == TerminalShellSyntax.POWERSHELL &&
            quote != NO_QUOTE &&
            commandLine.getOrNull(index + 1) == quote

    const val NO_QUOTE: Char = '\u0000'
    const val SINGLE_QUOTE: Char = '\''
    const val DOUBLE_QUOTE: Char = '"'

    private const val BACKSLASH = '\\'
    private const val BACKTICK = '`'
    private const val DOLLAR = '$'
    private const val NEWLINE = '\n'
    private const val AMPERSAND = '&'
    private const val PIPE = '|'
    private const val SEMICOLON = ';'
}
