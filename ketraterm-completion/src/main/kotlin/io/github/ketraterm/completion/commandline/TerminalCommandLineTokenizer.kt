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

internal data class TerminalCommandLineToken(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class TerminalCommandLineContext(
    val tokens: List<TerminalCommandLineToken>,
    val activeTokenIndex: Int,
    val activePrefix: String,
    val replacementStartOffset: Int,
    val replacementEndOffset: Int,
    val segmentStartOffset: Int,
    val segmentEndOffset: Int,
    val cursorRegion: TerminalCommandLineCursorRegion,
    val precededByOperator: Boolean,
    val cursorOffset: Int,
)

internal val TerminalCommandLineContext.commandStartOffset: Int
    get() = tokens.firstOrNull()?.startOffset ?: cursorOffset

internal val TerminalCommandLineContext.commandEndOffset: Int
    get() = tokens.lastOrNull()?.endOffset ?: cursorOffset

internal fun TerminalCommandLineContext.commandPrefix(commandLine: String): String = commandLine.substring(commandStartOffset, cursorOffset)

internal enum class TerminalCommandLineCursorRegion {
    SEGMENT,
    OPERATOR,
}

internal object TerminalCommandLineTokenizer {
    fun parse(
        commandLine: String,
        cursorOffset: Int,
        shellSyntax: TerminalShellSyntax = TerminalShellSyntax.PLAIN,
    ): TerminalCommandLineContext {
        val tokens = ArrayList<TerminalCommandLineToken>(INITIAL_TOKEN_CAPACITY)
        val tokenBuilder = StringBuilder()
        var index = 0
        var tokenStart = NO_OFFSET
        var quote = NO_QUOTE
        var activePrefix: String? = null
        var segmentStart = 0
        var segmentEnd = commandLine.length
        var precededByOperator = false

        while (index < commandLine.length) {
            if (tokenStart != NO_OFFSET && cursorOffset == index) {
                activePrefix = tokenBuilder.toString()
            }

            val ch = commandLine[index]
            if (quote == NO_QUOTE) {
                val operatorLength = ShellLexicalRules.operatorLength(commandLine, index, shellSyntax)
                if (operatorLength > 0) {
                    addToken(tokens, tokenBuilder, tokenStart, index)
                    tokenStart = NO_OFFSET
                    quote = NO_QUOTE
                    val operatorEnd = index + operatorLength
                    when {
                        cursorOffset <= index -> {
                            segmentEnd = index
                            break
                        }
                        cursorOffset < operatorEnd ->
                            return operatorContext(index, operatorEnd, cursorOffset, precededByOperator)
                        else -> {
                            tokens.clear()
                            activePrefix = null
                            segmentStart = operatorEnd
                            precededByOperator = true
                            index = operatorEnd
                            continue
                        }
                    }
                }

                if (ch.isShellWhitespace()) {
                    addToken(tokens, tokenBuilder, tokenStart, index)
                    tokenStart = NO_OFFSET
                    index++
                    continue
                }
            }

            if (tokenStart == NO_OFFSET) tokenStart = index

            when {
                quote == NO_QUOTE && (ch == SINGLE_QUOTE || ch == DOUBLE_QUOTE) -> {
                    quote = ch
                    index++
                }
                quote == ch && ShellLexicalRules.isDoubledQuote(commandLine, index, quote, shellSyntax) -> {
                    if (cursorOffset == index + 1) activePrefix = tokenBuilder.toString() + quote
                    tokenBuilder.append(quote)
                    index += 2
                }
                quote == ch -> {
                    quote = NO_QUOTE
                    index++
                }
                ShellLexicalRules.isEscapePair(commandLine, index, quote, shellSyntax) -> {
                    if (cursorOffset == index + 1) activePrefix = tokenBuilder.toString() + ch
                    tokenBuilder.append(commandLine[index + 1])
                    index += 2
                }
                else -> {
                    tokenBuilder.append(ch)
                    index++
                }
            }
        }

        if (tokenStart != NO_OFFSET && cursorOffset == commandLine.length) activePrefix = tokenBuilder.toString()
        addToken(tokens, tokenBuilder, tokenStart, segmentEnd)

        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val token = tokens[tokenIndex]
            if (cursorOffset in token.startOffset..token.endOffset) {
                return TerminalCommandLineContext(
                    tokens = tokens,
                    activeTokenIndex = tokenIndex,
                    activePrefix = if (cursorOffset >= token.endOffset) token.text else activePrefix.orEmpty(),
                    replacementStartOffset = token.startOffset,
                    replacementEndOffset = token.endOffset,
                    segmentStartOffset = segmentStart,
                    segmentEndOffset = segmentEnd,
                    cursorRegion = TerminalCommandLineCursorRegion.SEGMENT,
                    precededByOperator = precededByOperator,
                    cursorOffset = cursorOffset,
                )
            }
            if (cursorOffset < token.startOffset) break
            tokenIndex++
        }

        return TerminalCommandLineContext(
            tokens = tokens,
            activeTokenIndex = tokenIndex,
            activePrefix = "",
            replacementStartOffset = cursorOffset,
            replacementEndOffset = cursorOffset,
            segmentStartOffset = segmentStart,
            segmentEndOffset = segmentEnd,
            cursorRegion = TerminalCommandLineCursorRegion.SEGMENT,
            precededByOperator = precededByOperator,
            cursorOffset = cursorOffset,
        )
    }

    private fun operatorContext(
        operatorStart: Int,
        operatorEnd: Int,
        cursorOffset: Int,
        precededByOperator: Boolean,
    ): TerminalCommandLineContext =
        TerminalCommandLineContext(
            tokens = emptyList(),
            activeTokenIndex = 0,
            activePrefix = "",
            replacementStartOffset = cursorOffset,
            replacementEndOffset = cursorOffset,
            segmentStartOffset = operatorStart,
            segmentEndOffset = operatorEnd,
            cursorRegion = TerminalCommandLineCursorRegion.OPERATOR,
            precededByOperator = precededByOperator,
            cursorOffset = cursorOffset,
        )

    private fun addToken(
        tokens: MutableList<TerminalCommandLineToken>,
        builder: StringBuilder,
        startOffset: Int,
        endOffset: Int,
    ) {
        if (startOffset == NO_OFFSET) return
        tokens += TerminalCommandLineToken(builder.toString(), startOffset, endOffset)
        builder.setLength(0)
    }

    private fun Char.isShellWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private const val INITIAL_TOKEN_CAPACITY = 8
    private const val NO_OFFSET = -1
    private const val NO_QUOTE = ShellLexicalRules.NO_QUOTE
    private const val SINGLE_QUOTE = ShellLexicalRules.SINGLE_QUOTE
    private const val DOUBLE_QUOTE = ShellLexicalRules.DOUBLE_QUOTE
}
