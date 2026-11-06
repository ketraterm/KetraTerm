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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalCompletionOptionIdentityTest {
    @Test
    fun `Git source and staged options retain distinct value ownership in every dialect`() {
        for (capabilities in listOf(
            TerminalShellCapabilities.POSIX,
            TerminalShellCapabilities.POWERSHELL,
            TerminalShellCapabilities.PLAIN,
        )) {
            val source = resolve("git restore -s ", capabilities = capabilities)
            assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, source.activePosition)
            assertEquals(listOf("--source", "-s"), source.activeOption?.names)
            assertEquals(TerminalCompletionValueDomain.GIT_BRANCH, source.expectedValueDomain)

            val staged = resolve("git restore -S ", capabilities = capabilities)
            assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, staged.activePosition)
            assertNull(staged.activeOption)
            assertEquals(TerminalPathArgumentKind.FILE_OR_DIRECTORY, staged.expectedPathKind)
        }
    }

    @Test
    fun `separate and attached option values preserve case distinct aliases and order`() {
        val context = resolve("tool -n lower -N upper --name=next --Name=last tail", specs = listOf(tool))

        assertEquals(listOf("lower", "next"), context.precedingOptionValues(" -n "))
        assertEquals(listOf("lower", "next"), context.precedingOptionValues("--name"))
        assertEquals(listOf("upper", "last"), context.precedingOptionValues("-N"))
        assertEquals(listOf("upper", "last"), context.precedingOptionValues("--Name"))
        assertEquals(emptyList(), context.precedingOptionValues("--NAME"))
        assertEquals(emptyList(), context.precedingPositionalArguments)
    }

    @Test
    fun `attached active values use the exact option metadata and replacement range`() {
        val line = "tool --Name=up"
        val context = resolve(line, specs = listOf(tool))

        assertEquals(listOf("-N", "--Name"), context.activeOption?.names)
        assertEquals(listOf("upper"), context.staticValueCandidates)
        assertEquals("up", context.activePrefix)
        assertEquals(line.indexOf('=') + 1, context.replacementStartOffset)
        assertEquals(line.length, context.replacementEndOffset)
    }

    @Test
    fun `undeclared casing does not acquire option value ownership`() {
        val context = resolve("tool --NAME ", specs = listOf(tool))

        assertNull(context.activeOption)
        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(emptyList(), context.precedingOptionValues("--name"))
    }

    @Test
    fun `option suggestions preserve case distinct edits while matching case insensitively`() =
        runTest {
            val spec = TerminalCommandSpec("tool", options = listOf(TerminalOptionSpec(listOf("-n")), TerminalOptionSpec(listOf("-N"))))
            val engine = TerminalCompletionEngines.fromSources(emptyList(), listOf(spec))
            val request = TerminalCompletionRequest("tool -n", 7)
            val candidates = engine.completions(request).last()

            assertEquals(listOf("-N"), candidates.map { it.replacementText })
            assertEquals(5, candidates.single().replacementStartOffset)
            assertEquals(7, candidates.single().replacementEndOffset)
        }

    private fun resolve(
        line: String,
        capabilities: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
        specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ): TerminalCompletionContext =
        TerminalCompletionContext.resolve(TerminalCompletionRequest(line, line.length, shellCapabilities = capabilities), specs)

    private val tool =
        TerminalCommandSpec(
            name = "tool",
            options =
                listOf(
                    TerminalOptionSpec(listOf("-n", "--name"), requiresValue = true, valueCandidates = listOf("lower")),
                    TerminalOptionSpec(listOf("-N", "--Name"), requiresValue = true, valueCandidates = listOf("upper")),
                ),
        )
}
