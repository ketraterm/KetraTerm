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
package io.github.ketraterm.completion

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
)

internal object TerminalCommandLineTokenizer {
    fun parse(
        commandLine: String,
        cursorOffset: Int,
    ): TerminalCommandLineContext {
        val tokens = ArrayList<TerminalCommandLineToken>(INITIAL_TOKEN_CAPACITY)
        var index = 0
        while (index < commandLine.length) {
            while (index < commandLine.length && commandLine[index].isShellWhitespace()) index++
            if (index >= commandLine.length) break

            val start = index
            val builder = StringBuilder()
            var quote = NO_QUOTE
            while (index < commandLine.length) {
                val ch = commandLine[index]
                if (quote == NO_QUOTE && ch.isShellWhitespace()) break
                when {
                    quote == NO_QUOTE && (ch == SINGLE_QUOTE || ch == DOUBLE_QUOTE) -> {
                        quote = ch
                        index++
                    }
                    quote == ch -> {
                        quote = NO_QUOTE
                        index++
                    }
                    ch == BACKSLASH && quote != SINGLE_QUOTE && index + 1 < commandLine.length -> {
                        builder.append(commandLine[index + 1])
                        index += 2
                    }
                    else -> {
                        builder.append(ch)
                        index++
                    }
                }
            }
            tokens += TerminalCommandLineToken(builder.toString(), start, index)
        }

        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val token = tokens[tokenIndex]
            if (cursorOffset in token.startOffset..token.endOffset) {
                return TerminalCommandLineContext(
                    tokens = tokens,
                    activeTokenIndex = tokenIndex,
                    activePrefix = token.textPrefixAt(commandLine, cursorOffset),
                    replacementStartOffset = token.startOffset,
                    replacementEndOffset = token.endOffset,
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
        )
    }

    private fun TerminalCommandLineToken.textPrefixAt(
        commandLine: String,
        cursorOffset: Int,
    ): String {
        if (cursorOffset >= endOffset) return text
        return decodeTokenText(commandLine, startOffset, cursorOffset)
    }

    private fun decodeTokenText(
        commandLine: String,
        startOffset: Int,
        endOffset: Int,
    ): String {
        val builder = StringBuilder()
        var quote = NO_QUOTE
        var index = startOffset
        while (index < endOffset) {
            val ch = commandLine[index]
            when {
                quote == NO_QUOTE && (ch == SINGLE_QUOTE || ch == DOUBLE_QUOTE) -> {
                    quote = ch
                    index++
                }
                quote == ch -> {
                    quote = NO_QUOTE
                    index++
                }
                ch == BACKSLASH && quote != SINGLE_QUOTE && index + 1 < endOffset -> {
                    builder.append(commandLine[index + 1])
                    index += 2
                }
                else -> {
                    builder.append(ch)
                    index++
                }
            }
        }
        return builder.toString()
    }

    private fun Char.isShellWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private const val INITIAL_TOKEN_CAPACITY = 8
    private const val NO_QUOTE = '\u0000'
    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private const val BACKSLASH = '\\'
}
