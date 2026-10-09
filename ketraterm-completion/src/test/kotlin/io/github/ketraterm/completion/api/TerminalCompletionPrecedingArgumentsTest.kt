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

import io.github.ketraterm.completion.commandline.TerminalCompletionContextResolver
import io.github.ketraterm.completion.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TerminalCompletionPrecedingArgumentsTest {
    @Test
    fun `repeated aliases and attached values preserve decoded input order`() {
        val context = resolve("ENV=ignored tool --context 'QA Region' -c=Prod=blue --flag repo --namespace ns act")

        assertEquals(listOf("--context", "QA Region", "-c=Prod=blue", "--flag", "repo", "--namespace", "ns"), context.precedingArguments)
        assertEquals(listOf("QA Region", "Prod=blue"), context.precedingOptionValues("--context"))
        assertEquals(listOf("QA Region", "Prod=blue"), context.precedingOptionValues(" -C "))
        assertEquals(listOf("ns"), context.precedingOptionValues("-n"))
        assertEquals(listOf("repo"), context.precedingPositionalArguments)
        assertEquals(emptyList(), context.precedingOptionValues("--flag"))
        assertEquals(emptyList(), context.precedingOptionValues("--unknown"))
    }

    @Test
    fun `empty and whitespace quoted values remain real arguments`() {
        val context = resolve("tool --context '' repo ' ' --namespace= '' ")

        assertEquals(listOf("--context", "", "repo", " ", "--namespace=", ""), context.precedingArguments)
        assertEquals(listOf(""), context.precedingOptionValues("--context"))
        assertEquals(listOf(""), context.precedingOptionValues("--namespace"))
        assertEquals(listOf("repo", " ", ""), context.precedingPositionalArguments)
        assertEquals(null, context.activeOption)
    }

    @Test
    fun `pending separate and attached active values are excluded until the cursor passes the word`() {
        for (line in listOf("tool --context ", "tool --context prod", "tool --context 'pr", "tool --context=prod", "tool --context='pr")) {
            val context = resolve(line)
            assertEquals(emptyList(), context.precedingOptionValues("--context"), line)
            assertEquals(emptyList(), context.precedingPositionalArguments, line)
            assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition, line)
        }
        assertEquals(listOf("--context"), resolve("tool --context prod").precedingArguments)
        assertEquals(emptyList(), resolve("tool --context=prod").precedingArguments)
        assertEquals(listOf("prod"), resolve("tool --context prod ").precedingOptionValues("--context"))
        assertEquals(listOf("prod"), resolve("tool --context=prod ").precedingOptionValues("--context"))
    }

    @Test
    fun `mid word cursor excludes active suffix and later values`() {
        val line = "tool --context prod repository --context future tail"
        val context = resolve(line, line.indexOf("repository") + 4)

        assertEquals(listOf("--context", "prod"), context.precedingArguments)
        assertEquals(listOf("prod"), context.precedingOptionValues("--context"))
        assertEquals(emptyList(), context.precedingPositionalArguments)
        assertEquals("repo", context.activePrefix)
        assertEquals(line.indexOf("repository") + "repository".length, context.replacementEndOffset)
    }

    @ParameterizedTest
    @EnumSource(value = TerminalShellSyntax::class, names = ["POSIX", "POWERSHELL"])
    fun `operators isolate the active segment from earlier and later commands`(syntax: TerminalShellSyntax) {
        for (operator in listOf(";", "|", "&&", "||")) {
            val line = "tool --context previous repo $operator tool --context current here active $operator tool --context future"
            val context = resolve(line, line.indexOf("active") + "active".length, syntax)

            assertEquals(listOf("--context", "current", "here"), context.precedingArguments, operator)
            assertEquals(listOf("current"), context.precedingOptionValues("--context"), operator)
            assertEquals(listOf("here"), context.precedingPositionalArguments, operator)
        }
    }

    @Test
    fun `POSIX quotes and escapes preserve literal separators without expanding values`() {
        val context = resolve("tool --context 'prod | \$HOME' repo\\;name active")

        assertEquals(listOf("prod | \$HOME"), context.precedingOptionValues("--context"))
        assertEquals(listOf("repo;name"), context.precedingPositionalArguments)
    }

    @Test
    fun `PowerShell doubled quotes and backtick escapes use the shared decoding`() {
        val context = resolve("tool --context 'prod''west' repo` name active", syntax = TerminalShellSyntax.POWERSHELL)

        assertEquals(listOf("prod'west"), context.precedingOptionValues("--context"))
        assertEquals(listOf("repo name"), context.precedingPositionalArguments)
    }

    @Test
    fun `plain syntax does not infer command boundaries`() {
        val context = resolve("tool --context earlier | tool --context later active", syntax = TerminalShellSyntax.PLAIN)

        assertEquals(listOf("--context", "earlier", "|", "tool", "--context", "later"), context.precedingArguments)
        assertEquals(listOf("earlier", "later"), context.precedingOptionValues("--context"))
        assertEquals(listOf("|", "tool"), context.precedingPositionalArguments)
    }

    @Test
    fun `unknown commands expose words without inventing option or positional ownership`() {
        val context = resolve("ENV=value unknown --context 'prod west' folder active")

        assertEquals(listOf("--context", "prod west", "folder"), context.precedingArguments)
        assertEquals(emptyList(), context.precedingOptionValues("--context"))
        assertEquals(emptyList(), context.precedingPositionalArguments)
    }

    @Test
    fun `command and operator positions have no preceding arguments`() {
        for (line in listOf("", "ENV=value to", "tool")) {
            val context = resolve(line)
            assertEquals(emptyList(), context.precedingArguments, line)
            assertEquals(emptyList(), context.precedingOptionValues("--context"), line)
            assertEquals(emptyList(), context.precedingPositionalArguments, line)
        }
        val line = "tool --context prod && tool next"
        val context = resolve(line, line.indexOf("&&") + 1)
        assertEquals(TerminalCompletionActivePosition.OPERATOR, context.activePosition)
        assertEquals(emptyList(), context.precedingArguments)
    }

    @Test
    fun `subcommand shadowing resolves each option occurrence at its own command position`() {
        val child =
            TerminalCommandSpec(
                name = "get",
                options = listOf(TerminalOptionSpec(listOf("--context", "--child-context"), requiresValue = true)),
            )
        val catalog = listOf(tool.copy(subcommands = listOf(child)))
        val context = resolve("tool -c root get --context child --child-context next repo active", catalog = catalog)

        assertEquals(listOf("root", "child", "next"), context.precedingOptionValues("--context"))
        assertEquals(listOf("root"), context.precedingOptionValues("-c"))
        assertEquals(listOf("child", "next"), context.precedingOptionValues("--child-context"))
        assertEquals(listOf("repo"), context.precedingPositionalArguments)
        assertEquals(listOf("tool", "get"), context.commandPath.map { it.name })
    }

    @Test
    fun `option terminator ends ownership once and preserves later dash words`() {
        val context = resolve("tool --context prod -- --context ignored -- '' next ")

        assertEquals(listOf("prod"), context.precedingOptionValues("--context"))
        assertEquals(listOf("--context", "ignored", "--", "", "next"), context.precedingPositionalArguments)
        assertEquals(true, context.optionsTerminated)
        assertEquals(null, context.activeOption)
    }

    @Test
    fun `empty positional advances the active spec and empty option value does not consume a positional`() {
        assertEquals("rest", resolve("tool '' ").activePositionalArgument?.name)
        val context = resolve("tool --context '' repository ")
        assertEquals(listOf("repository"), context.precedingPositionalArguments)
        assertEquals("rest", context.activePositionalArgument?.name)
    }

    @Test
    fun `unknown options remain lexical and do not acquire inferred values`() {
        val context = resolve("tool --unknown=value --unknown next active")
        assertEquals(listOf("--unknown=value", "--unknown", "next"), context.precedingArguments)
        assertEquals(emptyList(), context.precedingOptionValues("--unknown"))
        assertEquals(listOf("next"), context.precedingPositionalArguments)
    }

    @Test
    fun `published argument collections reject mutation and survive later resolutions`() {
        val context = resolve("tool --context prod repo active")
        val words = context.precedingArguments
        val optionValues = context.precedingOptionValues("--context")
        val positionalValues = context.precedingPositionalArguments
        for (values in listOf(words, optionValues, positionalValues)) {
            assertFailsWith<UnsupportedOperationException> { (values as MutableList<String>).clear() }
            assertFailsWith<UnsupportedOperationException> { (values as MutableList<String>)[0] = "changed" }
        }
        resolve("tool --context future other active")
        assertSame(words, context.precedingArguments)
        assertEquals(listOf("--context", "prod", "repo"), words)
        assertEquals(listOf("prod"), optionValues)
        assertEquals(listOf("repo"), positionalValues)
    }

    @Test
    fun `suspending providers share one context and retain it while another request completes`() =
        runTest {
            val firstContext = CompletableDeferred<TerminalCompletionContext>()
            val firstObserverContext = CompletableDeferred<TerminalCompletionContext>()
            val secondContext = CompletableDeferred<TerminalCompletionContext>()
            val releaseFirst = CompletableDeferred<Unit>()
            val provider =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                    sourceId = "namespaces",
                    valuesProvider = { request, context ->
                        if (request.profileId == "first") {
                            firstContext.complete(context)
                            releaseFirst.await()
                        } else {
                            secondContext.complete(context)
                        }
                        val cluster = context.precedingOptionValues("--context").last()
                        val repository = context.precedingPositionalArguments.single()
                        listOf(TerminalCompletionDomainValue("$cluster-$repository-namespace"))
                    },
                )
            val observer =
                TerminalCompletionSource { request, context, _ ->
                    if (request.profileId == "first") firstObserverContext.complete(context)
                    emptyList()
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(TerminalCompletionSourceEntry(provider), TerminalCompletionSourceEntry(observer)),
                    commandSpecs =
                        listOf(
                            tool.copy(
                                name = "kubectl",
                                options =
                                    listOf(
                                        TerminalOptionSpec(listOf("--context", "-c"), requiresValue = true),
                                        TerminalOptionSpec(
                                            listOf("--namespace"),
                                            requiresValue = true,
                                            valueDomain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                                        ),
                                    ),
                            ),
                        ),
                )

            fun request(
                line: String,
                profile: String,
            ) = TerminalCompletionRequest(line, line.length, profileId = profile, shellCapabilities = TerminalShellCapabilities.POSIX)
            val firstResult =
                async {
                    engine.completions(request("kubectl --context prod repo --namespace pr", "first")).toList().last()
                }
            try {
                val retained = firstContext.await()
                assertSame(retained, firstObserverContext.await())
                val retainedWords = retained.precedingArguments
                val next =
                    engine
                        .completions(request("kubectl -c future other --namespace fu", "second"))
                        .toList()
                        .last()
                        .single()
                assertEquals("future-other-namespace", next.replacementText)
                assertEquals(listOf("future"), secondContext.await().precedingOptionValues("--context"))
                assertSame(retainedWords, retained.precedingArguments)
                assertEquals(listOf("--context", "prod", "repo", "--namespace"), retainedWords)
                assertEquals(listOf("prod"), retained.precedingOptionValues("--context"))
            } finally {
                releaseFirst.complete(Unit)
            }
            assertEquals("prod-repo-namespace", firstResult.await().single().replacementText)
        }

    private fun resolve(
        line: String,
        cursor: Int = line.length,
        syntax: TerminalShellSyntax = TerminalShellSyntax.POSIX,
        catalog: List<TerminalCommandSpec> = listOf(tool),
    ): TerminalCompletionContext = TerminalCompletionContextResolver.resolve(line, cursor, catalog, syntax)

    private val tool =
        TerminalCommandSpec(
            name = "tool",
            options =
                listOf(
                    TerminalOptionSpec(listOf("--context", "-c"), requiresValue = true),
                    TerminalOptionSpec(listOf("--namespace", "-n"), requiresValue = true),
                    TerminalOptionSpec(listOf("--flag")),
                ),
            positionalArguments = listOf(TerminalArgumentSpec("repository"), TerminalArgumentSpec("rest", isVariadic = true)),
        )
}
