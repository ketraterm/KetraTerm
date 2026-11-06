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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedExpressionCompletionTest {
    @Test
    fun `literal and expanded variable arguments remain distinct after accepting suggestions`() =
        runTest {
            val recorded = listOf("echo '\$HOME'", "echo \"\$HOME\"", "echo \$HOME")
            for (shell in shells) {
                val completed = completedCommands(engine(recorded), "echo \$", shell)

                assertEquals(3, completed.size, shell.toString())
                assertTrue("echo \"\$HOME\"" in completed, shell.toString())
                assertTrue("echo \$HOME" in completed, shell.toString())
                val literal = completed.filterNot { it == "echo \"\$HOME\"" || it == "echo \$HOME" }.single()
                val escapedLiteral = if (shell == TerminalShellCapabilities.POSIX) "echo \\\$HOME" else "echo `\$HOME"
                assertTrue(literal in setOf("echo '\$HOME'", escapedLiteral), literal)
                val unfinishedQuote = completedCommands(engine(recorded), "echo \"$", shell)
                assertEquals(3, unfinishedQuote.size, "unfinished quotes must remain completable")
                assertTrue("echo \"\$HOME\"" in unfinishedQuote)
                assertTrue("echo \$HOME" in unfinishedQuote)
            }
        }

    @Test
    fun `equivalent escaped and single quoted literals still merge`() =
        runTest {
            for ((shell, escape) in listOf(TerminalShellCapabilities.POSIX to "\\", TerminalShellCapabilities.POWERSHELL to "`")) {
                val escaped = "$escape\$HOME"
                val recorded = listOf("echo '\$HOME'", "echo $escaped", "echo \"$escaped\"")
                val completed = completedCommands(engine(recorded), "echo \$", shell)

                assertEquals(1, completed.size, shell.toString())
                assertTrue(completed.single() in recorded, completed.single())
            }
        }

    @Test
    fun `mixed literal and variable fragments preserve the recorded shell syntax`() =
        runTest {
            val recorded = "echo prefix\"\$HOME\"/suffix"
            for (shell in shells) {
                assertEquals(
                    listOf(recorded),
                    completedCommands(engine(listOf(recorded)), "echo prefix\$", shell),
                    shell.toString(),
                )
            }
        }

    @Test
    fun `completed expression arguments do not match literal history contexts`() =
        runTest {
            val literal = "tool '\$HOME' --literal"
            val expanded = "tool \"\$HOME\" --expanded"
            for (shell in shells) {
                val engine = engine(listOf(literal, expanded))
                assertEquals(listOf(literal), completedCommands(engine, "tool '\$HOME' --", shell), shell.toString())
                assertEquals(listOf(expanded), completedCommands(engine, "tool \"\$HOME\" --", shell), shell.toString())
            }
        }

    @Test
    fun `attached option expressions retain their raw value and reject incompatible prefixes`() =
        runTest {
            val spec =
                TerminalCommandSpec(
                    "tool",
                    options = listOf(TerminalOptionSpec(listOf("--value"), requiresValue = true)),
                )
            val expression = "tool --value=\"\$HOME\""
            val quotedOption = "tool \"--value\"=\"\$HOME\""
            for (shell in shells) {
                assertEquals(
                    listOf(expression),
                    completedCommands(engine(listOf(expression), listOf(spec)), "tool --value=\$", shell),
                    shell.toString(),
                )
                assertEquals(
                    emptyList(),
                    completedCommands(engine(listOf(quotedOption), listOf(spec)), "tool --value=\$", shell),
                    shell.toString(),
                )
            }
        }

    @Test
    fun `unsupported substitutions and compound commands are omitted instead of replaying fragments`() =
        runTest {
            val cases =
                listOf(
                    TerminalShellCapabilities.POSIX to listOf("echo \$(printf value)", "echo `pwd`", "echo first && echo second"),
                    TerminalShellCapabilities.POWERSHELL to listOf("echo \$(Get-Location)", "echo first | echo second"),
                )
            for ((shell, recorded) in cases) {
                for (command in recorded) {
                    assertEquals(emptyList(), completedCommands(engine(listOf(command)), "echo ", shell), command)
                }
            }
        }

    @Test
    fun `PowerShell special escapes are replayed verbatim instead of decoded as ordinary letters`() =
        runTest {
            val special = "echo \"a`tb\""
            val literal = "echo atb"
            val completed = completedCommands(engine(listOf(special, literal)), "echo a", TerminalShellCapabilities.POWERSHELL)

            assertEquals(2, completed.size)
            assertEquals(setOf(special, literal), completed.toSet())
        }

    @Test
    fun `PowerShell array expressions are omitted while quoted and escaped punctuation remains replayable`() =
        runTest {
            val shell = TerminalShellCapabilities.POWERSHELL
            for (command in listOf("echo first,second", "echo \$items[ 1 + 1 ]", "echo [int]1")) {
                assertEquals(emptyList(), completedCommands(engine(listOf(command)), "echo ", shell), command)
            }
            val literalCases =
                listOf(
                    listOf("'a,b'", "\"a,b\"", "a`,b") to "a",
                    listOf("'[a]'", "\"[a]\"", "`[a`]") to "[",
                )
            for ((values, prefix) in literalCases) {
                val recorded = values.map { "echo $it" }
                val completed = completedCommands(engine(recorded), "echo $prefix", shell)
                assertEquals(1, completed.size, prefix)
                assertTrue(completed.single() in recorded, completed.single())
            }
        }

    private fun engine(
        recorded: List<String>,
        commandSpecs: List<TerminalCommandSpec> = emptyList(),
    ): TerminalCompletionEngine {
        val store = TerminalCompletionLearningStore()
        for ((index, command) in recorded.withIndex()) {
            store.recordCommandResult(command, true, null, null, index + 1L)
        }
        return TerminalCompletionEngines.fromSources(emptyList(), commandSpecs, store)
    }

    private suspend fun completedCommands(
        engine: TerminalCompletionEngine,
        line: String,
        shell: TerminalShellCapabilities,
    ): List<String> =
        engine.complete(TerminalCompletionRequest(line, line.length, shellCapabilities = shell)).map { candidate ->
            line.replaceRange(candidate.replacementStartOffset, candidate.replacementEndOffset, candidate.replacementText)
        }

    private val shells = listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)
}
