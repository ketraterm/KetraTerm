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

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionEngines
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecCompletionEngineTest {
    @Test
    fun `empty command line suggests top-level commands`() {
        val candidates = engine().complete(request(""))

        assertEquals(listOf("git", "gradle"), candidates.map { it.replacementText })
        assertEquals(TerminalCompletionCandidateKind.COMMAND, candidates[0].kind)
        assertEquals(0, candidates[0].replacementStartOffset)
        assertEquals(0, candidates[0].replacementEndOffset)
    }

    @Test
    fun `command prefix filters top-level commands`() {
        val candidates = engine().complete(request("gr"))

        assertEquals(listOf("gradle"), candidates.map { it.replacementText })
        assertEquals(0, candidates.single().replacementStartOffset)
        assertEquals(2, candidates.single().replacementEndOffset)
    }

    @Test
    fun `exact command token does not return no-op command suggestion`() {
        val candidates = engine().complete(request("git"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `subcommand prefix resolves command path`() {
        val candidates = engine().complete(request("git c"))

        assertEquals(listOf("commit", "checkout"), candidates.map { it.replacementText })
        assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.SUBCOMMAND })
        assertEquals(4, candidates[0].replacementStartOffset)
        assertEquals(5, candidates[0].replacementEndOffset)
    }

    @Test
    fun `whitespace after command suggests subcommands with empty replacement range`() {
        val candidates = engine().complete(request("git "))

        assertEquals(listOf("status", "commit", "checkout"), candidates.map { it.replacementText })
        assertEquals(4, candidates[0].replacementStartOffset)
        assertEquals(4, candidates[0].replacementEndOffset)
    }

    @Test
    fun `exact subcommand token does not return no-op subcommand suggestion`() {
        val candidates = engine().complete(request("git status"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `option prefix returns command options`() {
        val candidates = engine().complete(request("git --"))

        assertEquals(listOf("--help", "--version"), candidates.map { it.replacementText })
        assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.OPTION })
    }

    @Test
    fun `exact option token does not return no-op option suggestion`() {
        val candidates = engine().complete(request("git --help"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `cursor inside token replaces the whole active token`() {
        val candidates = engine().complete(request("git che", cursorOffset = 6))

        assertEquals(listOf("checkout"), candidates.map { it.replacementText })
        assertEquals(4, candidates.single().replacementStartOffset)
        assertEquals(7, candidates.single().replacementEndOffset)
    }

    @Test
    fun `aliases resolve command paths but candidates use canonical names`() {
        val candidates = engine().complete(request("gradlew t"))

        assertEquals(listOf("test", "tasks"), candidates.map { it.replacementText })
    }

    @Test
    fun `quoted active token is unquoted for matching and replaced as one shell token`() {
        val candidates = engine().complete(request("git \"ch"))

        assertEquals(listOf("checkout"), candidates.map { it.replacementText })
        assertEquals(4, candidates.single().replacementStartOffset)
        assertEquals(7, candidates.single().replacementEndOffset)
    }

    @Test
    fun `max candidates caps returned results after ranking`() {
        val candidates = engine().complete(request("git ", maxCandidates = 2))

        assertEquals(listOf("status", "commit"), candidates.map { it.replacementText })
    }

    @Test
    fun `unknown command returns no command-body suggestions`() {
        assertTrue(engine().complete(request("unknown --")).isEmpty())
    }

    private fun engine(): TerminalCompletionEngine =
        TerminalCompletionEngines.fromSpecs(
            listOf(
                TerminalCommandSpec(
                    name = "git",
                    description = "version control",
                    subcommands =
                        listOf(
                            TerminalCommandSpec("status", "show status"),
                            TerminalCommandSpec("commit", "record changes"),
                            TerminalCommandSpec("checkout", "switch branches"),
                        ),
                    options =
                        listOf(
                            TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                            TerminalOptionSpec(listOf("--version"), "show version"),
                        ),
                ),
                TerminalCommandSpec(
                    name = "gradle",
                    aliases = listOf("gradlew", "./gradlew"),
                    subcommands =
                        listOf(
                            TerminalCommandSpec("test", "run tests"),
                            TerminalCommandSpec("tasks", "list tasks"),
                        ),
                ),
            ),
        )

    private fun request(
        commandLine: String,
        cursorOffset: Int = commandLine.length,
        maxCandidates: Int = 8,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = cursorOffset,
            maxCandidates = maxCandidates,
        )
}
