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
import io.github.ketraterm.completion.internal.commandLineAfterCandidate
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PowerShellOptionAuthorityTest {
    @Test
    fun `shell aliases and functions never inherit Unix options or unrelated fallback`() =
        runTest {
            val cases =
                mapOf(
                    "ls --a" to emptyList(),
                    "ls -H" to listOf("-h", "-Hidden"),
                    "cat -n" to emptyList(),
                    "cat -E" to listOf("-Exclude", "-Encoding"),
                    "rm -f" to listOf("-Filter", "-Force"),
                    "mkdir -p" to listOf("-Path"),
                    "git --v" to listOf("--value"), // A function can shadow an application.
                )
            for ((line, expected) in cases) {
                var queries = 0
                val learning =
                    TerminalCompletionLearningStore().apply {
                        recordCommandResult("$line unwanted-history", true, null, null, 10)
                    }
                val engine =
                    TerminalCompletionEngines.fromSources(
                        sources = listOf(TerminalCompletionSourceEntry({ _, _, _ -> error("No generic source for $line") })),
                        learningStore = learning,
                        shellProvider =
                            TerminalCompletionShellProvider { request, _, _ ->
                                queries++
                                TerminalCompletionShellResult(
                                    expected.mapIndexed { index, value ->
                                        candidate(request, value, line.indexOf(' ') + 1).copy(score = expected.size - index)
                                    },
                                )
                            },
                    )
                val result = engine.completions(request(line)).last()
                assertEquals(expected, result.map { it.replacementText }, line)
                assertEquals(1, queries, line)
            }
        }

    @Test
    fun `abbreviations attached values and colliding flags preserve native replacement ranges`() =
        runTest {
            for ((line, value, start) in listOf(
                Triple("cat -Enc u", "UTF8", 9),
                Triple("cat -Encoding:u", "UTF8", 14),
                Triple("mkdir -p ", "'new directory'", 9),
                Triple("mkdir -p:'new d'", "'new directory'", 9),
            )) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        sources = listOf(TerminalCompletionSourceEntry({ _, _, _ -> error("Native value owns $line") })),
                        shellProvider =
                            TerminalCompletionShellProvider { request, context, _ ->
                                assertNull(context.activeOption)
                                assertNull(context.activePositionalArgument)
                                val valueCandidate = candidate(request, value, start, TerminalCompletionCandidateKind.ARGUMENT)
                                TerminalCompletionShellResult(listOf(valueCandidate))
                            },
                    )
                val request = request(line)
                val result = engine.completions(request).last().single()
                assertEquals(line.take(start) + value, request.commandLineAfterCandidate(result), line)
                assertEquals(start, result.replacementStartOffset, line)
                assertEquals(line.length, result.replacementEndOffset, line)
            }
        }

    @Test
    fun `compatible applications retain catalog options without a native completer`() =
        runTest {
            for (external in listOf("git", "GIT.EXE", "git.cmd", "git.bat", "git.com")) {
                for (genericPath in listOf(false, true)) {
                    var queries = 0
                    val engine =
                        TerminalCompletionEngines.fromSources(
                            emptyList(),
                            shellProvider =
                                TerminalCompletionShellProvider { request, _, _ ->
                                    queries++
                                    val candidates =
                                        if (genericPath) {
                                            listOf(candidate(request, "--version.txt", 4, TerminalCompletionCandidateKind.PATH))
                                        } else {
                                            emptyList()
                                        }
                                    TerminalCompletionShellResult(candidates, external)
                                },
                        )
                    val result = engine.completions(request("git --v")).last()
                    assertTrue(result.any { it.replacementText == "--version" }, "$external $genericPath")
                    assertTrue(result.none { it.kind == TerminalCompletionCandidateKind.PATH })
                    assertEquals(1, queries)
                }
            }
        }

    @Test
    fun `an external alias targeting another application does not inherit the original catalog`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(TerminalCompletionSourceEntry({ _, _, _ -> error("Mismatched command") })),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            TerminalCompletionShellResult(emptyList(), "other.exe")
                        },
                )
            assertTrue(engine.completions(request("git --v")).last().isEmpty())
        }

    @Test
    fun `installed external argument completers override modeled options`() =
        runTest {
            for (kind in listOf(TerminalCompletionCandidateKind.OPTION, TerminalCompletionCandidateKind.ARGUMENT)) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        emptyList(),
                        shellProvider =
                            TerminalCompletionShellProvider { request, _, _ ->
                                TerminalCompletionShellResult(listOf(candidate(request, "--vendor", 4, kind)), "git.exe")
                            },
                    )
                assertEquals(listOf("--vendor"), engine.completions(request("git --v")).last().map { it.replacementText })
            }
        }

    @Test
    fun `external option values retain host domains without generic paths`() =
        runTest {
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { request, _, _ ->
                            queries++
                            val path = candidate(request, "file.txt", 20, TerminalCompletionCandidateKind.PATH)
                            TerminalCompletionShellResult(listOf(path), "git.exe")
                        },
                )
            val result = engine.completions(request("git switch --detach fe")).last()
            assertTrue(result.none { it.kind == TerminalCompletionCandidateKind.PATH })
            assertEquals(1, queries)
        }

    @Test
    fun `deferred external answer is reused when catalog and host sources have no options`() =
        runTest {
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    commandSpecs = listOf(TerminalCommandSpec("tool", options = listOf(TerminalOptionSpec(listOf("--known"))))),
                    shellProvider =
                        TerminalCompletionShellProvider { request, _, _ ->
                            queries++
                            TerminalCompletionShellResult(
                                listOf(candidate(request, "--other.txt", 5, TerminalCompletionCandidateKind.PATH)),
                                "tool.exe",
                            )
                        },
                )
            assertEquals(listOf("--other.txt"), engine.completions(request("tool --other")).last().map { it.replacementText })
            assertEquals(1, queries)
        }

    @Test
    fun `unavailable native options preserve catalog fallback with one query`() =
        runTest {
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            null
                        },
                )
            assertTrue(engine.completions(request("git --v")).last().any { it.replacementText == "--version" })
            assertEquals(1, queries)
        }

    @Test
    fun `live answers detach candidate collections from their providers`() {
        val candidates = mutableListOf(candidate(request("git --v"), "--version", 4))
        val answer = TerminalCompletionShellResult(candidates, "git.exe")
        candidates.clear()
        assertEquals("--version", answer.candidates.single().replacementText)
        assertFailsWith<UnsupportedOperationException> { (answer.candidates as MutableList).clear() }
    }

    private fun request(line: String) =
        TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)

    private fun candidate(
        request: TerminalCompletionRequest,
        text: String,
        start: Int,
        kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.OPTION,
    ) = TerminalCompletionCandidate(text, start, request.commandLine.length, "powershell", kind)
}
