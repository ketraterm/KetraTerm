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
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

class MergedCompletionEngineTest {
    @Test
    fun `fast source publishes before slow source and final emission reranks all results`(): Unit =
        runBlocking {
            val fastCompleted = CompletableDeferred<Unit>()
            val slowStarted = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    fastCompleted.complete(Unit)
                                    listOf(candidate("fast", source = "fast"))
                                },
                                priority = 0,
                            ),
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    slowStarted.complete(Unit)
                                    releaseSlow.await()
                                    listOf(candidate("slow", source = "slow"))
                                },
                                priority = 100,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val collected = async { engine.completions(request()).toList() }
            fastCompleted.await()
            slowStarted.await()
            yield()
            assertFalse(collected.isCompleted)
            releaseSlow.complete(Unit)

            val emissions = collected.await()
            assertEquals(listOf("fast"), emissions.first().map { it.replacementText })
            assertEquals(listOf("slow", "fast"), emissions.last().map { it.replacementText })
        }

    @Test
    fun `source failure is isolated from progressive results`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(TerminalCompletionSource { _, _, _ -> error("failed source") }, 100),
                            entry(source(candidate("available")), 0),
                        ),
                    commandSpecs = emptyList(),
                )

            assertEquals(listOf("available"), engine.complete(request()).map { it.replacementText })
        }

    @Test
    fun `source cancellation terminates the request and cancels siblings`() =
        runBlocking {
            val siblingStarted = CompletableDeferred<Unit>()
            val siblingCancelled = CompletableDeferred<Unit>()
            val cancelSource = CompletableDeferred<Unit>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    cancelSource.await()
                                    throw CancellationException("source cancelled")
                                },
                                0,
                            ),
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    siblingStarted.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        siblingCancelled.complete(Unit)
                                    }
                                },
                                0,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val collection = async { engine.completions(request()).toList() }
            siblingStarted.await()
            cancelSource.complete(Unit)

            assertFailsWith<CancellationException> { collection.await() }
            siblingCancelled.await()
        }

    @Test
    fun `cancelling collection cancels every source child`(): Unit =
        runBlocking {
            val bothStarted = CompletableDeferred<Unit>()
            val bothCancelled = CompletableDeferred<Unit>()
            var started = 0
            var cancelled = 0
            val source =
                TerminalCompletionSource { _, _, _ ->
                    started++
                    if (started == 2) bothStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                        if (cancelled == 2) bothCancelled.complete(Unit)
                    }
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(source, 0), entry(source, 0)),
                    commandSpecs = emptyList(),
                )

            val collection = launch { engine.completions(request()).collect {} }
            bothStarted.await()
            collection.cancelAndJoin()

            bothCancelled.await()
            assertEquals(2, cancelled)
        }

    @Test
    fun `merged engine starts sources in parallel within the request scope`(): Unit =
        runBlocking {
            val bothStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var started = 0
            val source =
                TerminalCompletionSource { _, _, _ ->
                    started++
                    if (started == 2) bothStarted.complete(Unit)
                    release.await()
                    emptyList()
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(source, 0), entry(source, 0)),
                    commandSpecs = emptyList(),
                )

            val completion = async { engine.complete(request()) }
            bothStarted.await()
            assertEquals(2, started)
            release.complete(Unit)
            completion.await()
        }

    @Test
    fun `source priority ranks ahead of candidate score`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(source(candidate("status", source = "spec", score = 900)), priority = 0),
                        entry(source(candidate("switch", source = "mru", score = 1)), priority = 100),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
            assertEquals(listOf("mru", "spec"), candidates.map { it.source })
        }

    @Test
    fun `deduplicates by replacement range and replacement text using highest ranked candidate`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(source(candidate("status", detail = "static", source = "spec", score = 900)), priority = 0),
                        entry(source(candidate("status", detail = "recent", source = "mru", score = 1)), priority = 100),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("status"), candidates.map { it.replacementText })
            assertEquals("recent", candidates.single().detail)
            assertEquals("mru", candidates.single().source)
        }

    @Test
    fun `keeps same replacement text when replacement range differs`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(source(candidate("status", start = 0, end = 1, source = "left")), priority = 0),
                        entry(source(candidate("status", start = 4, end = 5, source = "right")), priority = 0),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("left", "right"), candidates.map { it.source })
            assertEquals(listOf(0, 4), candidates.map { it.replacementStartOffset })
        }

    @Test
    fun `returns the complete bounded source union after merging and sorting`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate("alpha", score = 10),
                                candidate("charlie", score = 30),
                            ),
                            priority = 0,
                        ),
                        entry(source(candidate("bravo", score = 20)), priority = 0),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("bravo", "charlie", "alpha"), candidates.map { it.replacementText })
        }

    @Test
    fun `collects fixed source budget before global context ranking`() =
        runBlocking {
            val rawCandidates =
                buildList {
                    repeat(8) { index ->
                        add(
                            candidate(
                                replacement = "git remembered-${index + 1}",
                                source = "mixed",
                                kind = TerminalCompletionCandidateKind.HISTORY,
                                score = 1_000 - index,
                            ),
                        )
                    }
                    add(
                        candidate(
                            replacement = "status",
                            start = 4,
                            end = 5,
                            source = "mixed",
                            kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                            score = 900,
                        ),
                    )
                }
            var collectionLimit = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    TerminalCompletionSource { _, _, limit ->
                        collectionLimit = limit
                        rawCandidates.take(limit)
                    },
                )

            val candidates = engine.complete(request(commandLine = "git s"))

            assertEquals(256, collectionLimit)
            assertEquals(11, candidates.size)
            assertEquals("spec", candidates.first().source)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.first().kind)
        }

    @Test
    fun `each source receives the fixed safety budget and ranked union is not UI limited`() =
        runBlocking {
            var suppliedLimit = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, limit ->
                                    suppliedLimit = limit
                                    List(300) { candidate("candidate-$it", score = 300 - it) }
                                },
                                0,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val candidates = engine.complete(request())

            assertEquals(256, suppliedLimit)
            assertEquals(256, candidates.size)
        }

    @Test
    fun `large provider result preserves exact deterministic ordering`() =
        runBlocking {
            val candidates =
                ArrayList<TerminalCompletionCandidate>(1_024).apply {
                    var index = 0
                    while (index < 1_024) {
                        add(
                            candidate(
                                replacement = "candidate-$index",
                                display = "display-${1_024 - index}",
                                score = (index * 37) % 101,
                            ),
                        )
                        index++
                    }
                }
            val engine = TerminalCompletionEngines.fromSources(TerminalCompletionSource { _, _, _ -> candidates })

            val actual = engine.complete(request())
            val expected =
                candidates
                    .take(256)
                    .sortedWith(
                        compareByDescending<TerminalCompletionCandidate> { it.score }
                            .thenBy { it.displayText }
                            .thenBy { it.replacementText },
                    )

            assertEquals(expected.map { it.replacementText }, actual.map { it.replacementText })
            assertEquals(actual.map { it.score }.sortedDescending(), actual.map { it.score })
        }

    @Test
    fun `equal ranking falls back to display text for deterministic order`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    source(
                        candidate("zeta", display = "zeta", score = 10),
                        candidate("alpha", display = "alpha", score = 10),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("alpha", "zeta"), candidates.map { it.replacementText })
        }

    @Test
    fun `path candidates outrank history in cd positional path position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "cd remembered",
                                    start = 0,
                                    end = 3,
                                    source = "mru",
                                    kind = TerminalCompletionCandidateKind.HISTORY,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "src/",
                                    start = 3,
                                    end = 3,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "cd "))

            assertEquals(listOf("src/", "cd remembered"), candidates.map { it.replacementText })
        }

    @Test
    fun `subcommand candidates outrank history and paths in subcommand position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "git stash",
                                    start = 0,
                                    end = 5,
                                    source = "mru",
                                    kind = TerminalCompletionCandidateKind.HISTORY,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "src/",
                                    start = 4,
                                    end = 5,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "status",
                                    start = 4,
                                    end = 5,
                                    source = "spec",
                                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git s"))

            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.first().kind)
        }

    @Test
    fun `static option values outrank history in option value position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "aws --output table",
                                    start = 0,
                                    end = 14,
                                    source = "mru",
                                    kind = TerminalCompletionCandidateKind.HISTORY,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "text",
                                    start = 13,
                                    end = 14,
                                    source = "spec",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "aws --output t"))

            assertEquals(listOf("table", "text"), candidates.map { it.replacementText })
            assertTrue(candidates.all { it.source == "spec" })
        }

    @Test
    fun `option names outrank history and paths in option name position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "git status",
                                    start = 0,
                                    end = 5,
                                    source = "mru",
                                    kind = TerminalCompletionCandidateKind.HISTORY,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "src/",
                                    start = 4,
                                    end = 5,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "--help",
                                    start = 4,
                                    end = 5,
                                    source = "spec",
                                    kind = TerminalCompletionCandidateKind.OPTION,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git -"))

            assertEquals("--help", candidates.first().replacementText)
        }

    @Test
    fun `dynamic positional domain candidates outrank paths and history`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "main/",
                                    start = 11,
                                    end = 14,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "git switch main",
                                    start = 0,
                                    end = 14,
                                    source = "mru",
                                    kind = TerminalCompletionCandidateKind.HISTORY,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "main",
                                    start = 11,
                                    end = 14,
                                    source = "git-branches",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git switch mai"))

            assertEquals("main", candidates.first().replacementText)
            assertEquals("git-branches", candidates.first().source)
        }

    @Test
    fun `dynamic option domain candidates outrank generic argument candidates`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "default-history",
                                    start = 20,
                                    end = 23,
                                    source = "stats",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "default",
                                    start = 20,
                                    end = 23,
                                    source = "kubernetes",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                    valueDomain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "kubectl --namespace def"))

            assertEquals(listOf("default", "default-history"), candidates.map { it.replacementText })
        }

    @Test
    fun `empty host source list still composes static specifications`() =
        runBlocking {
            val engine = TerminalCompletionEngines.fromSources(emptyList<TerminalCompletionSourceEntry>())

            assertEquals(
                listOf("status", "stash", "switch"),
                engine.complete(request(commandLine = "git s")).map { it.replacementText },
            )
        }

    @Test
    fun `operator region suppresses every source before source evaluation`() =
        runBlocking {
            var sourceCalls = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    TerminalCompletionSource { _, _, _ ->
                        sourceCalls++
                        listOf(candidate("unexpected"))
                    },
                )
            val commandLine = "git status && cd"

            val candidates =
                engine.complete(
                    TerminalCompletionRequest(
                        commandLine = commandLine,
                        cursorOffset = commandLine.indexOf("&&") + 1,
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )

            assertTrue(candidates.isEmpty())
            assertEquals(0, sourceCalls)
        }

    @Test
    fun `sources with the same command specs share one resolved context`() =
        runBlocking {
            val contexts = ArrayList<TerminalCompletionContext>()
            val specs = TerminalCommandSpecs.defaults()
            val source =
                TerminalCompletionSource { _, context, _ ->
                    contexts += context
                    emptyList()
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(source, 0), entry(source, 0)),
                    commandSpecs = specs,
                )

            engine.complete(request(commandLine = "git --"))

            assertEquals(2, contexts.size)
            assertSame(contexts[0], contexts[1])
        }

    private fun entry(
        source: TerminalCompletionSource,
        priority: Int,
    ): TerminalCompletionSourceEntry =
        TerminalCompletionSourceEntry(
            source = source,
            priority = priority,
        )

    private fun source(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { _, _, _ -> candidates.toList() }

    private fun candidate(
        replacement: String,
        start: Int = 0,
        end: Int = 1,
        display: String = replacement,
        detail: String = "",
        source: String = "test",
        kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.ARGUMENT,
        score: Int = 0,
        valueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacement,
            replacementStartOffset = start,
            replacementEndOffset = end,
            source = source,
            kind = kind,
            displayText = display,
            detail = detail,
            score = score,
            valueDomain = valueDomain,
        )

    private fun request(commandLine: String = "s"): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
        )
}
