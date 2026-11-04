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

/** Literal identities ignore equivalent quoting; expressions retain their exact shell spelling. */
internal data class TerminalCommandLineTokenIdentity(
    val text: String,
    val rawText: String? = null,
)

internal data class TerminalCommandLineToken(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val rawText: String? = null,
    val canReplay: Boolean = true,
) {
    val identity: TerminalCommandLineTokenIdentity get() = TerminalCommandLineTokenIdentity(text, rawText)
}

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
    /** False when the complete input contains syntax that learned replay cannot safely project. */
    val canReplay: Boolean = true,
)

internal val TerminalCommandLineContext.commandStartOffset: Int
    get() = tokens.firstOrNull()?.startOffset ?: cursorOffset

internal val TerminalCommandLineContext.commandEndOffset: Int
    get() = tokens.lastOrNull()?.endOffset ?: cursorOffset

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
        val bounds = locateSegmentBounds(commandLine, cursorOffset, shellSyntax)
        if (bounds.cursorInOperator) {
            return operatorContext(bounds.start, bounds.end, cursorOffset, bounds.precededByOperator)
        }
        return tokenizeSegment(
            commandLine = commandLine,
            cursorOffset = cursorOffset,
            segmentStart = bounds.start,
            segmentEnd = bounds.end,
            precededByOperator = bounds.precededByOperator,
            shellSyntax = shellSyntax,
            canReplay = bounds.canReplay,
        )
    }

    private data class SegmentBounds(
        val start: Int,
        val end: Int,
        val precededByOperator: Boolean,
        val cursorInOperator: Boolean = false,
        val canReplay: Boolean = true,
    )

    private fun locateSegmentBounds(
        commandLine: String,
        cursorOffset: Int,
        shellSyntax: TerminalShellSyntax,
    ): SegmentBounds {
        var index = 0
        var quote = NO_QUOTE
        var segmentStart = 0
        var segmentEnd = commandLine.length
        var precededByOperator = false
        var canReplay = true

        while (index < commandLine.length) {
            if (ShellLexicalRules.replayCharacter(commandLine, index, quote, shellSyntax) ==
                ShellLexicalRules.ReplayCharacter.UNSUPPORTED
            ) {
                canReplay = false
            }
            if (quote == NO_QUOTE) {
                val operatorLength = ShellLexicalRules.operatorLength(commandLine, index, shellSyntax)
                if (operatorLength > 0) {
                    canReplay = false
                    val operatorEnd = index + operatorLength
                    when {
                        cursorOffset <= index -> {
                            segmentEnd = index
                            break
                        }
                        cursorOffset < operatorEnd ->
                            return SegmentBounds(
                                start = index,
                                end = operatorEnd,
                                precededByOperator = precededByOperator,
                                cursorInOperator = true,
                                canReplay = false,
                            )
                        else -> {
                            segmentStart = operatorEnd
                            precededByOperator = true
                            index = operatorEnd
                            continue
                        }
                    }
                }
            }

            val previousQuote = quote
            quote = updateQuoteState(commandLine, index, previousQuote, shellSyntax)
            index += characterAdvance(commandLine, index, previousQuote, shellSyntax)
        }

        return SegmentBounds(
            start = segmentStart,
            end = segmentEnd,
            precededByOperator = precededByOperator,
            canReplay = canReplay && quote == NO_QUOTE,
        )
    }

    private fun updateQuoteState(
        commandLine: String,
        index: Int,
        quote: Char,
        shellSyntax: TerminalShellSyntax,
    ): Char {
        val ch = commandLine[index]
        return when {
            quote == NO_QUOTE && (ch == SINGLE_QUOTE || ch == DOUBLE_QUOTE) -> ch
            quote == ch && !ShellLexicalRules.isDoubledQuote(commandLine, index, quote, shellSyntax) -> NO_QUOTE
            else -> quote
        }
    }

    private fun characterAdvance(
        commandLine: String,
        index: Int,
        quote: Char,
        shellSyntax: TerminalShellSyntax,
    ): Int {
        val ch = commandLine[index]
        return when {
            quote == ch && ShellLexicalRules.isDoubledQuote(commandLine, index, quote, shellSyntax) -> 2
            ShellLexicalRules.isEscapePair(commandLine, index, quote, shellSyntax) -> 2
            else -> 1
        }
    }

    private fun tokenizeSegment(
        commandLine: String,
        cursorOffset: Int,
        segmentStart: Int,
        segmentEnd: Int,
        precededByOperator: Boolean,
        shellSyntax: TerminalShellSyntax,
        canReplay: Boolean,
    ): TerminalCommandLineContext {
        val (tokenList, activePrefix) = collectSegmentTokens(commandLine, cursorOffset, segmentStart, segmentEnd, shellSyntax)
        return resolveCursorTokenContext(
            tokenList = tokenList,
            activePrefix = activePrefix,
            cursorOffset = cursorOffset,
            segmentStart = segmentStart,
            segmentEnd = segmentEnd,
            precededByOperator = precededByOperator,
            canReplay = canReplay && tokenList.all { it.canReplay },
        )
    }

    private data class ParsedSegmentTokens(
        val tokens: List<TerminalCommandLineToken>,
        val activePrefix: String?,
    )

    private fun collectSegmentTokens(
        commandLine: String,
        cursorOffset: Int,
        segmentStart: Int,
        segmentEnd: Int,
        shellSyntax: TerminalShellSyntax,
    ): ParsedSegmentTokens {
        var tokens: MutableList<TerminalCommandLineToken>? = null
        var tokenBuilder: StringBuilder? = null
        var index = segmentStart
        var tokenStart = NO_OFFSET
        var quote = NO_QUOTE
        var activePrefix: String? = null
        var tokenNeedsRawText = false
        var tokenCanReplay = true

        while (index < segmentEnd) {
            if (tokenStart != NO_OFFSET && cursorOffset == index) {
                activePrefix = tokenBuilder!!.toString()
            }

            val ch = commandLine[index]
            if (quote == NO_QUOTE && ch.isShellWhitespace()) {
                tokens = addToken(tokens, tokenBuilder, tokenStart, index, commandLine, tokenNeedsRawText, tokenCanReplay)
                tokenStart = NO_OFFSET
                tokenBuilder = null
                tokenNeedsRawText = false
                tokenCanReplay = true
                index++
                continue
            }

            if (tokenStart == NO_OFFSET) {
                tokenStart = index
                tokenBuilder = StringBuilder()
            }

            when (ShellLexicalRules.replayCharacter(commandLine, index, quote, shellSyntax)) {
                ShellLexicalRules.ReplayCharacter.LITERAL -> Unit
                ShellLexicalRules.ReplayCharacter.EXPRESSION -> tokenNeedsRawText = true
                ShellLexicalRules.ReplayCharacter.UNSUPPORTED -> {
                    tokenNeedsRawText = true
                    tokenCanReplay = false
                }
            }

            when {
                quote == NO_QUOTE && (ch == SINGLE_QUOTE || ch == DOUBLE_QUOTE) -> {
                    quote = ch
                    index++
                }
                quote == ch && ShellLexicalRules.isDoubledQuote(commandLine, index, quote, shellSyntax) -> {
                    if (cursorOffset == index + 1) activePrefix = tokenBuilder!!.toString() + quote
                    tokenBuilder!!.append(quote)
                    index += 2
                }
                quote == ch -> {
                    quote = NO_QUOTE
                    index++
                }
                ShellLexicalRules.isEscapePair(commandLine, index, quote, shellSyntax) -> {
                    if (cursorOffset == index + 1) activePrefix = tokenBuilder!!.toString() + ch
                    tokenBuilder!!.append(commandLine[index + 1])
                    index += 2
                }
                else -> {
                    tokenBuilder!!.append(ch)
                    index++
                }
            }
        }

        if (tokenStart != NO_OFFSET && cursorOffset == segmentEnd) activePrefix = tokenBuilder!!.toString()
        tokens =
            addToken(
                tokens,
                tokenBuilder,
                tokenStart,
                segmentEnd,
                commandLine,
                tokenNeedsRawText || quote != NO_QUOTE,
                tokenCanReplay && quote == NO_QUOTE,
            )

        return ParsedSegmentTokens(tokens ?: emptyList(), activePrefix)
    }

    private fun resolveCursorTokenContext(
        tokenList: List<TerminalCommandLineToken>,
        activePrefix: String?,
        cursorOffset: Int,
        segmentStart: Int,
        segmentEnd: Int,
        precededByOperator: Boolean,
        canReplay: Boolean,
    ): TerminalCommandLineContext {
        var tokenIndex = 0
        while (tokenIndex < tokenList.size) {
            val token = tokenList[tokenIndex]
            if (cursorOffset in token.startOffset..token.endOffset) {
                return TerminalCommandLineContext(
                    tokens = tokenList,
                    activeTokenIndex = tokenIndex,
                    activePrefix = if (cursorOffset >= token.endOffset) token.text else activePrefix.orEmpty(),
                    replacementStartOffset = token.startOffset,
                    replacementEndOffset = token.endOffset,
                    segmentStartOffset = segmentStart,
                    segmentEndOffset = segmentEnd,
                    cursorRegion = TerminalCommandLineCursorRegion.SEGMENT,
                    precededByOperator = precededByOperator,
                    cursorOffset = cursorOffset,
                    canReplay = canReplay,
                )
            }
            if (cursorOffset < token.startOffset) break
            tokenIndex++
        }

        return TerminalCommandLineContext(
            tokens = tokenList,
            activeTokenIndex = tokenIndex,
            activePrefix = "",
            replacementStartOffset = cursorOffset,
            replacementEndOffset = cursorOffset,
            segmentStartOffset = segmentStart,
            segmentEndOffset = segmentEnd,
            cursorRegion = TerminalCommandLineCursorRegion.SEGMENT,
            precededByOperator = precededByOperator,
            cursorOffset = cursorOffset,
            canReplay = canReplay,
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
            canReplay = false,
        )

    private fun addToken(
        tokens: MutableList<TerminalCommandLineToken>?,
        builder: StringBuilder?,
        startOffset: Int,
        endOffset: Int,
        commandLine: String,
        needsRawText: Boolean,
        canReplay: Boolean,
    ): MutableList<TerminalCommandLineToken>? {
        if (startOffset == NO_OFFSET) return tokens
        val result = tokens ?: ArrayList(INITIAL_TOKEN_CAPACITY)
        result +=
            TerminalCommandLineToken(
                builder!!.toString(),
                startOffset,
                endOffset,
                rawText = if (needsRawText) commandLine.substring(startOffset, endOffset) else null,
                canReplay = canReplay,
            )
        return result
    }

    private fun Char.isShellWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private const val INITIAL_TOKEN_CAPACITY = 8
    private const val NO_OFFSET = -1
    private const val NO_QUOTE = ShellLexicalRules.NO_QUOTE
    private const val SINGLE_QUOTE = ShellLexicalRules.SINGLE_QUOTE
    private const val DOUBLE_QUOTE = ShellLexicalRules.DOUBLE_QUOTE
}
