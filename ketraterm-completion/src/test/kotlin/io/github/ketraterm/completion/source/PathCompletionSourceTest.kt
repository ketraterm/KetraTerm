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

class PathCompletionSourceTest {
    private val mockProvider = FakeFileSystemProvider()
    private val source = PathCompletionSource(mockProvider)

    init {
        mockProvider.register(
            "file:///project/",
            listOf(
                TerminalFileEntry("src", isDirectory = true),
                TerminalFileEntry("README.md", isDirectory = false),
                TerminalFileEntry("LICENSE", isDirectory = false),
            ),
        )
        mockProvider.register(
            "file:///project/src/",
            listOf(
                TerminalFileEntry("main", isDirectory = true),
                TerminalFileEntry("test", isDirectory = true),
                TerminalFileEntry("App.kt", isDirectory = false),
            ),
        )
        mockProvider.register(
            "file:///usr/",
            listOf(
                TerminalFileEntry("local", isDirectory = true),
                TerminalFileEntry("bin", isDirectory = true),
            ),
        )
    }

    @Test
    fun `completes directories and files in current working directory`() {
        val request = request("cat R", "file:///project")
        val candidates = source.complete(request)

        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        assertEquals("README.md", candidate.replacementText)
        assertEquals("README.md", candidate.displayText)
        assertEquals(TerminalCompletionCandidateKind.PATH, candidate.kind)
        assertEquals("file", candidate.detail)
    }

    @Test
    fun `completes nested relative path with forward slashes`() {
        val request = request("cat src/m", "file:///project")
        val candidates = source.complete(request)

        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        assertEquals("src/main/", candidate.replacementText)
        assertEquals("main/", candidate.displayText)
        assertEquals("directory", candidate.detail)
    }

    @Test
    fun `completes nested relative path with Windows backslashes`() {
        val request = request("cat src\\\\m", "file:///project")
        val candidates = source.complete(request)

        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        assertEquals("src\\main\\", candidate.replacementText)
        assertEquals("main\\", candidate.displayText)
    }

    @Test
    fun `completes absolute paths bypassing working directory`() {
        val request = request("ls /usr/l", "file:///project")
        val candidates = source.complete(request)

        assertEquals(1, candidates.size)
        assertEquals("/usr/local/", candidates.first().replacementText)
        assertEquals("local/", candidates.first().displayText)
    }

    @Test
    fun `suppresses path completion when prefix is an option flag`() {
        val request = request("git -", "file:///project")
        val candidates = source.complete(request)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `suppresses path completion in command position unless prefix is path-like`() {
        // typing "s" in command position -> should not complete files
        val request = request("s", "file:///project")
        val candidates = source.complete(request)
        assertTrue(candidates.isEmpty())

        // typing "./s" in command position -> explicitly path-like, should complete
        val pathLikeRequest = request("./s", "file:///project")
        val pathLikeCandidates = source.complete(pathLikeRequest)
        assertEquals(1, pathLikeCandidates.size)
        assertEquals("./src/", pathLikeCandidates.first().replacementText)
    }

    @Test
    fun `returns empty list when working directory is missing or invalid`() {
        val request = request("cat R", null)
        assertTrue(source.complete(request).isEmpty())

        val invalidRequest = request("cat R", "invalid-uri")
        assertTrue(source.complete(invalidRequest).isEmpty())
    }

    private fun request(
        commandLine: String,
        workingDirectoryUri: String?,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = workingDirectoryUri,
        )

    private class FakeFileSystemProvider : TerminalFileSystemProvider {
        private val directories = HashMap<String, List<TerminalFileEntry>>()

        fun register(
            uri: String,
            entries: List<TerminalFileEntry>,
        ) {
            directories[canonicalizeDirectoryUri(uri)] = entries
        }

        override fun listDirectory(directoryUri: String): List<TerminalFileEntry> =
            directories[canonicalizeDirectoryUri(directoryUri)] ?: emptyList()
    }
}
