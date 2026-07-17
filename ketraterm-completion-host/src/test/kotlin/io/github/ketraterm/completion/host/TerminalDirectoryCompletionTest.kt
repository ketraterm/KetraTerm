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

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class TerminalDirectoryCompletionTest {
    @Test
    fun `remote authority is rejected without local reinterpretation`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false)

        assertNull(resolver.resolve(request(workingDirectoryUri = "file://remote.example/work")))
    }

    @Test
    fun `throwing scan clears its owned in-flight marker and permits retry`() {
        val scheduler = RecordingScheduler()
        var attempts = 0
        val expected = listOf(TerminalFileEntry("recovered", isDirectory = true))
        val provider =
            provider(scheduler) { _, _ ->
                attempts++
                if (attempts == 1) error("scan failed")
                expected
            }

        provider.listDirectory(request())
        try {
            scheduler.runNext()
            fail("Expected the first scan to fail")
        } catch (expectedFailure: IllegalStateException) {
            assertEquals("scan failed", expectedFailure.message)
        }

        assertTrue(provider.listDirectory(request()).isEmpty())
        scheduler.runNext()
        assertEquals(expected, provider.listDirectory(request()))
    }

    @Test
    fun `returning to queued key adopts the latest accepted generation`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val provider =
            provider(scheduler, onSnapshotChanged = { publications++ }) { _, prefix ->
                listOf(TerminalFileEntry(prefix, isDirectory = true))
            }

        provider.listDirectory(request(entryNamePrefix = "a"))
        provider.listDirectory(request(entryNamePrefix = "b"))
        provider.listDirectory(request(entryNamePrefix = "a"))
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals("a", provider.listDirectory(request(entryNamePrefix = "a")).single().name)
    }

    private fun provider(
        scheduler: TerminalCompletionLoadScheduler,
        onSnapshotChanged: () -> Unit = {},
        scanner: TerminalDirectoryScanner,
    ): TerminalAsyncFileSystemProvider =
        TerminalAsyncFileSystemProvider(
            scheduler = scheduler,
            onSnapshotChanged = onSnapshotChanged,
            resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false),
            scanner = scanner,
            snapshotTtlNanos = Long.MAX_VALUE,
        )

    private fun request(
        workingDirectoryUri: String =
            Path
                .of(".")
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString(),
        directoryPrefix: String = "",
        entryNamePrefix: String = "",
    ): TerminalDirectoryListingRequest =
        TerminalDirectoryListingRequest(workingDirectoryUri, directoryPrefix, entryNamePrefix)

    private class RecordingScheduler : TerminalCompletionLoadScheduler {
        private val tasks = ArrayDeque<suspend () -> Unit>()

        override fun schedule(work: suspend () -> Unit): Boolean {
            tasks.addLast(work)
            return true
        }

        fun runNext() {
            runBlocking { tasks.removeFirst().invoke() }
        }
    }
}
