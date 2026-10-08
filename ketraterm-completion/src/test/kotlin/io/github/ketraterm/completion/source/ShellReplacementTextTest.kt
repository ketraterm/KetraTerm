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
import kotlin.test.*

class ShellReplacementTextTest {
    @Test
    fun `POSIX unquoted values protect operators and expansions`() {
        val cases =
            listOf(
                "a|b" to "a\\|b",
                "a<b" to "a\\<b",
                "a>b" to "a\\>b",
                "a||b&&c;d<(e)>f" to "a\\|\\|b\\&\\&c\\;d\\<\\(e\\)\\>f",
                "a*b" to "a\\*b",
                "a?b" to "a\\?b",
                "a[b]" to "a\\[b\\]",
                "{a,b}" to "\\{a,b\\}",
                "~" to "\\~",
                "~user" to "\\~user",
                "#note" to "\\#note",
                "a!b" to "a\\!b",
                "=ls" to "\\=ls",
                "^a" to "\\^a",
                "\$HOME" to "\\\$HOME",
                "\${HOME}" to "\\\$\\{HOME\\}",
                "\$(printf bad)" to "\\\$\\(printf\\ bad\\)",
                "\$((1+2))" to "\\\$\\(\\(1+2\\)\\)",
                "`printf bad`" to "\\`printf\\ bad\\`",
                "O'Brien" to "O\\'Brien",
                "a\"b" to "a\\\"b",
                "a\\b" to "a\\\\b",
                "a b\tc\rd" to "a\\ b\\\tc\\\rd",
            )

        for ((literal, expected) in cases) {
            assertEquals(expected, ShellReplacementText.encode(literal, '\u0000', TerminalShellQuotingPolicy.POSIX), literal)
            assertTrue(ShellReplacementText.canEncode(literal, '\u0000', TerminalShellQuotingPolicy.POSIX), literal)
            assertNull(ShellReplacementText.encode(literal, '\u0000', TerminalShellQuotingPolicy.CONSERVATIVE), literal)
            assertFalse(ShellReplacementText.canEncode(literal, '\u0000', TerminalShellQuotingPolicy.CONSERVATIVE), literal)
        }
    }

    @Test
    fun `POSIX unquoted newlines remain literal instead of becoming line continuations`() {
        assertEquals("'a\nb'", ShellReplacementText.encode("a\nb", '\u0000', TerminalShellQuotingPolicy.POSIX))
        assertEquals("'a'\\''\n\$b'", ShellReplacementText.encode("a'\n\$b", '\u0000', TerminalShellQuotingPolicy.POSIX))
    }

    @Test
    fun `POSIX double quoted values protect expansions and quote delimiters`() {
        val cases =
            listOf(
                "a|b<>*?[]{}~#" to "\"a|b<>*?[]{}~#\"",
                "\$HOME" to "\"\\\$HOME\"",
                "`printf bad`" to "\"\\`printf bad\\`\"",
                "a\\b\"c" to "\"a\\\\b\\\"c\"",
                "a!b" to "\"a\"\\!\"b\"",
                "!!" to "\"\"\\!\"\"\\!\"\"",
                "a\\!\$b" to "\"a\\\\\"\\!\"\\\$b\"",
                "a\nb" to "\"a\nb\"",
            )

        for ((literal, expected) in cases) {
            assertEquals(expected, ShellReplacementText.encode(literal, '"', TerminalShellQuotingPolicy.POSIX), literal)
            assertTrue(ShellReplacementText.canEncode(literal, '"', TerminalShellQuotingPolicy.POSIX), literal)
        }
    }

    @Test
    fun `POSIX single quoted values protect operators expansions and embedded quotes`() {
        val literal = "a|b<>*?[]{}~#!\$`\\'\n"
        assertEquals("'a|b<>*?[]{}~#!\$`\\'\\''\n'", ShellReplacementText.encode(literal, '\'', TerminalShellQuotingPolicy.POSIX))
        assertTrue(ShellReplacementText.canEncode(literal, '\'', TerminalShellQuotingPolicy.POSIX))
    }

    @Test
    fun `conservative double quotes reject expansion characters consistently`() {
        for (literal in listOf("a\"b", "a\$b", "a`b", "a!b")) {
            assertNull(ShellReplacementText.encode(literal, '"', TerminalShellQuotingPolicy.CONSERVATIVE), literal)
            assertFalse(ShellReplacementText.canEncode(literal, '"', TerminalShellQuotingPolicy.CONSERVATIVE), literal)
        }
    }

    @Test
    fun `PowerShell still single quotes unquoted shell syntax`() {
        val literal = "a|b<>*?[c]{d,e}~#!\$HOME`'"
        assertEquals("'a|b<>*?[c]{d,e}~#!\$HOME`'''", ShellReplacementText.encode(literal, '\u0000', TerminalShellQuotingPolicy.POWERSHELL))
        assertTrue(ShellReplacementText.canEncode(literal, '\u0000', TerminalShellQuotingPolicy.POWERSHELL))
    }

    @Test
    fun `safe unquoted values reuse the supplied string for every shell policy`() {
        val literal = "src/file-name_123.txt-中😀"
        for (policy in TerminalShellQuotingPolicy.entries) {
            assertSame(literal, ShellReplacementText.encode(literal, '\u0000', policy))
            assertTrue(ShellReplacementText.canEncode(literal, '\u0000', policy))
        }
    }
}
