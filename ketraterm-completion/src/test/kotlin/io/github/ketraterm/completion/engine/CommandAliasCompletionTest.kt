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
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandAliasCompletionTest {
    private val aliases = mapOf("g" to "git", "gs" to "g switch", "work" to "gs", "gr" to "git -C 'repo folder'")

    @Test
    fun `literal aliases chains and fixed arguments share semantic context with original edit offsets`() {
        val context = resolve("work fe")
        assertEquals(listOf("git", "switch"), context.commandPath.map { it.name })
        assertEquals(listOf("switch"), context.precedingArguments)
        assertEquals(TerminalCompletionValueDomain.GIT_BRANCH, context.expectedValueDomain)
        assertEquals(5, context.replacementStartOffset)
        assertEquals(7, context.replacementEndOffset)
        assertEquals(listOf("repo folder"), resolve("gr switch fe").precedingOptionValues("-C"))
        assertEquals(listOf("repo folder"), resolve("g 'repo folder' switch fe", mapOf("g" to "git -C")).precedingOptionValues("-C"))
    }

    @Test
    fun `self aliases add flags once and quoted replacement commands stop alias chaining`() {
        val context = resolve("git switch fe", mapOf("git" to "git -C 'repo folder'"))
        assertEquals(listOf("repo folder"), context.precedingOptionValues("-C"))
        assertEquals("switch", context.currentCommand?.name)
        val quoted = resolve("g switch fe", mapOf("g" to "'git'", "git" to "kubectl"))
        assertEquals("git", quoted.command?.name)
        assertEquals("kubectl", resolve("git get ", mapOf("git" to "kubectl")).command?.name)
    }

    @Test
    fun `only completed unquoted Bash command words are expanded`() {
        for (line in listOf("'g' switch fe", "\\g switch fe", "echo g switch fe")) {
            assertNull(resolve(line).command, line)
        }
        assertEquals(TerminalCompletionActivePosition.COMMAND, resolve("gs").activePosition)
        assertNull(resolve("gs").command)
        assertEquals("switch", resolve("printf hi | gs fe").currentCommand?.name)
        assertEquals("switch", resolve("LANG=C gs fe").currentCommand?.name)
        val powershell = TerminalCompletionRequest("git switch fe", 13, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
        assertEquals("git", TerminalCompletionContext.resolve(powershell, commandAliases = mapOf("git" to "kubectl")).command?.name)
    }

    @Test
    fun `unsupported definitions and cycles never fall back to the shadowed command spec`() {
        for (definition in listOf(
            "",
            "git ",
            "git; echo x",
            "git && echo x",
            "git > out",
            "git $(printf switch)",
            "git ${'$'}MODE",
            "git `pwd`",
            "git *",
            "git 'unterminated",
            "git\nstatus",
            "ENV=x git",
            "x".repeat(4097),
        )) {
            assertNull(resolve("git fe", mapOf("git" to definition)).command, definition)
        }
        assertNull(resolve("git fe", mapOf("git" to "g", "g" to "git")).command)
        val tooDeep = (0..17).associate { "g$it" to "g${it + 1}" } + ("g18" to "git")
        assertNull(resolve("g0 fe", tooDeep).command)
    }

    @Test
    fun `alias bounds accept the boundary and reject excess expansion`() {
        val chain = (0 until 16).associate { "g$it" to if (it == 15) "git" else "g${it + 1}" }
        assertEquals("switch", resolve("g0 switch fe", chain).currentCommand?.name)
        assertNull(resolve("extra switch fe", chain + ("extra" to "g0")).command)
        val directory = "x".repeat(4089)
        assertEquals(listOf(directory), resolve("g switch fe", mapOf("g" to "git -C $directory")).precedingOptionValues("-C"))
        assertEquals("git", resolve("g switch fe", mapOf("g" to "git" + " --no-pager".repeat(63))).command?.name)
        assertNull(resolve("g switch fe", mapOf("g" to "git" + " --no-pager".repeat(64))).command)
    }

    @Test
    fun `acceptance preserves alias pipeline suffix quotes and cursor-relative replacement`() =
        runTest {
            val engine = engine(aliases)
            for ((line, cursor, expected) in listOf(
                Triple("gs fe", 5, "gs feature/local"),
                Triple("printf hi | work fex --quiet", 19, "printf hi | work feature/local --quiet"),
                Triple("gs 'fe'", 6, "gs 'feature/local'"),
            )) {
                val candidate = engine.complete(request(line, cursor)).first { it.source == "git" }
                assertEquals(
                    expected,
                    line.replaceRange(candidate.replacementStartOffset, candidate.replacementEndOffset, candidate.replacementText),
                )
            }
        }

    @Test
    fun `alias supplied Git modes and refspecs use the shared Git source`() =
        runTest {
            val engine = engine(mapOf("gp" to "git push origin", "ga" to "git fetch --all"))
            val line = "gp +main:re"
            val candidate = engine.complete(request(line)).single()
            assertEquals(
                "gp +main:release",
                line.replaceRange(candidate.replacementStartOffset, candidate.replacementEndOffset, candidate.replacementText),
            )
            assertTrue(engine.complete(request("ga ")).isEmpty())
        }

    private fun engine(definitions: Map<String, String>) =
        TerminalCompletionEngines.fromSources(
            listOf(
                TerminalCompletionSourceEntry(
                    TerminalCompletionSources.git("git") { _, _ ->
                        TerminalGitCompletionSnapshot(
                            localBranches = listOf(TerminalCompletionDomainValue("feature/local")),
                            remoteBranches = listOf(TerminalCompletionDomainValue("origin/release")),
                        )
                    },
                ),
            ),
            shellProvider =
                object : TerminalCompletionShellProvider {
                    override fun commandAliases() = definitions

                    override suspend fun complete(
                        request: TerminalCompletionRequest,
                        context: TerminalCompletionContext,
                        limit: Int,
                    ): List<TerminalCompletionCandidate>? = null
                },
        )

    private fun resolve(
        line: String,
        definitions: Map<String, String> = aliases,
    ) = TerminalCompletionContext.resolve(request(line), commandAliases = definitions)

    private fun request(
        line: String,
        cursor: Int = line.length,
    ) = TerminalCompletionRequest(line, cursor, shellCapabilities = TerminalShellCapabilities.POSIX)
}
