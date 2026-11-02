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
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShellAwareCompletionEngineTest {
    @Test
    fun `branch prefixes use runtime or native results without filesystem noise after shortening and quoting`() =
        runTest {
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                for (runtimeAvailable in listOf(true, false)) {
                    var fileQueries = 0
                    var nativeQueries = 0
                    val branch = TerminalCompletionDomainValue("feature/local")
                    val learning =
                        TerminalCompletionLearningStore().apply {
                            repeat(20) { recordCommandResult("git switch feature/old", true, null, null, 10) }
                            recordCommandResult("git switch feature/local", true, null, null, 10)
                        }
                    val engine =
                        MergedCompletionEngine(
                            sources =
                                listOf(
                                    TerminalCompletionSourceEntry({ _, context, _ ->
                                        listOf(
                                            TerminalCompletionCandidate(
                                                "feature/local.txt",
                                                context.replacementStartOffset,
                                                context.replacementEndOffset,
                                                "custom-files",
                                                TerminalCompletionCandidateKind.PATH,
                                            ),
                                        )
                                    }),
                                    TerminalCompletionSourceEntry(
                                        TerminalCompletionSources.path {
                                            fileQueries++
                                            listOf(TerminalFileEntry("local.txt", false))
                                        },
                                    ),
                                    TerminalCompletionSourceEntry(
                                        TerminalCompletionSources.fuzzyPath("project", { _, _ ->
                                            fileQueries++
                                            listOf(TerminalFuzzyPathEntry("feature/local.txt", false))
                                        }),
                                    ),
                                    TerminalCompletionSourceEntry(
                                        TerminalCompletionSources.valueDomain(
                                            TerminalCompletionValueDomain.GIT_BRANCH,
                                            "git",
                                            { _, _ -> if (runtimeAvailable) listOf(branch) else emptyList() },
                                        ),
                                    ),
                                ),
                            learningStore = learning,
                            clockEpochMillis = { 20 },
                            shellProvider =
                                TerminalCompletionShellProvider { request, context, limit ->
                                    nativeQueries++
                                    TerminalCompletionSources.valueDomainCandidates(
                                        request,
                                        context,
                                        TerminalCompletionValueDomain.GIT_BRANCH,
                                        "native",
                                        listOf(branch),
                                        limit,
                                    ) +
                                        TerminalCompletionCandidate(
                                            "feature/local.txt",
                                            context.replacementStartOffset,
                                            context.replacementEndOffset,
                                            "native",
                                            TerminalCompletionCandidateKind.PATH,
                                        )
                                },
                        )
                    for (line in listOf("git switch feature/lo", "git switch feature/", "git switch 'feature/")) {
                        val request = request(line).copy(shellCapabilities = shell, workingDirectoryUri = "file:///project")
                        val results = engine.complete(request)
                        val first = results.first()
                        assertEquals(if (runtimeAvailable) "git" else "native", first.source, line)
                        val edited = line.replaceRange(first.replacementStartOffset, first.replacementEndOffset, first.replacementText)
                        val editedContext =
                            TerminalCompletionContext.resolve(
                                request.copy(commandLine = edited, cursorOffset = edited.length),
                            )
                        assertEquals("feature/local", editedContext.activePrefix, line)
                        assertEquals(1, results.count { it.displayText == "feature/local" }, line)
                        assertTrue(results.none { it.kind == TerminalCompletionCandidateKind.PATH }, line)
                    }
                    assertEquals(0, fileQueries)
                    assertEquals(if (runtimeAvailable) 0 else 3, nativeQueries)
                }
            }
        }

    @Test
    fun `PowerShell command authority preserves differently cased history`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry({ _, _, _ ->
                                listOf(
                                    candidate("GIT status", 2, "history", TerminalCompletionCandidateKind.COMMAND),
                                    candidate("cargo build", 2, "history", TerminalCompletionCandidateKind.COMMAND),
                                )
                            }),
                        ),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            listOf(candidate("git", 2, "powershell", TerminalCompletionCandidateKind.COMMAND))
                        },
                )
            assertEquals(
                setOf("git", "GIT status"),
                engine
                    .completions(request("gi"))
                    .last()
                    .map { it.replacementText }
                    .toSet(),
            )
            assertEquals(
                listOf("git"),
                engine
                    .completions(request("gi").copy(shellCapabilities = TerminalShellCapabilities.POSIX))
                    .last()
                    .map { it.replacementText },
            )
        }

    @Test
    fun `command relevance overrides native enumeration order`() =
        runTest {
            val names = listOf("docker-credential-desktop.exe", "docker-compose", "docker", "docker-credential-wincred.exe")
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    commandSpecs = listOf(TerminalCommandSpec("docker"), TerminalCommandSpec("docker-compose")),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            names.mapIndexed { index, name ->
                                candidate(name, 4, "powershell", TerminalCompletionCandidateKind.COMMAND)
                                    .copy(score = names.size - index)
                            }
                        },
                )
            assertEquals(
                listOf("docker", "docker-compose", "docker-credential-desktop.exe", "docker-credential-wincred.exe"),
                engine.completions(request("dock")).last().map { it.replacementText },
            )
        }

    @Test
    fun `only shell verified commands are offered and catalog descriptions enrich their native edit`() =
        runTest {
            val request = request("dock")
            val native =
                candidate("docker", 4, "powershell", TerminalCompletionCandidateKind.COMMAND)
                    .copy(displayText = "docker.exe", detail = "C:\\tools\\docker.exe")
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    commandSpecs =
                        listOf(
                            TerminalCommandSpec("docker", description = "Container platform"),
                            TerminalCommandSpec("docker-compose"),
                        ),
                    shellProvider = TerminalCompletionShellProvider { _, _, _ -> listOf(native) },
                )
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                val publications = engine.completions(request.copy(shellCapabilities = shell)).toList()
                assertEquals(1, publications.size)
                val result = publications.single().single()
                assertEquals("docker", result.replacementText)
                assertEquals("docker", result.displayText)
                assertTrue(result.detail.startsWith("Container platform"))
                assertTrue(result.detail.contains("C:\\tools\\docker.exe"))
                assertEquals(1, result.matchedRanges.rangeCount)
            }
        }

    @Test
    fun `catalog cannot flash unavailable commands while shell discovery is pending`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<List<TerminalCompletionCandidate>>()
            val publications = mutableListOf<List<TerminalCompletionCandidate>>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    commandSpecs = listOf(TerminalCommandSpec("cargo")),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            entered.complete(Unit)
                            response.await()
                        },
                )
            val job = launch { engine.completions(request("car")).toList(publications) }
            entered.await()
            runCurrent()
            assertTrue(publications.isEmpty())
            response.complete(emptyList())
            job.join()
            assertEquals(listOf(emptyList()), publications)
        }

    @Test
    fun `unavailable shell falls back to catalog and shell cancellation cancels the request`() =
        runTest {
            val fallback =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    commandSpecs = listOf(TerminalCommandSpec("cargo")),
                    shellProvider = TerminalCompletionShellProvider { _, _, _ -> null },
                )
            assertEquals(
                "cargo",
                fallback
                    .completions(request("car"))
                    .last()
                    .single()
                    .replacementText,
            )
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            try {
                                entered.complete(Unit)
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        },
                )
            val job = launch { engine.completions(request("car")).collect() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
        }

    @Test
    fun `other command sources cannot reintroduce an unavailable executable`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                { _, _, _ ->
                                    listOf(
                                        candidate("cargo build", 2, "learned", TerminalCompletionCandidateKind.COMMAND),
                                        candidate("cat file", 2, "learned", TerminalCompletionCandidateKind.COMMAND),
                                    )
                                },
                            ),
                        ),
                    commandSpecs = emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            listOf(candidate("cat", 2, "powershell", TerminalCompletionCandidateKind.COMMAND))
                        },
                )
            val results = engine.completions(request("ca")).last()
            assertEquals(setOf("cat", "cat file"), results.map { it.replacementText }.toSet())
        }

    @Test
    fun `directory outcomes merge across PowerShell separators quoting and relative prefix`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                { _, _, _ ->
                                    listOf(TerminalCompletionCandidate("'build dir/'", 3, 3, "path", TerminalCompletionCandidateKind.PATH))
                                },
                            ),
                            TerminalCompletionSourceEntry(
                                { _, _, _ ->
                                    listOf(
                                        TerminalCompletionCandidate(
                                            "'.\\build dir\\'",
                                            3,
                                            3,
                                            "powershell",
                                            TerminalCompletionCandidateKind.PATH,
                                        ),
                                    )
                                },
                            ),
                        ),
                )
            assertEquals(1, engine.completions(request("cd ")).last().size)
        }

    @Test
    fun `modeled directory and subcommand results do not query the native completer`() =
        runTest {
            var queries = 0
            val shell =
                TerminalCompletionShellProvider { _, _, _ ->
                    queries++
                    emptyList()
                }
            val directories =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                { _, _, _ ->
                                    listOf(TerminalCompletionCandidate("xyz/", 3, 4, "path", TerminalCompletionCandidateKind.PATH))
                                },
                            ),
                        ),
                    shellProvider = shell,
                )
            assertEquals(
                "path",
                directories
                    .completions(request("cd x"))
                    .last()
                    .single()
                    .source,
            )
            val commands = TerminalCompletionEngines.fromSources(sources = emptyList(), shellProvider = shell)
            assertTrue(commands.completions(request("git s")).last().any { it.replacementText == "status" })
            assertEquals(0, queries)
        }

    @Test
    fun `empty modeled arguments fall back to native completion once`() =
        runTest {
            var queries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            listOf(TerminalCompletionCandidate("xyzdir/", 3, 6, "powershell", TerminalCompletionCandidateKind.PATH))
                        },
                )
            assertEquals(
                "xyzdir/",
                engine
                    .completions(request("cd xyz"))
                    .last()
                    .single()
                    .replacementText,
            )
            assertEquals(1, queries)
        }

    @Test
    fun `learned arguments cannot suppress native fallback or publish before its edit completes`() =
        runTest {
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("git switch feature-old", true, null, null, 10)
            val native = CompletableDeferred<List<TerminalCompletionCandidate>>()
            var queries = 0
            val engine =
                MergedCompletionEngine(
                    sources = listOf(TerminalCompletionSourceEntry({ _, _, _ -> emptyList() })),
                    learningStore = learning,
                    clockEpochMillis = { 20 },
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            native.await()
                        },
                )
            val results = ArrayList<List<TerminalCompletionCandidate>>()
            val job = launch { engine.completions(request("git switch fe")).toList(results) }
            runCurrent()
            assertEquals(1, queries)
            assertTrue(results.isEmpty())
            native.complete(
                listOf(TerminalCompletionCandidate("feature-current", 11, 13, "powershell", TerminalCompletionCandidateKind.ARGUMENT)),
            )
            job.join()
            assertTrue(results.last().any { it.replacementText == "feature-current" })
            assertTrue(results.last().any { it.replacementText == "feature-old" })
        }

    @Test
    fun `learned arguments remain available when native fallback has no matches`() =
        runTest {
            val learning = TerminalCompletionLearningStore()
            learning.recordCommandResult("git switch feature-old", true, null, null, 10)
            var queries = 0
            val engine =
                MergedCompletionEngine(
                    sources = emptyList(),
                    learningStore = learning,
                    clockEpochMillis = { 20 },
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            queries++
                            emptyList()
                        },
                )
            assertTrue(engine.completions(request("git switch fe")).last().any { it.replacementText == "feature-old" })
            assertEquals(1, queries)
        }

    @Test
    fun `unmodeled argument completers take precedence over generic filesystem candidates`() =
        runTest {
            var fileQueries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                { _, _, _ ->
                                    fileQueries++
                                    listOf(TerminalCompletionCandidate("other.txt", 7, 8, "path", TerminalCompletionCandidateKind.PATH))
                                },
                            ),
                        ),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            listOf(TerminalCompletionCandidate("native", 7, 8, "powershell", TerminalCompletionCandidateKind.ARGUMENT))
                        },
                )
            assertEquals(
                "native",
                engine
                    .completions(request("custom x"))
                    .last()
                    .single()
                    .replacementText,
            )
            assertEquals(0, fileQueries)
        }

    @Test
    fun `exact native command is authority for longer history but not a no-op popup row`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                { _, _, _ ->
                                    listOf(candidate("cat file", 3, "learned", TerminalCompletionCandidateKind.COMMAND))
                                },
                            ),
                        ),
                    shellProvider =
                        TerminalCompletionShellProvider { _, _, _ ->
                            listOf(candidate("cat", 3, "powershell", TerminalCompletionCandidateKind.COMMAND))
                        },
                )
            assertEquals(listOf("cat file"), engine.completions(request("cat")).last().map { it.replacementText })
        }

    private fun request(line: String) =
        TerminalCompletionRequest(
            line,
            line.length,
            shellCapabilities = TerminalShellCapabilities.POWERSHELL,
        )

    private fun candidate(
        text: String,
        end: Int,
        source: String,
        kind: TerminalCompletionCandidateKind,
    ) = TerminalCompletionCandidate(text, 0, end, source, kind)
}
