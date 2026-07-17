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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandaloneDirectoryCompletionServiceTest {
    @Test
    fun `remote OSC 7 authority is rejected instead of becoming a local path`() {
        val resolver = StandalonePathResolver(homeDirectory = Path.of("home"), windows = false)

        val resolved = resolver.resolve(request("file://remote.example/work", ""))

        assertNull(resolved)
    }

    @Test
    fun `localhost OSC 7 authority remains local`() {
        val resolver = StandalonePathResolver(homeDirectory = Path.of("home"), windows = false)
        val path = Files.createTempDirectory("ketraterm-localhost")
        try {
            val uri = "file://localhost${path.toUri().path}"

            assertEquals(path.toAbsolutePath().normalize(), resolver.resolve(request(uri, "")))
        } finally {
            path.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tilde directory resolves through explicit host home capability`() {
        val home = Path.of("host-home").toAbsolutePath().normalize()
        val resolver = StandalonePathResolver(homeDirectory = home, windows = false)

        val resolved = resolver.resolve(request("file:///workspace", "~/projects/"))

        assertEquals(home.resolve("projects").normalize(), resolved)
    }

    @Test
    fun `Windows drive and UNC syntax are rejected on non-Windows hosts`() {
        val resolver = StandalonePathResolver(homeDirectory = null, windows = false)

        assertNull(resolver.resolve(request("file:///workspace", "C:/Users/")))
        assertNull(resolver.resolve(request("file:///workspace", "//server/share/")))
    }

    @Test
    fun `provider returns immediately and publishes immutable ready snapshot`() {
        val executor = RecordingExecutor()
        var publications = 0
        val expected = listOf(TerminalFileEntry("src", isDirectory = true))
        val provider = provider(executor, onSnapshotChanged = { publications++ }) { _, _ -> expected }

        assertTrue(provider.listDirectory(request()).isEmpty())
        assertEquals(1, executor.size)

        executor.runNext()

        assertEquals(1, publications)
        assertEquals(expected, provider.listDirectory(request()))
    }

    @Test
    fun `completion from superseded request does not publish stale refresh`() {
        val executor = RecordingExecutor()
        var publications = 0
        val provider = provider(executor, onSnapshotChanged = { publications++ }) { _, prefix ->
            listOf(TerminalFileEntry(prefix, isDirectory = true))
        }

        provider.listDirectory(request(entryNamePrefix = "a"))
        provider.listDirectory(request(entryNamePrefix = "b"))

        executor.runNext()
        assertEquals(0, publications)

        executor.runNext()
        assertEquals(1, publications)
        assertEquals("b", provider.listDirectory(request(entryNamePrefix = "b")).single().name)
    }

    @Test
    fun `returning to an in-flight request updates its accepted generation`() {
        val executor = RecordingExecutor()
        var publications = 0
        val provider = provider(executor, onSnapshotChanged = { publications++ }) { _, prefix ->
            listOf(TerminalFileEntry(prefix, isDirectory = true))
        }

        provider.listDirectory(request(entryNamePrefix = "a"))
        provider.listDirectory(request(entryNamePrefix = "b"))
        provider.listDirectory(request(entryNamePrefix = "a"))

        executor.runNext()

        assertEquals(1, publications)
        assertEquals("a", provider.listDirectory(request(entryNamePrefix = "a")).single().name)
    }

    @Test
    fun `closed provider discards queued results and callbacks`() {
        val executor = RecordingExecutor()
        var publications = 0
        val provider = provider(executor, onSnapshotChanged = { publications++ }) { _, _ ->
            listOf(TerminalFileEntry("src", isDirectory = true))
        }
        provider.listDirectory(request())

        provider.close()
        executor.runNext()

        assertEquals(0, publications)
        assertTrue(provider.listDirectory(request()).isEmpty())
    }

    @Test
    fun `scanner caps matching entries and returns deterministic order`() {
        val directory = Files.createTempDirectory("ketraterm-bounded-scan")
        try {
            Files.createDirectory(directory.resolve("zeta"))
            Files.createDirectory(directory.resolve("Alpha"))
            Files.createDirectory(directory.resolve("beta"))
            val scanner =
                BoundedStandaloneDirectoryScanner(
                    maxVisitedEntries = 16,
                    maxMatchingEntries = 2,
                    scanBudgetNanos = Long.MAX_VALUE,
                    nanoTime = { 0L },
                )

            val entries = scanner.scan(directory, "")

            assertEquals(2, entries.size)
            assertEquals(
                entries.sortedWith(compareBy<TerminalFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }),
                entries,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun provider(
        executor: Executor,
        onSnapshotChanged: () -> Unit,
        scanner: StandaloneDirectoryScanner,
    ): StandaloneAsyncFileSystemProvider =
        StandaloneAsyncFileSystemProvider(
            executor = executor,
            onSnapshotChanged = onSnapshotChanged,
            resolver = StandalonePathResolver(homeDirectory = null, windows = false),
            scanner = scanner,
            snapshotTtlNanos = Long.MAX_VALUE,
        )

    private fun request(
        workingDirectoryUri: String = Path.of(".").toAbsolutePath().normalize().toUri().toString(),
        directoryPrefix: String = "",
        entryNamePrefix: String = "",
    ): TerminalDirectoryListingRequest =
        TerminalDirectoryListingRequest(
            workingDirectoryUri = workingDirectoryUri,
            directoryPrefix = directoryPrefix,
            entryNamePrefix = entryNamePrefix,
        )

    private class RecordingExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
