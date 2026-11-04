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
package io.github.ketraterm.completion.ranking

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class TerminalCompletionOutcomeKeyResolverTest {
    @Test
    fun `literal and differently quoted expansions keep distinct outcomes and decoded matching text`() {
        for (capabilities in dialects) {
            val request = request("echo $", capabilities)
            val context = TerminalCompletionContext.resolve(request)
            val replacements = listOf("'$homeVariable'", "\"$homeVariable\"", homeVariable)
            val candidates = replacements.map { candidate(it, context) }
            val outcomes = candidates.map { assertNotNull(resolver.resolve(request, it, context)) }

            assertEquals(3, outcomes.map { it.groupKey }.distinct().size)
            assertEquals(List(3) { homeVariable }, outcomes.map { it.activeValue })

            val ranking = GlobalCompletionRanker().createRequestState(request, context, 10, 0)
            ranking.ingest(CompletionSourceCandidates(0, 0, candidates = candidates))
            assertEquals(replacements.toSet(), ranking.rankedCandidates().map { it.replacementText }.toSet())
        }
    }

    @Test
    fun `equivalent literal quoting and escaping still share one outcome`() {
        for (capabilities in dialects) {
            val request = request("echo h", capabilities)
            val context = TerminalCompletionContext.resolve(request)
            val escape = if (capabilities == TerminalShellCapabilities.POSIX) '\\' else '`'
            val replacements = listOf("'hello world'", "\"hello world\"", "hello$escape world")
            val outcomes = replacements.map { assertNotNull(resolver.resolve(request, candidate(it, context), context)) }

            assertEquals(1, outcomes.map { it.groupKey }.distinct().size)
            assertEquals(List(3) { "hello world" }, outcomes.map { it.activeValue })

            val literalVariable = assertNotNull(resolver.resolve(request, candidate("'$homeVariable'", context), context))
            val escapedVariable = assertNotNull(resolver.resolve(request, candidate("$escape$homeVariable", context), context))
            assertEquals(literalVariable.groupKey, escapedVariable.groupKey)
        }
    }

    @Test
    fun `path normalization applies to literal values but preserves expression syntax`() {
        for (capabilities in dialects) {
            val request = request("cd ", capabilities)
            val context = TerminalCompletionContext.resolve(request)

            fun outcome(replacement: String) = assertNotNull(resolver.resolve(request, candidate(replacement, context), context))

            assertEquals(outcome("'project/'").groupKey, outcome("project").groupKey)
            val expression = outcome("\"$homeVariable/\"")
            assertNotEquals(expression.groupKey, outcome("\"$homeVariable\"").groupKey)
            assertEquals("$homeVariable/", expression.activeValue)
            assertNotEquals(expression.groupKey, outcome("'$homeVariable/'").groupKey)
        }
    }

    @Test
    fun `attached option values keep expression identity separate from active matching text`() {
        val specs =
            listOf(
                TerminalCommandSpec(
                    "tool",
                    options =
                        listOf(
                            TerminalOptionSpec(
                                listOf("--path"),
                                requiresValue = true,
                                valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                            ),
                        ),
                ),
            )
        for (capabilities in dialects) {
            val request = request("tool --path=$", capabilities)
            val context = TerminalCompletionContext.resolve(request, specs)
            val literal = assertNotNull(resolver.resolve(request, candidate("'$homeVariable'", context), context))
            val expression = assertNotNull(resolver.resolve(request, candidate("\"$homeVariable\"", context), context))

            assertNotEquals(literal.groupKey, expression.groupKey)
            assertEquals(homeVariable, literal.activeValue)
            assertEquals(homeVariable, expression.activeValue)
        }
    }

    @Test
    fun `unsupported command chains retain host candidates with exact full line identity`() {
        for (capabilities in dialects) {
            val request = request("", capabilities)
            val context = TerminalCompletionContext.resolve(request)
            val first = candidate("echo one && echo done", context)
            val second = candidate("echo two && echo done", context)
            val firstOutcome = assertNotNull(resolver.resolve(request, first, context))
            val secondOutcome = assertNotNull(resolver.resolve(request, second, context))

            assertNotEquals(firstOutcome.groupKey, secondOutcome.groupKey)
            assertEquals(
                firstOutcome.groupKey,
                assertNotNull(resolver.resolve(request, first.copy(source = "other"), context)).groupKey,
            )

            val ranking = GlobalCompletionRanker().createRequestState(request, context, 10, 0)
            ranking.ingest(CompletionSourceCandidates(0, 0, candidates = listOf(first, second)))
            assertEquals(
                setOf(first.replacementText, second.replacementText),
                ranking.rankedCandidates().map { it.replacementText }.toSet(),
            )
        }
    }

    private fun request(
        line: String,
        capabilities: TerminalShellCapabilities,
    ): TerminalCompletionRequest = TerminalCompletionRequest(line, line.length, shellCapabilities = capabilities)

    private fun candidate(
        replacement: String,
        context: TerminalCompletionContext,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacement,
            context.replacementStartOffset,
            context.replacementEndOffset,
            "host",
            TerminalCompletionCandidateKind.ARGUMENT,
        )

    private val resolver = TerminalCompletionOutcomeKeyResolver()
    private val dialects = listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)
    private val homeVariable = "\$HOME"
}
