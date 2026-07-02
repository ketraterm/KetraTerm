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

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionContextResolverTest {
    @Test
    fun `git subcommand prefix resolves subcommand position`() {
        val context = resolve("git s")

        assertEquals(TerminalCompletionActivePosition.SUBCOMMAND, context.activePosition)
        assertEquals("git", context.command?.name)
        assertEquals(listOf("git"), context.commandPath.map { it.name })
        assertEquals("s", context.activePrefix)
    }

    @Test
    fun `git option prefix resolves option name position`() {
        val context = resolve("git -")

        assertEquals(TerminalCompletionActivePosition.OPTION_NAME, context.activePosition)
        assertEquals("-", context.activePrefix)
    }

    @Test
    fun `git directory option value resolves option value path context`() {
        val context = resolve("git -C ")

        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals("-C", context.activeOption?.names?.single())
        assertEquals(TerminalPathArgumentKind.DIRECTORY, context.expectedPathKind)
    }

    @Test
    fun `static option value candidates are exposed through context`() {
        val context = resolve("aws --output t")

        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals(listOf("json", "text", "table", "yaml", "yaml-stream"), context.staticValueCandidates)
        assertEquals("t", context.activePrefix)
    }

    @Test
    fun `quoted directory argument exposes path and quote context`() {
        val context = resolve("cd \"Idea")

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(TerminalPathArgumentKind.DIRECTORY, context.expectedPathKind)
        assertEquals('"', context.activeTokenQuote)
        assertEquals(3, context.replacementStartOffset)
    }

    @Test
    fun `unknown command pathlike token remains positional without expected path kind`() {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = "unknown ./s",
                cursorOffset = "unknown ./s".length,
                commandSpecs = specs,
            )

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(TerminalPathArgumentKind.NONE, context.expectedPathKind)
        assertEquals("./s", context.activePrefix)
    }

    private fun resolve(commandLine: String): TerminalCompletionContext =
        TerminalCompletionContextResolver.resolve(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            commandSpecs = specs,
        )

    private companion object {
        private val specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults()
    }
}
