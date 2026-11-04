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
import kotlin.test.*

class TerminalCommandLineReplaySemanticsTest {
    @Test
    fun `literal identities ignore equivalent quoting and dollar escapes`() {
        for ((syntax, escaped) in listOf(TerminalShellSyntax.POSIX to "\\\$HOME", TerminalShellSyntax.POWERSHELL to "`\$HOME")) {
            val literal = token("'\$HOME'", syntax)
            val escapedLiteral = token(escaped, syntax)
            assertEquals(TerminalCommandLineTokenIdentity("\$HOME"), literal.identity)
            assertEquals(literal.identity, escapedLiteral.identity)
            assertTrue(literal.canReplay)
            assertTrue(escapedLiteral.canReplay)
        }
        for (syntax in TerminalShellSyntax.entries) {
            assertEquals(token("alpha", syntax).identity, token("'alpha'", syntax).identity)
            assertEquals(token("alpha", syntax).identity, token("\"alpha\"", syntax).identity)
        }
    }

    @Test
    fun `simple variables and mixed quotes retain raw source identity`() {
        for (syntax in listOf(TerminalShellSyntax.POSIX, TerminalShellSyntax.POWERSHELL)) {
            for (raw in listOf("\$HOME", "\"\$HOME\"", "prefix\"\$HOME\"/suffix", "--root=\"\$HOME\"")) {
                val context = parse("echo $raw", syntax)
                val token = context.tokens.last()
                assertEquals(raw, token.rawText)
                assertEquals(raw, token.identity.rawText)
                assertTrue(token.canReplay, raw)
                assertTrue(context.canReplay, raw)
            }
        }
        val scoped = token("\"\$env:USERPROFILE\"", TerminalShellSyntax.POWERSHELL)
        assertEquals("\"\$env:USERPROFILE\"", scoped.rawText)
        assertTrue(scoped.canReplay)
    }

    @Test
    fun `POSIX patterns and tilde expansions differ from literal paths`() {
        for (value in listOf("*.kt", "file?.txt", "[ab].kt", "~/repo")) {
            val expression = token(value, TerminalShellSyntax.POSIX)
            val literal = token("'$value'", TerminalShellSyntax.POSIX)
            assertEquals(value, expression.rawText)
            assertNull(literal.rawText)
            assertTrue(expression.canReplay)
            assertTrue(literal.canReplay)
        }
        assertNull(token("file~backup", TerminalShellSyntax.POSIX).rawText)
    }

    @Test
    fun `PowerShell control escapes retain raw meaning without evaluating them`() {
        val raw = "\"a`tb\""
        val expression = token(raw, TerminalShellSyntax.POWERSHELL)
        assertEquals("atb", expression.text)
        assertEquals(raw, expression.rawText)
        assertTrue(expression.canReplay)
        assertNull(token("'a`tb'", TerminalShellSyntax.POWERSHELL).rawText)
        assertNull(token("\"a``b\"", TerminalShellSyntax.POWERSHELL).rawText)
    }

    @Test
    fun `PowerShell array syntax is unsupported while quoted and escaped punctuation remains literal`() {
        for (line in listOf("echo first,second", "echo \$items[ 1 + 1 ]", "echo [int]1")) {
            for (cursor in listOf(0, line.length / 2, line.length)) {
                assertFalse(
                    TerminalCommandLineTokenizer.parse(line, cursor, TerminalShellSyntax.POWERSHELL).canReplay,
                    "$line at $cursor",
                )
            }
        }
        for (raw in listOf("'a,b'", "\"a,b\"", "a`,b", "'[a]'", "\"[a]\"", "`[a`]")) {
            val context = parse("echo $raw", TerminalShellSyntax.POWERSHELL)
            assertTrue(context.canReplay, raw)
            assertNull(context.tokens.last().rawText, raw)
        }
    }

    @Test
    fun `unsupported expressions disable complete row replay at every cursor position`() {
        for (syntax in listOf(TerminalShellSyntax.POSIX, TerminalShellSyntax.POWERSHELL)) {
            for (line in listOf(
                "echo \$(first; second) suffix",
                "echo \"\$(first second)\" suffix",
                "echo \${HOME} suffix",
                "echo first && echo second",
                "echo first | echo second",
                "echo first > output",
                "echo 'unfinished",
            )) {
                for (cursor in listOf(0, line.length / 2, line.length)) {
                    assertFalse(TerminalCommandLineTokenizer.parse(line, cursor, syntax).canReplay, "$syntax: $line at $cursor")
                }
            }
        }
        for (line in listOf("echo `date`", "echo \$'line\\n'", "echo tail\\", "echo \"!previous\"")) {
            assertFalse(parse(line, TerminalShellSyntax.POSIX).canReplay, line)
        }
        for (line in listOf("echo tail`", "echo @(first second)", "echo @values")) {
            assertFalse(parse(line, TerminalShellSyntax.POWERSHELL).canReplay, line)
        }
    }

    @Test
    fun `quoted and escaped operators remain literal and replayable`() {
        for ((syntax, escaped) in listOf(TerminalShellSyntax.POSIX to "\\", TerminalShellSyntax.POWERSHELL to "`")) {
            for (line in listOf("echo 'a; b | c > d'", "echo \"a; b | c > d\"", "echo a$escaped|b")) {
                val context = parse(line, syntax)
                assertTrue(context.canReplay, line)
                assertTrue(context.tokens.all { it.rawText == null && it.canReplay }, line)
            }
        }
    }

    @Test
    fun `plain syntax does not infer replay semantics for expressions`() {
        for (line in listOf("echo \$HOME", "echo '\$HOME'", "echo *.kt", "echo ~/repo", "echo \"a`tb\"")) {
            assertFalse(parse(line, TerminalShellSyntax.PLAIN).canReplay, line)
        }
        assertTrue(parse("echo 'ordinary text'", TerminalShellSyntax.PLAIN).canReplay)
    }

    private fun token(
        raw: String,
        syntax: TerminalShellSyntax,
    ): TerminalCommandLineToken = parse("echo $raw", syntax).tokens.last()

    private fun parse(
        line: String,
        syntax: TerminalShellSyntax,
    ): TerminalCommandLineContext = TerminalCommandLineTokenizer.parse(line, line.length, syntax)
}
