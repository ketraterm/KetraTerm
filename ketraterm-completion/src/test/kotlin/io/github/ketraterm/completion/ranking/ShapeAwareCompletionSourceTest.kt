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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCommandLineClassifier
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeAwareCompletionSourceTest {
    @Test
    fun `accepted matching shape boosts candidate above otherwise higher score candidate`() {
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate("switch", score = 300),
                    ),
                shapeStatsProvider = {
                    listOf(
                        shapeStats(
                            commandLine = "git switch main",
                            acceptedCount = 4,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s", profileId = "bash", workingDirectoryUri = "file:///repo"))

        assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
        assertTrue(candidates[0].score > candidates[1].score)
    }

    @Test
    fun `dismissed matching shape demotes candidate`() {
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate("switch", score = 300),
                    ),
                shapeStatsProvider = {
                    listOf(
                        shapeStats(
                            commandLine = "git status",
                            dismissedCount = 4,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s", profileId = "bash", workingDirectoryUri = "file:///repo"))

        assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
    }

    @Test
    fun `profile and working directory matching shape receives stronger adjustment`() {
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate("switch", score = 320),
                    ),
                shapeStatsProvider = {
                    listOf(
                        shapeStats(
                            commandLine = "git status",
                            acceptedCount = 1,
                            profileId = "bash",
                            workingDirectoryUri = "file:///other",
                        ),
                        shapeStats(
                            commandLine = "git switch main",
                            acceptedCount = 1,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s", profileId = "bash", workingDirectoryUri = "file:///repo"))

        assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
    }

    @Test
    fun `specific rejected shape overrides broad accepted shape`() {
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate("switch", score = 300),
                    ),
                shapeStatsProvider = {
                    listOf(
                        shapeStats(
                            commandLine = "git status",
                            acceptedCount = 10,
                        ),
                        shapeStats(
                            commandLine = "git status",
                            dismissedCount = 4,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s", profileId = "bash", workingDirectoryUri = "file:///repo"))

        assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
    }

    @Test
    fun `spec aware ranking canonicalizes learned aliases`() {
        val specs =
            listOf(
                TerminalCommandSpec(
                    name = "git",
                    subcommands =
                        listOf(
                            TerminalCommandSpec("checkout", aliases = listOf("co")),
                            TerminalCommandSpec("commit"),
                        ),
                ),
            )
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("commit", score = 320),
                        candidate("checkout", score = 300),
                    ),
                shapeStatsProvider = {
                    listOf(
                        specShapeStats(
                            commandLine = "git co private-branch",
                            specs = specs,
                            acceptedCount = 4,
                        ),
                    )
                },
                commandSpecs = specs,
            )

        val candidates = source.complete(request("git c"))

        assertEquals(listOf("checkout", "commit"), candidates.map { it.replacementText })
    }

    @Test
    fun `spec aware ranking boosts nested command family independent of private argument`() {
        val specs =
            listOf(
                TerminalCommandSpec(
                    name = "docker",
                    subcommands =
                        listOf(
                            TerminalCommandSpec(
                                name = "compose",
                                subcommands =
                                    listOf(
                                        TerminalCommandSpec("up"),
                                        TerminalCommandSpec("ps"),
                                    ),
                            ),
                        ),
                ),
            )
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("ps", score = 320, replacementStartOffset = 15, replacementEndOffset = 15),
                        candidate("up", score = 300, replacementStartOffset = 15, replacementEndOffset = 15),
                    ),
                shapeStatsProvider = {
                    listOf(
                        specShapeStats(
                            commandLine = "docker compose up private-service",
                            specs = specs,
                            successCount = 6,
                            acceptedCount = 2,
                        ),
                    )
                },
                commandSpecs = specs,
            )

        val candidates = source.complete(request("docker compose "))

        assertEquals(listOf("up", "ps"), candidates.map { it.replacementText })
        assertTrue(candidates[0].score > candidates[1].score)
    }

    @Test
    fun `shape ranking keeps private arguments out of family keys`() {
        val specs =
            listOf(
                TerminalCommandSpec(
                    name = "npm",
                    subcommands = listOf(TerminalCommandSpec("run")),
                ),
            )

        val stats = specShapeStats(commandLine = "npm run secret-script", specs = specs, successCount = 1)

        assertTrue("secret-script" !in stats.shape.normalizedShapeKey)
        assertEquals(listOf("run"), stats.shape.subcommands)
        assertEquals(1, stats.shape.positionalArgumentCount)
    }

    @Test
    fun `shape stats do not create candidates`() {
        val source =
            ShapeAwareCompletionSource(
                delegate = TerminalCompletionSource.NONE,
                shapeStatsProvider = {
                    listOf(
                        shapeStats(commandLine = "git status", successCount = 10),
                    )
                },
            )

        assertTrue(source.complete(request("git s")).isEmpty())
    }

    @Test
    fun `candidate range outside request is ignored without throwing`() {
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320, replacementStartOffset = 4, replacementEndOffset = 99),
                        candidate("switch", score = 300),
                    ),
                shapeStatsProvider = {
                    listOf(shapeStats(commandLine = "git status", acceptedCount = 10))
                },
            )

        val candidates = source.complete(request("git s"))

        assertEquals(listOf("status", "switch"), candidates.map { it.replacementText })
    }

    @Test
    fun `candidate range that splits surrogate pair is ignored without throwing`() {
        val source =
            ShapeAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320, replacementStartOffset = 1, replacementEndOffset = 2),
                        candidate("switch", score = 300, replacementStartOffset = 3, replacementEndOffset = 3),
                    ),
                shapeStatsProvider = {
                    listOf(shapeStats(commandLine = "status", acceptedCount = 10))
                },
            )

        val candidates = source.complete(request("a\uD83D\uDE02"))

        assertEquals(listOf("status", "switch"), candidates.map { it.replacementText })
    }

    private fun fixedSource(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { candidates.toList() }

    private fun candidate(
        replacementText: String,
        score: Int,
        replacementStartOffset: Int = 4,
        replacementEndOffset: Int = 5,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacementText,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            displayText = replacementText,
            source = "test",
            kind = TerminalCompletionCandidateKind.SUBCOMMAND,
            score = score,
        )

    private fun shapeStats(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        useCount: Int = 0,
        successCount: Int = 0,
        failureCount: Int = 0,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
    ): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape = TerminalCommandLineShape.fromCommandLine(commandLine)!!,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = 100,
        )

    private fun specShapeStats(
        commandLine: String,
        specs: List<TerminalCommandSpec>,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        useCount: Int = 0,
        successCount: Int = 0,
        failureCount: Int = 0,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
    ): TerminalCommandShapeStats =
        TerminalCommandShapeStats(
            shape = TerminalCommandLineClassifier.classify(commandLine, specs)!!.shape,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = 100,
        )

    private fun request(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )
}
