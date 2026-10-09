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

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.*

class TerminalCompletionContextResolutionTest {
    @Test
    fun `default resolution uses bundled command specifications`() {
        val context = TerminalCompletionContext.resolve(request("git switch fe"))

        assertEquals(listOf("git", "switch"), context.commandPath.map { it.name })
        assertEquals(TerminalCompletionValueDomain.GIT_BRANCH, context.expectedValueDomain)
        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals("fe", context.activePrefix)
        assertEquals(11, context.replacementStartOffset)
        assertEquals(13, context.replacementEndOffset)
    }

    @Test
    fun `caller catalog is authoritative and preserves aliases and inherited options`() {
        val context = TerminalCompletionContext.resolve(request("t g -c prod --choice fi"), catalog)

        assertSame(tool, context.command)
        assertSame(tool.subcommands.single(), context.currentCommand)
        assertEquals(listOf("tool", "get"), context.commandPath.map { it.name })
        assertEquals(listOf("prod"), context.precedingOptionValues("--context"))
        assertEquals(domain, context.expectedValueDomain)
        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals(listOf("first", "final"), context.staticValueCandidates)
        assertEquals(setOf("cluster"), context.usedOptionExclusiveGroupIds)

        val withoutCatalog = TerminalCompletionContext.resolve(request("t g -c prod --choice fi"), emptyList())
        assertNull(withoutCatalog.command)
        assertNull(withoutCatalog.activeOption)
        assertEquals(TerminalCompletionValueDomain.NONE, withoutCatalog.expectedValueDomain)
        assertEquals(listOf("g", "-c", "prod", "--choice"), withoutCatalog.precedingArguments)
    }

    @Test
    fun `empty and incomplete input produces coherent partial contexts`() {
        val empty = TerminalCompletionContext.resolve(request(""), catalog)
        assertEquals(TerminalCompletionActivePosition.COMMAND, empty.activePosition)
        assertEquals("", empty.activePrefix)
        assertEquals(0, empty.replacementStartOffset)
        assertEquals(0, empty.replacementEndOffset)
        assertEquals(emptyList(), empty.precedingArguments)

        val pendingLine = "tool --choice "
        val pending = TerminalCompletionContext.resolve(request(pendingLine), catalog)
        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, pending.activePosition)
        assertEquals("--choice", pending.activeOption?.names?.single())
        assertEquals(domain, pending.expectedValueDomain)
        assertEquals("", pending.activePrefix)
        assertEquals(pendingLine.length, pending.replacementStartOffset)
        assertEquals(pendingLine.length, pending.replacementEndOffset)

