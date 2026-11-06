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
package io.github.ketraterm.completion.spec

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.model.TerminalArgumentSpec
import io.github.ketraterm.completion.model.TerminalCommandSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SpecCommandCollisionTest {
    @Test
    fun `command name shadowed by an alias uses the resolved command metadata`() =
        runTest {
            val winner =
                TerminalCommandSpec(
                    "wrapper",
                    "wrapper command",
                    aliases = listOf("tool"),
                    positionalArguments = listOf(TerminalArgumentSpec("target")),
                )
            val shadowed =
                TerminalCommandSpec(
                    "tool",
                    "shadowed command",
                    positionalArguments = listOf(TerminalArgumentSpec("file")),
                )
            val specs = listOf(winner, shadowed)
            val candidate = complete(specs, "to").single()

            assertEquals("tool", candidate.replacementText)
            assertEquals("tool", candidate.displayText)
            assertEquals("wrapper command", candidate.detail)
            assertEquals("<target>", candidate.argumentHint)
            assertSame(winner, resolve(specs, "tool ").command)
        }

    @Test
    fun `subcommand name shadowed by an alias uses the resolved subcommand metadata`() =
        runTest {
            val winner =
                TerminalCommandSpec(
                    "execute",
                    "execute task",
                    aliases = listOf("run"),
                    positionalArguments = listOf(TerminalArgumentSpec("task")),
                )
            val shadowed =
                TerminalCommandSpec(
                    "run",
                    "shadowed task",
                    positionalArguments = listOf(TerminalArgumentSpec("file")),
                )
            val specs = listOf(TerminalCommandSpec("tool", subcommands = listOf(winner, shadowed)))
            val candidate = complete(specs, "tool ru").single()

            assertEquals("run", candidate.replacementText)
            assertEquals("execute task", candidate.detail)
            assertEquals("<task>", candidate.argumentHint)
            assertSame(winner, resolve(specs, "tool run ").commandPath.last())
            assertEquals(
                "tool run",
                "tool ru".replaceRange(
                    candidate.replacementStartOffset,
                    candidate.replacementEndOffset,
                    candidate.replacementText,
                ),
            )
        }

    @Test
    fun `duplicate command names do not consume the source limit`() =
        runTest {
            val first = TerminalCommandSpec("alpha", "first definition")
            val commands =
                listOf(
                    first,
                    first,
                    TerminalCommandSpec("ALPHA", "shadowed definition"),
                    TerminalCommandSpec("beta", "other command"),
                )
            for ((specs, line) in listOf(
                commands to "",
                listOf(TerminalCommandSpec("tool", subcommands = commands)) to "tool ",
            )) {
                val candidates = complete(specs, line, limit = 2)
                assertEquals(listOf("alpha", "beta"), candidates.map { it.replacementText }, line)
                assertEquals(listOf("first definition", "other command"), candidates.map { it.detail }, line)
            }
        }

    @Test
    fun `used repeatable subcommand suppresses a canonical name owned by its alias`() =
        runTest {
            val task = TerminalCommandSpec("build", "build task", aliases = listOf("assemble"))
            val specs =
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        subcommands = listOf(task, TerminalCommandSpec("assemble", "shadowed task"), TerminalCommandSpec("test")),
                        repeatableSubcommands = true,
                    ),
                )

            assertEquals(listOf("test"), complete(specs, "tool build ").map { it.replacementText })
            assertEquals(listOf("test"), complete(specs, "tool assemble ").map { it.replacementText })
        }

    private suspend fun complete(
        specs: List<TerminalCommandSpec>,
        line: String,
        limit: Int = 64,
    ): List<TerminalCompletionCandidate> {
        val request = TerminalCompletionRequest(line, line.length)
        return SpecCompletionSource(specs).complete(request, TerminalCompletionContext.resolve(request, specs), limit)
    }

    private fun resolve(
        specs: List<TerminalCommandSpec>,
        line: String,
    ): TerminalCompletionContext = TerminalCompletionContext.resolve(TerminalCompletionRequest(line, line.length), specs)
}
