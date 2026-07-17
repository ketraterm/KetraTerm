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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests context-aware completion from a host-owned ready fuzzy path snapshot. */
class FuzzyPathCompletionSourceTest {
    private val source =
        TerminalCompletionSources.fuzzyPath(
            sourceId = "project-file",
            entriesProvider = {
                listOf(
                    TerminalFuzzyPathEntry("src/main/kotlin/FuzzyTarget.kt", isDirectory = false),
                    TerminalFuzzyPathEntry("src/main/resources", isDirectory = true),
                    TerminalFuzzyPathEntry("src/main/.generated/Hidden.kt", isDirectory = false),
                    TerminalFuzzyPathEntry("src/test/kotlin/FuzzyTargetTest.kt", isDirectory = false),
                    TerminalFuzzyPathEntry("notes/My File.txt", isDirectory = false),
                )
            },
        )

    @Test
    fun `finds a project file from a basename subsequence in a declared path argument`() {
        val candidates = source.complete(request("cat FzT"))

        assertEquals(
            listOf("src/main/kotlin/FuzzyTarget.kt", "src/test/kotlin/FuzzyTargetTest.kt"),
            candidates.map(TerminalCompletionCandidate::replacementText),
        )
        assertTrue(candidates.all { it.source == "project-file" && it.kind == TerminalCompletionCandidateKind.PATH })
    }

    @Test
    fun `filters fuzzy results to directories for cd`() {
        val candidates = source.complete(request("cd rs"))

        assertEquals(listOf("src/main/resources/"), candidates.map(TerminalCompletionCandidate::replacementText))
        assertEquals(listOf("project directory"), candidates.map(TerminalCompletionCandidate::detail))
    }

    @Test
    fun `leaves an empty path prefix to direct directory completion`() {
        assertTrue(source.complete(request("cd ")).isEmpty())
    }

    @Test
    fun `can opt into an empty prefix for a small context-specific path snapshot`() {
        val statusSource =
            TerminalCompletionSources.fuzzyPath(
                sourceId = "git-status-path",
                entriesProvider = { listOf(TerminalFuzzyPathEntry("src/Changed.kt", isDirectory = false)) },
                requiresNonEmptyPrefix = false,
                allowedCommandNames = setOf("add", "restore", "rm", "diff"),
            )

        assertEquals(
            listOf("src/Changed.kt"),
            statusSource.complete(request("git add ")).map(TerminalCompletionCandidate::replacementText),
        )
        assertEquals(
            listOf("src/Changed.kt"),
            statusSource.complete(request("git restore ")).map(TerminalCompletionCandidate::replacementText),
        )
        assertEquals(
            listOf("src/Changed.kt"),
            statusSource.complete(request("git rm ")).map(TerminalCompletionCandidate::replacementText),
        )
        assertTrue(statusSource.complete(request("cd ")).isEmpty())
    }

    @Test
    fun `hides nested dot directories until the active path component starts with a dot`() {
        assertTrue(source.complete(request("cat Hidden")).isEmpty())

        assertEquals(
            listOf("src/main/.generated/Hidden.kt"),
            source.complete(request("cat src/main/.g")).map(TerminalCompletionCandidate::replacementText),
        )
    }

    @Test
    fun `quotes fuzzy replacements using the active shell contract`() {
        assertEquals(
            listOf("notes/My\\ File.txt"),
            source.complete(request("cat MyF")).map(TerminalCompletionCandidate::replacementText),
        )
        assertTrue(
            source
                .complete(request("cat MyF", TerminalShellCapabilities.PLAIN))
                .isEmpty(),
        )
    }

    @Test
    fun `does not provide generic fuzzy paths in non-path command positions`() {
        assertTrue(source.complete(request("git sw")).isEmpty())
    }

    private fun request(
        commandLine: String,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = "file:///project",
            shellCapabilities = shellCapabilities,
        )
}
