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
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.completion.testing.shellProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ShellAwareCompletionEngineTest {
    @Test
    fun `catalog operand hints describe command names without being appended to full command suggestions`() =
        runTest {
            val spec =
                TerminalCommandSpec(
                    name = "cat",
                    positionalArguments = listOf(TerminalArgumentSpec(name = "file", isOptional = true, isVariadic = true)),
                )
            for (replacement in listOf("cat", "cat .\\utf8-extended.txt -Encoding UTF8", "cat 'my file.txt'")) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        sources =
                            listOf(
                                TerminalCompletionSourceEntry({ _, _, _ ->
                                    listOf(candidate(replacement, 2, "host", TerminalCompletionCandidateKind.COMMAND))
                                }),
                            ),
                        commandSpecs = listOf(spec),
                        shellProvider =
                            shellProvider { _, _, _ ->
                                listOf(candidate("cat", 2, "powershell", TerminalCompletionCandidateKind.COMMAND))
                            },
                    )
                val result = engine.completions(request("ca")).last().single { it.replacementText == replacement }
                assertEquals(if (replacement == "cat") "[file]" else "", result.argumentHint, replacement)
            }
        }

    @Test
    fun `unrecognized options use authoritative argument values without filesystem or history guesses`() =
        runTest {
            for (capabilities in listOf(TerminalShellCapabilities.POWERSHELL, TerminalShellCapabilities.POSIX)) {
                var fileQueries = 0
                var shellQueries = 0
                val learning =
                    TerminalCompletionLearningStore().apply {
                        recordCommandResult("cat utf8-extended.txt -Encoding unwanted-history", true, null, null, 10)
                    }
                val engine =
                    TerminalCompletionEngines.fromSources(
                        sources =
                            listOf(
                                TerminalCompletionSourceEntry({ _, context, _ ->
                                    fileQueries++
                                    listOf(
                                        TerminalCompletionCandidate(
                                            "unrelated.txt",
                                            context.replacementStartOffset,
                                            context.replacementEndOffset,
                                            "path",
                                            TerminalCompletionCandidateKind.PATH,
                                        ),
                                    )
                                }),
                            ),
                        learningStore = learning,
                        shellProvider =
                            shellProvider { request, context, _ ->
                                shellQueries++
                                assertEquals("cat", context.command?.name)
                                assertNull(context.activePositionalArgument)
                                assertNull(context.activeOption)
                                assertEquals(TerminalPathArgumentKind.NONE, context.expectedPathKind)
                                assertEquals(TerminalCompletionValueDomain.NONE, context.expectedValueDomain)
                                listOf("Unicode", "UTF8").filter { it.startsWith(context.activePrefix, ignoreCase = true) }.map {
                                    TerminalCompletionCandidate(
                                        it,
                                        context.replacementStartOffset,
                                        request.cursorOffset,
                                        "shell",
                                        TerminalCompletionCandidateKind.ARGUMENT,
                                    )
                                }
                            },
                    )
                val prefixes =
                    listOf(
                        "" to listOf("Unicode", "UTF8"),
                        "u" to listOf("Unicode", "UTF8"),
                        "ut" to listOf("UTF8"),
                        "z" to emptyList(),
                    )
                for ((prefix, expected) in prefixes) {
                    val line = "cat utf8-extended.txt -Encoding $prefix"
                    val candidates = engine.completions(request(line).copy(shellCapabilities = capabilities)).last()
                    assertEquals(expected.toSet(), candidates.map { it.replacementText }.toSet())
                    assertTrue(candidates.all { it.source == "shell" })
                    assertTrue(
                        candidates.all {
                            it.replacementStartOffset == line.length - prefix.length && it.replacementEndOffset == line.length
                        },
                    )
                }
                assertEquals(4, shellQueries)
                assertEquals(0, fileQueries)
            }
        }

    @Test
    fun `unrecognized options retain explicit path fallback when native completion is unavailable`() =
        runTest {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                TerminalCompletionSources.path {
                                    listOf(TerminalFileEntry("utf8.txt", isDirectory = false))
                                },
                            ),
                        ),
                    shellProvider = shellProvider { _, _, _ -> null },
                )
            val result =
                engine
                    .completions(
                        request("cat file.txt -Encoding ./u").copy(workingDirectoryUri = "file:///completion-test"),
                    ).last()
                    .single()
            assertEquals("./utf8.txt", result.replacementText)
            assertEquals("path", result.source)
        }

    @Test
    fun `PowerShell catalog enrichment respects command case without rewriting shell edits`() =
        runTest {
            for (name in listOf("get-widget", "widget")) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        sources = emptyList(),
                        commandSpecs = listOf(TerminalCommandSpec("Get-Widget", aliases = listOf("Widget"), description = "Widget tools")),
                        shellProvider =
                            shellProvider { _, _, _ ->
                                listOf(candidate(name, 1, "shell", TerminalCompletionCandidateKind.COMMAND).copy(detail = "Native help"))
                            },
                    )
                val powershell = engine.completions(request(name.take(1))).last().single()
                assertEquals(name, powershell.replacementText)
                assertEquals("Widget tools\nNative help", powershell.detail)
                val posix =
                    engine
                        .completions(
                            request(name.take(1)).copy(shellCapabilities = TerminalShellCapabilities.POSIX),
                        ).last()
                        .single()
                assertEquals("Native help", posix.detail)
            }
        }

    @Test
    fun `duplicate live commands retain catalog descriptions regardless of provider arrival`() =
        runTest {
            for (command in listOf("git", "docker")) {
                for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                    val release = CompletableDeferred<Unit>()
                    val publications = mutableListOf<List<TerminalCompletionCandidate>>()
                    val prefix = command.take(2)
                    val engine =
                        TerminalCompletionEngines.fromSources(
                            sources =
                                listOf(
                                    TerminalCompletionSourceEntry(
                                        { _, _, _ ->
                                            release.await()
                                            listOf(
                                                candidate(command, prefix.length, "host", TerminalCompletionCandidateKind.COMMAND),
                                            )
                                        },
                                        priority = TerminalCompletionSourcePrior.NATIVE_SHELL,
                                    ),
                                ),
                            commandSpecs = listOf(TerminalCommandSpec(command, description = "$command description")),
                            shellProvider =
                                shellProvider { _, _, _ ->
                                    listOf(candidate(command, prefix.length, "shell", TerminalCompletionCandidateKind.COMMAND))
                                },
                        )
                    val job = launch { engine.completions(request(prefix).copy(shellCapabilities = shell)).toList(publications) }
                    runCurrent()
                    assertEquals(1, publications.size)
                    release.complete(Unit)
                    job.join()
                    for (publication in publications) {
                        val result = publication.single()
                        assertEquals(command, result.replacementText)
                        assertEquals("$command description", result.detail)
                        assertEquals(0, result.replacementStartOffset)
                        assertEquals(prefix.length, result.replacementEndOffset)
                    }
                }
            }
        }

    @Test
    fun `authoritative empty discovery excludes learned and host commands after prefix deletion`() =
        runTest {
            val learning =
                TerminalCompletionLearningStore().apply {
                    repeat(20) { recordCommandResult("cargo build", true, null, null, 10) }
                }
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                val engine =
                    TerminalCompletionEngines.fromSources(
                        sources =
                            listOf(
                                TerminalCompletionSourceEntry({ _, context, _ ->
                                    listOf(
                                        candidate("cargo", context.replacementEndOffset, "host", TerminalCompletionCandidateKind.COMMAND),
                                    )
                                }),
                            ),
                        learningStore = learning,
                        shellProvider = shellProvider { _, _, _ -> emptyList() },
                    )
                for (prefix in listOf("car", "carg", "ca", "c")) {
                    val publications = engine.completions(request(prefix).copy(shellCapabilities = shell)).toList()
                    assertTrue(publications.isNotEmpty())
                    assertTrue(publications.all { it.isEmpty() }, "$shell: $prefix -> $publications")
                }
            }
        }

    @Test
    fun `cd merges learned directories with live paths without files or native noise after deletion`() =
        runTest {
            val learning =
                TerminalCompletionLearningStore().apply {
                    repeat(12) { recordCommandResult("cd Documents/", true, null, "file:///home/user", 10) }
                    recordCommandResult("cd Documents/projects/", true, null, "file:///home/user", 10)
                }
            for (shell in listOf(TerminalShellCapabilities.POSIX, TerminalShellCapabilities.POWERSHELL)) {
                val engine =
                    MergedCompletionEngine(
                        sources =
                            listOf(
                                TerminalCompletionSourceEntry(
                                    TerminalCompletionSources.path {
                                        listOf(
                                            TerminalFileEntry("Downloads", true),
                                            TerminalFileEntry("Documents", true),
                                            TerminalFileEntry("Document.txt", false),
                                        )
                                    },
                                    priority = TerminalCompletionSourcePrior.DIRECTORY_PATH,
                                ),
                            ),
                        learningStore = learning,
                        clockEpochMillis = { 2_000_000_000_000L },
                        shellProvider =
                            shellProvider { _, _, _ ->
                                error("Native completer must not run for populated cd paths")
                            },
                        sourceFailureHandler = { _, _, failure -> throw AssertionError("Unexpected provider failure", failure) },
                    )
                for (prefix in listOf("D", "Doc", "Do", "D")) {
                    val line = "cd $prefix"
                    val results =
                        engine
                            .completions(
                                request(line).copy(shellCapabilities = shell, workingDirectoryUri = "file:///home/user"),
                            ).last()
                    val outcomes = results.map { line.replaceRange(it.replacementStartOffset, it.replacementEndOffset, it.replacementText) }
                    // Repeated use promotes Documents; one old nested visit does not qualify for replay.
                    val expected =
                        if (prefix == "Doc") {
                            listOf("cd Documents/")
                        } else {
                            listOf("cd Documents/", "cd Downloads/")
                        }
                    assertEquals(expected, outcomes)
                    assertEquals("path", results.first().source)
                    assertTrue(results.all { it.kind == TerminalCompletionCandidateKind.PATH })
                }
            }
        }

    @Test
    fun `request cancellation during metadata capture prevents shell queries and publication`() =
        runTest {
            var metadataRead = false
            var shellQueries = 0
            val publications = mutableListOf<List<TerminalCompletionCandidate>>()
            val failures = mutableListOf<Throwable>()
            val collection =
                launch {
                    val owner = currentCoroutineContext().job
                    val engine =
                        TerminalCompletionEngines.fromSources(
                            emptyList(),
                            sourceFailureHandler = { _, _, failure -> failures += failure },
                            shellProvider =
                                object : TerminalCompletionShellProvider {
                                    override fun commandAliases(): Map<String, String> {
                                        metadataRead = true
                                        owner.cancel()
                                        return emptyMap()
                                    }

                                    override suspend fun complete(
                                        request: TerminalCompletionRequest,
                                        context: TerminalCompletionContext,
                                        limit: Int,
                                    ): TerminalCompletionShellResult? {
                                        shellQueries++
                                        return null
                                    }
                                },
                        )
                    engine.completions(request("ca")).toList(publications)
                }
            collection.join()
            assertTrue(metadataRead)
            assertTrue(collection.isCancelled)
            assertEquals(0, shellQueries)
            assertTrue(publications.isEmpty())
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `alias metadata failures are diagnosed without losing shell authority and subsequent requests recover`() =
        runTest {
            val failure = IllegalStateException("metadata unavailable")
            val reported = mutableListOf<Throwable>()
            var failing = true
            var shellQueries = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    commandSpecs = listOf(TerminalCommandSpec("cargo"), TerminalCommandSpec("cat")),
                    sourceFailureHandler = { _, _, error -> reported += error },
                    shellProvider =
                        object : TerminalCompletionShellProvider {
                            override fun commandAliases(): Map<String, String> {
                                if (failing) throw failure
                                return emptyMap()
                            }

                            override suspend fun complete(
                                request: TerminalCompletionRequest,
                                context: TerminalCompletionContext,
                                limit: Int,
                            ): TerminalCompletionShellResult {
                                shellQueries++
                                return TerminalCompletionShellResult(
                                    listOf(TerminalCompletionCandidate("cargo", 0, 2, "shell", TerminalCompletionCandidateKind.COMMAND)),
                                )
                            }
                        },
                )
            repeat(2) {
                assertEquals(listOf("cargo"), engine.completions(request("ca")).last().map { it.replacementText })
                failing = false
            }
            assertSame(failure, reported.single())
            assertEquals(2, shellQueries)
        }

    @Test
    fun `fatal alias metadata failure propagates and is reported without querying the shell`() =
        runTest {
            val failure = AssertionError("fatal metadata failure")
            val reported = mutableListOf<Throwable>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    emptyList(),
                    sourceFailureHandler = { _, _, error -> reported += error },
                    shellProvider =
                        object : TerminalCompletionShellProvider {
                            override fun commandAliases(): Map<String, String> = throw failure

                            override suspend fun complete(
                                request: TerminalCompletionRequest,
                                context: TerminalCompletionContext,
                                limit: Int,
                            ): TerminalCompletionShellResult = error("must not query after fatal failure")
                        },
                )
            assertEquals(failure.message, assertFailsWith<AssertionError> { engine.completions(request("ca")).last() }.message)
            assertSame(failure, reported.single())
        }

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
                                shellProvider { request, context, limit ->
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
                        shellProvider { _, _, _ ->
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
                        shellProvider { _, _, _ ->
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
                    shellProvider = shellProvider { _, _, _ -> listOf(native) },
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
                        shellProvider { _, _, _ ->
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
                    shellProvider = shellProvider { _, _, _ -> null },
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
                        shellProvider { _, _, _ ->
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
                        shellProvider { _, _, _ ->
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
                shellProvider { _, _, _ ->
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
                        shellProvider { _, _, _ ->
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
    fun `ready learned arguments publish while native fallback is pending without suppressing it`() =
        runTest {
            val learning = TerminalCompletionLearningStore()
            repeat(2) { learning.recordCommandResult("git switch feature-old", true, null, null, 10) }
            val host = CompletableDeferred<List<TerminalCompletionCandidate>>()
            val native = CompletableDeferred<List<TerminalCompletionCandidate>>()
            var queries = 0
            val engine =
                MergedCompletionEngine(
                    sources = listOf(TerminalCompletionSourceEntry({ _, _, _ -> host.await() })),
                    learningStore = learning,
                    clockEpochMillis = { 20 },
                    shellProvider =
                        shellProvider { _, _, _ ->
                            queries++
                            native.await()
                        },
                )
            val results = ArrayList<List<TerminalCompletionCandidate>>()
            val job = launch { engine.completions(request("git switch fe")).toList(results) }
            runCurrent()
            assertEquals(0, queries)
            assertTrue(results.single().any { it.replacementText == "feature-old" })
            host.complete(emptyList())
            runCurrent()
            assertEquals(1, queries)
            assertTrue(results.single().any { it.replacementText == "feature-old" })
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
            repeat(2) { learning.recordCommandResult("git switch feature-old", true, null, null, 10) }
            var queries = 0
            val engine =
                MergedCompletionEngine(
                    sources = emptyList(),
                    learningStore = learning,
                    clockEpochMillis = { 20 },
                    shellProvider =
                        shellProvider { _, _, _ ->
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
                        shellProvider { _, _, _ ->
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
                        shellProvider { _, _, _ ->
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
