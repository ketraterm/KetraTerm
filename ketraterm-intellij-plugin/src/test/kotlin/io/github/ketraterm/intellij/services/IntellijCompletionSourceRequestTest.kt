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
package io.github.ketraterm.intellij.services

import io.github.ketraterm.completion.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntellijCompletionSourceRequestTest {
    @Test
    fun `Git status loader uses the request working directory`() =
        runBlocking {
            var requestedDirectory: String? = null
            var requestedLimit = 0
            val source =
                intellijGitStatusPathCompletionSource { workingDirectoryUri, limit ->
                    requestedDirectory = workingDirectoryUri
                    requestedLimit = limit
                    listOf(TerminalFuzzyPathEntry("src/Changed.kt", isDirectory = false))
                }

            val candidates = engine(source).complete(request("git add "))

            assertEquals(WORKING_DIRECTORY, requestedDirectory)
            assertEquals(256, requestedLimit)
            assertTrue(candidates.any { it.replacementText == "src/Changed.kt" })
        }

    @Test
    fun `Gradle loader uses the request working directory`() =
        runBlocking {
            var requestedDirectory: String? = null
            var requestedLimit = 0
            val source =
                intellijGradleTaskCompletionSource { workingDirectoryUri, limit ->
                    requestedDirectory = workingDirectoryUri
                    requestedLimit = limit
                    listOf(TerminalGradleTask(":test", projectDirectory = "."))
                }

            val candidates = engine(source).complete(request("gradle te"))

            assertEquals(WORKING_DIRECTORY, requestedDirectory)
            assertEquals(256, requestedLimit)
            assertTrue(candidates.any { it.replacementText == "test" })
        }

    @Test
    fun `project file loader uses one request directory and prefix`() =
        runBlocking {
            var requestedDirectory: String? = null
            var requestedPrefix: String? = null
            var requestedLimit = 0
            val source =
                intellijProjectFileCompletionSource { workingDirectoryUri, prefix, limit ->
                    requestedDirectory = workingDirectoryUri
                    requestedPrefix = prefix
                    requestedLimit = limit
                    listOf(TerminalFuzzyPathEntry("src/main.kt", isDirectory = false))
                }

            val candidates = engine(source).complete(request("cat ma"))

            assertEquals(WORKING_DIRECTORY, requestedDirectory)
            assertEquals("ma", requestedPrefix)
            assertEquals(256, requestedLimit)
            assertTrue(candidates.any { it.replacementText == "src/main.kt" })
        }

    private fun engine(source: TerminalCompletionSource): TerminalCompletionEngine =
        TerminalCompletionEngines.fromSources(listOf(TerminalCompletionSourceEntry(source, priority = 1)))

    private fun request(commandLine: String): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = WORKING_DIRECTORY,
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )

    private companion object {
        private const val WORKING_DIRECTORY = "file:///request-directory"
    }
}
