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
import io.github.ketraterm.completion.internal.isTerminalCompletionUtf16Boundary
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalCommandLineTokenizerPropertyTest {
    @Test
    fun `generated malformed command lines preserve lexical invariants at every scalar cursor boundary`() {
        val random = Random(FUZZ_SEED)
        val commandLines =
            buildList {
                addAll(MALFORMED_CORPUS)
                repeat(GENERATED_LINE_COUNT) { add(randomCommandLine(random)) }
            }

        for (shellSyntax in TerminalShellSyntax.entries) {
            for ((caseIndex, commandLine) in commandLines.withIndex()) {
                for (cursorOffset in 0..commandLine.length) {
                    if (!commandLine.isTerminalCompletionUtf16Boundary(cursorOffset)) continue
                    val description = "seed=$FUZZ_SEED case=$caseIndex syntax=$shellSyntax cursor=$cursorOffset line=${commandLine.debug()}"

                    val context = TerminalCommandLineTokenizer.parse(commandLine, cursorOffset, shellSyntax)

                    assertEquals(
                        context,
                        TerminalCommandLineTokenizer.parse(commandLine, cursorOffset, shellSyntax),
                        "Tokenizer must be deterministic: $description",
                    )
                    assertContextInvariants(commandLine, cursorOffset, context, description)
                }
            }
        }
    }

    private fun assertContextInvariants(
        commandLine: String,
        cursorOffset: Int,
        context: TerminalCommandLineContext,
        description: String,
    ) {
        assertEquals(cursorOffset, context.cursorOffset, description)
        assertOrderedRange(context.segmentStartOffset, context.segmentEndOffset, commandLine, "segment", description)
        assertOrderedRange(
            context.replacementStartOffset,
            context.replacementEndOffset,
            commandLine,
            "replacement",
            description,
        )
        assertTrue(context.activeTokenIndex in 0..context.tokens.size, "Invalid active token index: $description")
        assertTrue(context.activePrefix.hasWellFormedUtf16(), "Active prefix split a surrogate pair: $description")

        var previousEnd = context.segmentStartOffset
        for ((tokenIndex, token) in context.tokens.withIndex()) {
            assertTrue(token.startOffset >= previousEnd, "Overlapping token $tokenIndex: $description")
            assertTrue(token.startOffset < token.endOffset, "Empty raw token range $tokenIndex: $description")
            assertTrue(token.startOffset >= context.segmentStartOffset, "Token before segment $tokenIndex: $description")
            assertTrue(token.endOffset <= context.segmentEndOffset, "Token after segment $tokenIndex: $description")
            assertTrue(token.text.hasWellFormedUtf16(), "Token $tokenIndex split a surrogate pair: $description")
            previousEnd = token.endOffset
        }

        when (context.cursorRegion) {
            TerminalCommandLineCursorRegion.OPERATOR -> {
                assertTrue(context.tokens.isEmpty(), "Operator context materialized tokens: $description")
                assertTrue(cursorOffset > context.segmentStartOffset, "Cursor is not inside operator: $description")
                assertTrue(cursorOffset < context.segmentEndOffset, "Cursor is not inside operator: $description")
                assertEquals(cursorOffset, context.replacementStartOffset, description)
                assertEquals(cursorOffset, context.replacementEndOffset, description)
            }
            TerminalCommandLineCursorRegion.SEGMENT -> {
                assertTrue(cursorOffset in context.segmentStartOffset..context.segmentEndOffset, "Cursor outside segment: $description")
                val activeTokenIndex = context.tokens.indexOfFirst { cursorOffset in it.startOffset..it.endOffset }
                if (activeTokenIndex >= 0) {
                    val activeToken = context.tokens[activeTokenIndex]
                    assertEquals(activeTokenIndex, context.activeTokenIndex, description)
                    assertEquals(activeToken.startOffset, context.replacementStartOffset, description)
                    assertEquals(activeToken.endOffset, context.replacementEndOffset, description)
                } else {
                    assertEquals(cursorOffset, context.replacementStartOffset, description)
                    assertEquals(cursorOffset, context.replacementEndOffset, description)
                }
            }
        }
    }

    private fun assertOrderedRange(
        startOffset: Int,
        endOffset: Int,
        commandLine: String,
        name: String,
        description: String,
    ) {
        assertTrue(startOffset in 0..commandLine.length, "$name start outside command line: $description")
        assertTrue(endOffset in 0..commandLine.length, "$name end outside command line: $description")
        assertTrue(startOffset <= endOffset, "$name range is reversed: $description")
        assertTrue(commandLine.isTerminalCompletionUtf16Boundary(startOffset), "$name start is not a scalar boundary: $description")
        assertTrue(commandLine.isTerminalCompletionUtf16Boundary(endOffset), "$name end is not a scalar boundary: $description")
    }

    private fun randomCommandLine(random: Random): String =
        buildString {
            repeat(random.nextInt(MAX_FRAGMENT_COUNT + 1)) {
                append(FRAGMENTS[random.nextInt(FRAGMENTS.size)])
            }
        }

    private fun String.hasWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index++
            }
        }
        return true
    }

    private fun String.debug(): String = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

    private companion object {
        private const val FUZZ_SEED = 0x4B455452
        private const val GENERATED_LINE_COUNT = 300
        private const val MAX_FRAGMENT_COUNT = 40

        private val FRAGMENTS =
            listOf(
                "a",
                "Z",
                "0",
                " ",
                "\t",
                "\r",
                "\n",
                "'",
                "\"",
                "\\",
                "`",
                "&",
                "|",
                ";",
                "-",
                "/",
                "=",
                "é",
                "中",
                "😀",
                "\u0301",
            )

        private val MALFORMED_CORPUS =
            listOf(
                "",
                "'",
                "\"",
                "\\",
                "`",
                "&&",
                "||",
                "|&",
                "git status && cd \"Idea Pro",
                "echo 'a && b' | rg \\|",
                "Get-Item `| Where-Object { \$_.Name -eq 'a''b' }",
                "echo 😀 && printf '中",
                "a;;;b||||c&&&d",
                "\"'\\`|&;😀",
            )
    }
}
