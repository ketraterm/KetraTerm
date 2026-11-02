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
package io.github.ketraterm.completion.host

import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TerminalCompletionSourceEnvironmentTest {
    private val projectDirectory = Path.of("completion-project").toAbsolutePath().normalize()

    @Test
    fun `repeated relative Git directory changes compose in input order`() {
        val request = request("git -C modules -C '../other repo' -C '' switch fe")

        assertEquals(projectDirectory.resolve("other repo"), resolvedPath(request))
        assertEquals(projectDirectory.toUri().toString(), request.workingDirectoryUri)
    }

    @Test
    fun `absolute Git directory changes replace the previous directory`() {
        val absolute = projectDirectory.resolveSibling("different repo")
        val request = request("git -C ignored -C '${absolute.toString().replace('\\', '/')}' -C child switch fe")

        assertEquals(absolute.resolve("child"), resolvedPath(request))
    }

    @Test
    fun `relative Git directory changes use the captured project only for absent metadata`() {
        val line = "git -C child switch fe"
        assertEquals(projectDirectory.resolve("child"), resolvedPath(request(line, directory = null)))
        assertNull(resolve(request(line, directory = null), project = null))
        assertNull(resolve(request(line, directory = "file://remote.example/repo")))
    }

    @Test
    fun `explicit unsupported environments cannot be replaced by an absolute Git path`() {
        val absolute = projectDirectory.toString().replace('\\', '/')
        for (uri in listOf("", "invalid uri", "ssh://remote/repo", "file://remote.example/repo")) {
            assertNull(resolve(request("git -C '$absolute' switch fe", directory = uri)), uri)
        }
    }

    @Test
    fun `pending and later directory values do not affect the active source environment`() {
        assertEquals(projectDirectory.resolve("first"), resolvedPath(request("git -C first -C ")))
        val line = "git -C first switch fe -C later"
        assertEquals(projectDirectory.resolve("first"), resolvedPath(request(line, cursor = line.indexOf("fe") + 2)))
    }

    @Test
    fun `directory changes belong only to their active command segment`() {
        assertEquals(projectDirectory, resolvedPath(request("git -C other status && git switch fe")))
        assertEquals(projectDirectory.resolve("child"), resolvedPath(request("git -C other status && git -C child switch fe")))
        val plain = request("cat -C other file")
        assertSame(plain, resolve(plain))
    }

    @Test
    fun `invalid directory values are rejected and empty values preserve the current directory`() {
        assertNull(resolve(request("git -C 'bad\u0000path' switch fe")))
        assertEquals(projectDirectory, resolvedPath(request("git -C '' switch fe")))
        val unknown = request("git -C '' switch fe", directory = null)
        assertSame(unknown, resolve(unknown, project = null))
    }

    private fun resolvedPath(request: TerminalCompletionRequest): Path? =
        resolve(request)?.workingDirectoryUri?.let(TerminalLocalFileUriResolver::resolve)

    private fun resolve(
        request: TerminalCompletionRequest,
        project: Path? = projectDirectory,
    ): TerminalCompletionRequest? =
        TerminalCompletionSourceEnvironment.resolve(request, TerminalCompletionContext.resolve(request), project)

    private fun request(
        line: String,
        directory: String? = projectDirectory.toUri().toString(),
        cursor: Int = line.length,
    ) = TerminalCompletionRequest(line, cursor, workingDirectoryUri = directory, shellCapabilities = TerminalShellCapabilities.POSIX)
}
