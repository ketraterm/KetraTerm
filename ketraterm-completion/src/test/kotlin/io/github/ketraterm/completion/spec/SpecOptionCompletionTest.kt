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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalArgumentSpec
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecOptionCompletionTest {
    @Test
    fun `Git switch overrides the root directory option in merged suggestions`() =
        runTest {
            val engine = TerminalCompletionEngines.fromSources(emptyList(), TerminalCommandSpecs.defaults())
            val dialects = listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL, TerminalShellCapabilities.PLAIN)
            for (capabilities in dialects) {
                val rootLine = "git -"
                val root =
                    engine
                        .complete(TerminalCompletionRequest(rootLine, rootLine.length, shellCapabilities = capabilities))
                        .single { it.replacementText == "-C" }
                assertEquals("<directory>", root.argumentHint)
                assertEquals("run as if git was started in path", root.detail)

                val childLine = "git switch -"
                val child =
                    engine
                        .complete(TerminalCompletionRequest(childLine, childLine.length, shellCapabilities = capabilities))
                        .single { it.replacementText == "-C" }
                assertEquals("<branch>", child.argumentHint)
                assertEquals("create/reset and switch to a branch", child.detail)
                assertEquals(
                    "git switch -C",
                    childLine.replaceRange(child.replacementStartOffset, child.replacementEndOffset, child.replacementText),
                )
            }
        }

    @Test
    fun `deepest option definition overrides only its shared spelling`() =
        runTest {
            val rootOption =
                TerminalOptionSpec(
                    listOf("--shared", "--root-alias"),
                    "root value",
                    requiresValue = true,
                    valueName = "root",
                    valueCandidates = listOf("root-value"),
                )
            val childOption = TerminalOptionSpec(listOf("--shared", "--child-alias"), "child flag")
            val leafOption =
                TerminalOptionSpec(
                    listOf("--shared", "--leaf-alias"),
                    "leaf value",
                    requiresValue = true,
                    valueName = "leaf",
                    valueCandidates = listOf("leaf-value"),
                )
            val leaf = TerminalCommandSpec("leaf", options = listOf(leafOption))
            val child = TerminalCommandSpec("child", options = listOf(childOption), subcommands = listOf(leaf))
            val specs = listOf(TerminalCommandSpec("tool", options = listOf(rootOption), subcommands = listOf(child)))

            val childCandidates = complete(specs, "tool child --")
            assertEquals(listOf("--shared", "--root-alias", "--child-alias"), childCandidates.map { it.replacementText })
            assertEquals(listOf("child flag", "root value", "child flag"), childCandidates.map { it.detail })
            assertEquals(listOf("", "<root>", ""), childCandidates.map { it.argumentHint })

            val leafCandidates = complete(specs, "tool child leaf --")
            assertEquals(
                listOf("--shared", "--root-alias", "--child-alias", "--leaf-alias"),
                leafCandidates.map { it.replacementText },
            )
            assertEquals(listOf("leaf value", "root value", "child flag", "leaf value"), leafCandidates.map { it.detail })
            assertEquals(listOf("<leaf>", "<root>", "", "<leaf>"), leafCandidates.map { it.argumentHint })

            for (separator in listOf(" ", "=")) {
                assertEquals(
                    listOf("leaf-value"),
                    complete(specs, "tool child leaf --shared${separator}l").map { it.replacementText },
                )
                assertEquals(
                    listOf("root-value"),
                    complete(specs, "tool child leaf --root-alias${separator}r").map { it.replacementText },
                )
            }
        }

    @Test
    fun `exclusive groups belong to the effective option without parent fallback`() =
        runTest {
            val child =
                TerminalCommandSpec(
                    "child",
                    options =
                        listOf(
                            TerminalOptionSpec(listOf("--shared", "--child-alias"), exclusiveGroupIds = listOf("child")),
                            TerminalOptionSpec(listOf("--child-mode"), exclusiveGroupIds = listOf("child")),
                        ),
                )
            val specs =
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--shared", "--root-alias"), exclusiveGroupIds = listOf("parent")),
                                TerminalOptionSpec(listOf("--parent-mode"), exclusiveGroupIds = listOf("parent")),
                            ),
                        subcommands = listOf(child),
                    ),
                )

            assertEquals(
                listOf("--root-alias", "--parent-mode"),
                complete(specs, "tool child --child-mode --").map { it.replacementText },
            )
            assertEquals(
                listOf("--shared", "--child-alias", "--child-mode"),
                complete(specs, "tool --parent-mode child --").map { it.replacementText },
            )
        }

    @Test
    fun `repeated option spellings do not consume the source limit`() =
        runTest {
            val shared = TerminalOptionSpec(listOf("--help", "--help", "-h"), "show help")
            val child = TerminalCommandSpec("child", options = listOf(shared, TerminalOptionSpec(listOf("--other"))))
            val specs = listOf(TerminalCommandSpec("tool", options = listOf(shared), subcommands = listOf(child)))

            assertEquals(
                listOf("-h", "--help", "--other"),
                complete(specs, "tool child -", limit = 3).map { it.replacementText },
            )
        }

    @Test
    fun `duplicate declarations at the same level keep the first definition`() =
        runTest {
            val specs =
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--verbose"), "first definition"),
                                TerminalOptionSpec(
                                    listOf("--verbose", "--details"),
                                    "second definition",
                                    requiresValue = true,
                                    valueName = "level",
                                ),
                            ),
                    ),
                )

            val candidates = complete(specs, "tool --", limit = 2)
            assertEquals(listOf("--verbose", "--details"), candidates.map { it.replacementText })
            assertEquals(listOf("first definition", "second definition"), candidates.map { it.detail })
            assertEquals(listOf("", "<level>"), candidates.map { it.argumentHint })
        }

    @Test
    fun `duplicate static values do not consume the source limit or merge distinct casing`() =
        runTest {
            val values = listOf("alpha", "alpha", "Alpha", "beta")
            val specs =
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        options = listOf(TerminalOptionSpec(listOf("--value"), requiresValue = true, valueCandidates = values)),
                        positionalArguments = listOf(TerminalArgumentSpec("value", valueCandidates = values)),
                    ),
                )
            for (line in listOf("tool ", "tool --value ", "tool --value=")) {
                assertEquals(
                    listOf("alpha", "Alpha", "beta"),
                    complete(specs, line, limit = 3).map { it.replacementText },
                    line,
                )
            }
        }

    private suspend fun complete(
        specs: List<TerminalCommandSpec>,
        line: String,
        limit: Int = 64,
    ): List<TerminalCompletionCandidate> {
        val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POSIX)
        return SpecCompletionSource(specs).complete(request, TerminalCompletionContext.resolve(request, specs), limit)
    }
}
