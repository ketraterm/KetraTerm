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

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/** Contract tests for local-path resolution and asynchronous directory snapshots. */
class IntellijDirectoryCompletionTest {
    /** Verifies that a remote file authority is never reinterpreted as a local path. */
    @Test
    fun `remote authority is rejected without local reinterpretation`() {
        val resolver = IntellijCompletionPathResolver(homeDirectory = Path.of("home"), windows = false)

        assertNull(resolver.resolve(request(workingDirectoryUri = "file://remote.example/work")))
    }

    /** Verifies that tilde expansion uses only the explicitly supplied host home. */
    @Test
    fun `tilde expansion uses explicit host home`() {
        val home = Path.of("host-home").toAbsolutePath().normalize()
        val resolver = IntellijCompletionPathResolver(homeDirectory = home, windows = false)

        assertEquals(home.resolve("projects"), resolver.resolve(request(directoryPrefix = "~/projects/")))
    }

    /** Verifies that loading is asynchronous and published snapshots are reusable. */
    @Test
    fun `ready snapshot is immutable and published after background work`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val expected = listOf(TerminalFileEntry("src", isDirectory = true))
        val provider = provider(scheduler, { publications++ }) { _, _ -> expected }

        assertTrue(provider.listDirectory(request()).isEmpty())
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals(expected, provider.listDirectory(request()))
    }

    /** Verifies that a superseded generation cannot publish its stale snapshot. */
    @Test
    fun `superseded request cannot publish stale refresh`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val provider =
            provider(scheduler, { publications++ }) { _, prefix ->
                listOf(TerminalFileEntry(prefix, isDirectory = true))
            }

        provider.listDirectory(request(entryNamePrefix = "a"))
        provider.listDirectory(request(entryNamePrefix = "b"))
        scheduler.runNext()
        assertEquals(0, publications)
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals("b", provider.listDirectory(request(entryNamePrefix = "b")).single().name)
    }

    /** Verifies that a throwing scanner clears in-flight state and permits retry. */
    @Test
    fun `failed scan can be retried`() {
        val scheduler = RecordingScheduler()
        var attempts = 0
        val expected = listOf(TerminalFileEntry("recovered", isDirectory = true))
        val provider =
            provider(scheduler, {}) { _, _ ->
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

    private fun provider(
        scheduler: IntellijCompletionLoadScheduler,
        onSnapshotChanged: () -> Unit,
        scanner: IntellijDirectoryScanner,
    ): IntellijAsyncFileSystemProvider =
        IntellijAsyncFileSystemProvider(
            scheduler = scheduler,
            onSnapshotChanged = onSnapshotChanged,
            resolver = IntellijCompletionPathResolver(homeDirectory = null, windows = false),
            scanner = scanner,
            snapshotTtlNanos = Long.MAX_VALUE,
        )

    private fun request(
        workingDirectoryUri: String = Path.of(".").toAbsolutePath().normalize().toUri().toString(),
        directoryPrefix: String = "",
        entryNamePrefix: String = "",
    ): TerminalDirectoryListingRequest =
        TerminalDirectoryListingRequest(workingDirectoryUri, directoryPrefix, entryNamePrefix)

    /** Deterministic FIFO scheduler used to control snapshot publication in tests. */
    private class RecordingScheduler : IntellijCompletionLoadScheduler {
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
