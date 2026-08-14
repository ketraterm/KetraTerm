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
package io.github.ketraterm.completion.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalCompletionSessionRegistryTest {
    @Test
    fun `replacement retires the previous MRU and owns later records`() =
        runBlocking {
            val registry = registry()
            val previous = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "git status", null, null)
            assertEquals(listOf("git status"), previous.engine.complete(request()).map { it.replacementText })

            val replacement = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "git switch main", null, null)

            assertTrue(previous.engine.complete(request()).isEmpty())
            assertEquals(listOf("git switch main"), replacement.engine.complete(request()).map { it.replacementText })
            registry.close()
        }

    @Test
    fun `closing a replaced handle cannot remove the current session`() =
        runBlocking {
            val registry = registry()
            val previous = registry.openSession("session", EMPTY_FILE_SYSTEM)
            val replacement = registry.openSession("session", EMPTY_FILE_SYSTEM)

            previous.close()
            registry.recordSuccessfulCommand("session", "git status", null, null)

            assertEquals(listOf("git status"), replacement.engine.complete(request()).map { it.replacementText })
            registry.close()
        }

    @Test
    fun `close clears sessions ignores late records and rejects reopening`(): Unit =
        runBlocking {
            val registry = registry()
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "git status", null, null)

            registry.close()
            registry.close()
            registry.recordSuccessfulCommand("session", "git switch main", null, null)

            assertTrue(session.engine.complete(request()).isEmpty())
            assertFailsWith<IllegalStateException> { registry.openSession("replacement", EMPTY_FILE_SYSTEM) }
        }

    private fun registry(): TerminalCompletionSessionRegistry =
        TerminalCompletionSessionRegistry(commandSpecs = emptyList(), sessionMruCapacity = 4)

    private fun request(): TerminalCompletionRequest = TerminalCompletionRequest(commandLine = "git", cursorOffset = 3)

    private companion object {
        private val EMPTY_FILE_SYSTEM = TerminalFileSystemProvider { emptyList() }
    }
}
