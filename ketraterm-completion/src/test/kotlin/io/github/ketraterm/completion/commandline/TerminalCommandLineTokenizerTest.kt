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
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCommandLineTokenizerTest {
    @Test
    fun `cursor before an operator belongs to the left command segment`() {
        val commandLine = "git status && cd src"
        val context = parse(commandLine, commandLine.indexOf(" &&"))

        assertEquals(TerminalCommandLineCursorRegion.SEGMENT, context.cursorRegion)
        assertEquals(listOf("git", "status"), context.tokens.map { it.text })
        assertEquals("status", context.activePrefix)
        assertEquals(0, context.segmentStartOffset)
        assertEquals(commandLine.indexOf("&&"), context.segmentEndOffset)
    }

    @Test
    fun `cursor inside multi-character operator is an operator region`() {
        val commandLine = "git status && cd src"
        val operatorStart = commandLine.indexOf("&&")
        val context = parse(commandLine, operatorStart + 1)

        assertEquals(TerminalCommandLineCursorRegion.OPERATOR, context.cursorRegion)
        assertEquals(operatorStart, context.segmentStartOffset)
        assertEquals(operatorStart + 2, context.segmentEndOffset)
    }

    @Test
    fun `cursor after operator belongs to the right command segment`() {
        val commandLine = "git status && cd src"
        val cursorOffset = commandLine.indexOf("&&") + 2
        val context = parse(commandLine, cursorOffset)

        assertEquals(TerminalCommandLineCursorRegion.SEGMENT, context.cursorRegion)
        assertEquals(listOf("cd", "src"), context.tokens.map { it.text })
        assertEquals("", context.activePrefix)
        assertEquals(cursorOffset, context.segmentStartOffset)
        assertEquals(true, context.precededByOperator)
    }

    @Test
    fun `quoted and escaped operators do not split POSIX command segments`() {
        val quoted = "git 'status && ignored' && cd src"
        val quotedContext = parse(quoted, quoted.length)
        assertEquals(listOf("cd", "src"), quotedContext.tokens.map { it.text })

        val escaped = "git status \\| rg src"
        val escapedContext = parse(escaped, escaped.length)
        assertEquals(listOf("git", "status", "|", "rg", "src"), escapedContext.tokens.map { it.text })
        assertEquals(false, escapedContext.precededByOperator)
    }

    @Test
    fun `PowerShell backtick escapes pipeline operator`() {
        val commandLine = "Get-Item `| Where-Object"
        val context =
            TerminalCommandLineTokenizer.parse(
                commandLine,
                commandLine.length,
                TerminalShellSyntax.POWERSHELL,
            )

        assertEquals(listOf("Get-Item", "|", "Where-Object"), context.tokens.map { it.text })
        assertEquals(false, context.precededByOperator)
    }

    @Test
    fun `unclosed quote retains latest completed segment without failing`() {
        val commandLine = "git status && cd \"Idea Pro"
        val context = parse(commandLine, commandLine.length)

        assertEquals(TerminalCommandLineCursorRegion.SEGMENT, context.cursorRegion)
        assertEquals(listOf("cd", "Idea Pro"), context.tokens.map { it.text })
        assertEquals("Idea Pro", context.activePrefix)
        assertEquals(true, context.precededByOperator)
    }

    private fun parse(
        commandLine: String,
        cursorOffset: Int,
    ): TerminalCommandLineContext =
        TerminalCommandLineTokenizer.parse(
            commandLine,
            cursorOffset,
            TerminalShellSyntax.POSIX,
        )
}