        val incompleteLine = "tool --choice=\"fi"
        val incomplete = TerminalCompletionContext.resolve(request(incompleteLine), catalog)
        assertEquals("fi", incomplete.activePrefix)
        assertEquals('"', incomplete.activeTokenQuote)
        assertEquals(incompleteLine.indexOf('=') + 1, incomplete.replacementStartOffset)
        assertEquals(incompleteLine.length, incomplete.replacementEndOffset)
        assertEquals(emptyList(), incomplete.precedingOptionValues("--choice"))
    }

    @Test
    fun `catalog replacement for later requests preserves retained context metadata`() {
        val changingCatalog = mutableListOf(tool)
        val request = request("tool --context prod --choice fi")
        val retained = TerminalCompletionContext.resolve(request, changingCatalog)
        val nextDomain = TerminalCompletionValueDomain("replacement-values")
        changingCatalog[0] =
            tool.copy(
                options =
                    tool.options.map { option ->
                        if ("--choice" in option.names) {
                            option.copy(valueDomain = nextDomain, valueCandidates = listOf("different"))
                        } else {
                            option
                        }
                    },
            )

        val next = TerminalCompletionContext.resolve(request, changingCatalog)

        assertSame(tool, retained.command)
        assertEquals(domain, retained.expectedValueDomain)
        assertEquals(listOf("first", "final"), retained.staticValueCandidates)
        assertEquals(listOf("prod"), retained.precedingOptionValues("--context"))
        assertEquals(nextDomain, next.expectedValueDomain)
        assertEquals(listOf("different"), next.staticValueCandidates)
    }

    @Test
    fun `mid line cursor preserves complete replacement span and excludes later arguments`() {
        val line = "tool --context 😀 --choice=\"first\" --context future"
        val context =
            TerminalCompletionContext.resolve(
                request(line).copy(cursorOffset = line.indexOf("first") + 2),
                catalog,
            )

        assertEquals("fi", context.activePrefix)
        assertEquals('"', context.activeTokenQuote)
        assertEquals("\"first\"", line.substring(context.replacementStartOffset, context.replacementEndOffset))
        assertEquals(listOf("😀"), context.precedingOptionValues("--context"))
        assertEquals(listOf("--context", "😀"), context.precedingArguments)
    }

    @ParameterizedTest
    @EnumSource(TerminalShellSyntax::class)
    fun `resolution follows captured syntax rather than quoting policy or profile`(syntax: TerminalShellSyntax) {
        val line = "tool --context before | tool --context after --choice fi"
        val request =
            request(line).copy(
                profileId = "arbitrary-shell",
                shellCapabilities = TerminalShellCapabilities(syntax, TerminalShellQuotingPolicy.POSIX),
            )
        val context = TerminalCompletionContext.resolve(request, catalog)

        assertEquals(
            if (syntax == TerminalShellSyntax.PLAIN) listOf("before", "after") else listOf("after"),
            context.precedingOptionValues("--context"),
        )
        assertEquals("fi", context.activePrefix)
        assertEquals(domain, context.expectedValueDomain)
        assertEquals(line.lastIndexOf("fi"), context.replacementStartOffset)
    }

    @Test
    fun `operator context signals custom engines to skip evaluation`() {
        val line = "tool --choice fi && tool next"
        val cursor = line.indexOf("&&") + 1
        val context = TerminalCompletionContext.resolve(request(line).copy(cursorOffset = cursor), catalog)

        assertEquals(TerminalCompletionActivePosition.OPERATOR, context.activePosition)
        assertNull(context.command)
        assertEquals(emptyList(), context.commandPath)
        assertEquals(emptyList(), context.precedingArguments)
        assertEquals(cursor, context.replacementStartOffset)
        assertEquals(cursor, context.replacementEndOffset)
    }

    @Test
    fun `context owned collections reject mutation without changing resolved fields`() {
        val context = TerminalCompletionContext.resolve(request("tool get --context prod --choice fi"), catalog)
        val before = publicFields(context)

        assertFailsWith<UnsupportedOperationException> { (context.commandPath as MutableList<TerminalCommandSpec>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (context.usedOptionExclusiveGroupIds as MutableSet<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (context.staticValueCandidates as MutableList<String>)[0] = "changed"
        }
        assertEquals(before, publicFields(context))
        assertEquals(listOf("first", "final"), context.activeOption?.valueCandidates)
        assertEquals("get", context.currentCommand?.name)
    }

    @Test
    fun `custom engine can invoke stock sources and projectors without merged engine construction`() =
        runTest {
            val values = listOf(TerminalCompletionDomainValue("prod-first"))
            val source =
                TerminalCompletionSources.valueDomain(domain, "custom-dataset", valuesProvider = { _, context ->
                    assertEquals(listOf("prod"), context.precedingOptionValues("--context"))
                    values
                })
            val engine =
                object : TerminalCompletionEngine {
                    override fun completions(request: TerminalCompletionRequest): Flow<List<TerminalCompletionCandidate>> =
                        flow {
                            val context = TerminalCompletionContext.resolve(request, catalog)
                            emit(
                                if (context.activePosition == TerminalCompletionActivePosition.OPERATOR) {
                                    emptyList()
                                } else {
                                    source.complete(request, context, 1)
                                },
                            )
                        }
                }
            val request = request("tool --context prod --choice pr")
            val candidate =
                engine
                    .completions(request)
                    .toList()
                    .single()
                    .single()

            assertEquals("prod-first", candidate.replacementText)
            assertEquals(request.commandLine.lastIndexOf("pr"), candidate.replacementStartOffset)
            assertEquals(request.commandLine.length, candidate.replacementEndOffset)
            val context = TerminalCompletionContext.resolve(request, catalog)
            val projected =
                TerminalCompletionSources.valueDomainCandidates(
                    request,
                    context,
                    domain,
                    "custom-dataset",
                    values,
                    1,
                )
            assertEquals(listOf(candidate), projected)
        }

    @Test
    fun `public resolution and stock engine expose identical contexts`() =
        runTest {
            var observed: TerminalCompletionContext? = null
            val observer =
                TerminalCompletionSource { _, context, _ ->
                    observed = context
                    emptyList()
                }
            val engine = TerminalCompletionEngines.fromSources(listOf(TerminalCompletionSourceEntry(observer)), catalog)
            val lines =
                listOf(
                    "",
                    "tool ",
                    "tool g ",
                    "tool g --choice fi",
                    "tool --context='' repo --choice=\"fi",
                    "tool -- --choice",
                )
            for (line in lines) {
                val request = request(line)
                val direct = TerminalCompletionContext.resolve(request, catalog)
                engine.completions(request).toList()
                assertEquals(publicFields(direct), publicFields(assertNotNull(observed)), line)
            }
        }

    @Test
    fun `direct contexts survive suspension and subsequent resolutions with stock sources`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val firstRequest = request("tool --context prod --choice pr")
            val firstContext = TerminalCompletionContext.resolve(firstRequest, catalog)
            val original = publicFields(firstContext)
            val source =
                TerminalCompletionSources.valueDomain(
                    domain,
                    "suspending-dataset",
                    valuesProvider = { request, context ->
                        if (request == firstRequest) {
                            assertSame(firstContext, context)
                            entered.complete(Unit)
                            release.await()
                        }
                        listOf(TerminalCompletionDomainValue("${context.precedingOptionValues("--context").last()}-first"))
                    },
                )
            val firstResult = async { source.complete(firstRequest, firstContext, 2) }
            try {
                entered.await()
                val nextRequest = request("tool --context next --choice ne")
                val nextContext = TerminalCompletionContext.resolve(nextRequest, catalog)
                assertEquals("next-first", source.complete(nextRequest, nextContext, 2).single().replacementText)
                assertEquals(original, publicFields(firstContext))
            } finally {
                release.complete(Unit)
            }
            assertEquals("prod-first", firstResult.await().single().replacementText)
        }

    private fun request(line: String): TerminalCompletionRequest =
        TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POSIX)

    private fun publicFields(context: TerminalCompletionContext): List<Any?> =
        listOf(
            context.activePosition,
            context.commandTokenIndex,
            context.command,
            context.commandPath,
            context.activeOption,
            context.activePositionalArgument,
            context.usedOptionExclusiveGroupIds,
            context.optionsTerminated,
            context.expectedPathKind,
            context.expectedHiddenPathPolicy,
            context.expectedValueDomain,
            context.subcommandCandidateSource,
            context.staticValueCandidates,
            context.activeTokenQuote,
            context.activePrefix,
            context.replacementStartOffset,
            context.replacementEndOffset,
            context.currentCommand,
            context.precedingArguments,
            context.precedingPositionalArguments,
            context.precedingOptionValues("--context"),
        )

    private val domain = TerminalCompletionValueDomain("test-values")
    private val tool =
        TerminalCommandSpec(
            name = "tool",
            aliases = listOf("t"),
            subcommands = listOf(TerminalCommandSpec("get", aliases = listOf("g"))),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--context", "-c"), requiresValue = true, exclusiveGroupIds = listOf("cluster")),
                    TerminalOptionSpec(
                        listOf("--choice"),
                        requiresValue = true,
                        valueCandidates = listOf("first", "final"),
                        valueDomain = domain,
                    ),
                ),
        )
    private val catalog = listOf(tool)
}
