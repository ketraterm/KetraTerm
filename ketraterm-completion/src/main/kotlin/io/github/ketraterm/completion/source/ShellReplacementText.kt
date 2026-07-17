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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalShellQuotingPolicy

/**
 * Encodes literal completion values for insertion into the active shell token.
 *
 * This helper is pure and shared by path and dynamic-domain sources so both
 * features apply identical quoting and escaping rules.
 */
internal object ShellReplacementText {
    /**
     * Encodes [value] for the current quote context and shell policy.
     *
     * @param value literal, unescaped value supplied by a completion provider.
     * @param activeTokenQuote quote character opening the active token, or the
     * NUL character when the token is unquoted.
     * @param policy shell-specific quoting and escaping policy.
     * @return replacement-safe text, or `null` when [policy] cannot represent
     * [value] safely in the current quote context.
     */
    fun encode(
        value: String,
        activeTokenQuote: Char,
        policy: TerminalShellQuotingPolicy,
    ): String? =
        when (activeTokenQuote) {
            SINGLE_QUOTE -> quoteSingle(value, policy)
            DOUBLE_QUOTE -> quoteDouble(value, policy)
            else -> unquoted(value, policy)
        }

    private fun unquoted(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): String? {
        if (!needsEscaping(value, policy)) return value
        return when (policy) {
            TerminalShellQuotingPolicy.CONSERVATIVE -> null
            TerminalShellQuotingPolicy.POSIX -> escapePosixUnquoted(value)
            TerminalShellQuotingPolicy.POWERSHELL -> quoteSingle(value, policy)
        }
    }

    private fun quoteSingle(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): String? =
        when (policy) {
            TerminalShellQuotingPolicy.CONSERVATIVE ->
                if (value.contains(SINGLE_QUOTE)) null else "'$value'"

            TerminalShellQuotingPolicy.POWERSHELL -> "'${value.replace("'", "''")}'"
            TerminalShellQuotingPolicy.POSIX -> "'${value.replace("'", "'\\''")}'"
        }

    private fun quoteDouble(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): String? =
        when (policy) {
            TerminalShellQuotingPolicy.CONSERVATIVE ->
                if (value.any { it == DOUBLE_QUOTE || it == DOLLAR || it == BACKTICK }) null else "\"$value\""

            TerminalShellQuotingPolicy.POWERSHELL ->
                buildString(value.length + 2) {
                    append(DOUBLE_QUOTE)
                    for (ch in value) {
                        if (ch == DOUBLE_QUOTE || ch == BACKTICK || ch == DOLLAR) append(BACKTICK)
                        append(ch)
                    }
                    append(DOUBLE_QUOTE)
                }

            TerminalShellQuotingPolicy.POSIX ->
                buildString(value.length + 2) {
                    append(DOUBLE_QUOTE)
                    for (ch in value) {
                        if (ch == DOUBLE_QUOTE || ch == BACKSLASH || ch == DOLLAR || ch == BACKTICK) append(BACKSLASH)
                        append(ch)
                    }
                    append(DOUBLE_QUOTE)
                }
        }

    private fun escapePosixUnquoted(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                if (ch.needsPosixUnquotedEscape()) append(BACKSLASH)
                append(ch)
            }
        }

    private fun needsEscaping(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): Boolean =
        when (policy) {
            TerminalShellQuotingPolicy.POSIX -> value.any { it.needsPosixUnquotedEscape() }
            TerminalShellQuotingPolicy.POWERSHELL -> value.any { it.needsPowerShellUnquotedEscape() }
            TerminalShellQuotingPolicy.CONSERVATIVE ->
                value.any { it.needsPosixUnquotedEscape() || it.needsPowerShellUnquotedEscape() }
        }

    private fun Char.needsPosixUnquotedEscape(): Boolean =
        isShellWhitespace() ||
                this == SINGLE_QUOTE ||
                this == DOUBLE_QUOTE ||
                this == BACKSLASH ||
                this == DOLLAR ||
                this == BACKTICK ||
                this == SEMICOLON ||
                this == AMPERSAND ||
                this == LEFT_PAREN ||
                this == RIGHT_PAREN

    private fun Char.needsPowerShellUnquotedEscape(): Boolean =
        isShellWhitespace() ||
                this == BACKTICK ||
                this == DOLLAR ||
                this == SINGLE_QUOTE ||
                this == DOUBLE_QUOTE ||
                this == LEFT_PAREN ||
                this == RIGHT_PAREN ||
                this == LEFT_BRACE ||
                this == RIGHT_BRACE ||
                this == LEFT_BRACKET ||
                this == RIGHT_BRACKET ||
                this == LESS_THAN ||
                this == GREATER_THAN ||
                this == PIPE ||
                this == AMPERSAND ||
                this == SEMICOLON ||
                this == COMMA ||
                this == AT ||
                this == HASH

    private fun Char.isShellWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private const val BACKSLASH = '\\'
    private const val DOLLAR = '$'
    private const val BACKTICK = '`'
    private const val SEMICOLON = ';'
    private const val AMPERSAND = '&'
    private const val LEFT_PAREN = '('
    private const val RIGHT_PAREN = ')'
    private const val LEFT_BRACE = '{'
    private const val RIGHT_BRACE = '}'
    private const val LEFT_BRACKET = '['
    private const val RIGHT_BRACKET = ']'
    private const val LESS_THAN = '<'
    private const val GREATER_THAN = '>'
    private const val PIPE = '|'
    private const val COMMA = ','
    private const val AT = '@'
    private const val HASH = '#'
}
