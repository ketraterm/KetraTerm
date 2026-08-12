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

import io.github.ketraterm.completion.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalDirectoryCompletionTest {
    @Test
    fun `localhost shell URI completes current and parent directories asynchronously`() =
        runTest {
            val root = Files.createTempDirectory("ketraterm-path-completion")
            val workingDirectory = Files.createDirectory(root.resolve("working"))
            val child = Files.createDirectory(workingDirectory.resolve("child"))
            val sibling = Files.createDirectory(root.resolve("sibling"))
            val scanner = TerminalBoundedDirectoryScanner(ioDispatcher = StandardTestDispatcher(testScheduler))
            var publications = 0
            val service = TerminalCompletionSnapshotService(parentScope = this, maxConcurrentLoads = 1)
            val provider = service.createDirectoryProvider({ publications++ }, scanner = scanner)
            val source = TerminalCompletionSources.path(provider)
            val workingDirectoryUri = URI("file", "localhost", workingDirectory.toUri().path, null).toASCIIString()

            try {
                assertTrue(source.complete(completionRequest("cd ", workingDirectoryUri)).isEmpty())
                advanceUntilIdle()
                assertEquals(
                    listOf("child/"),
                    source.complete(completionRequest("cd ", workingDirectoryUri)).map { it.replacementText },
                )

                assertTrue(source.complete(completionRequest("cd ../", workingDirectoryUri)).isEmpty())
                advanceUntilIdle()
                assertEquals(
                    listOf("../sibling/", "../working/"),
                    source.complete(completionRequest("cd ../", workingDirectoryUri)).map { it.replacementText },
                )
                assertEquals(2, publications)
            } finally {
                provider.close()
                service.close()
                Files.deleteIfExists(child)
                Files.deleteIfExists(workingDirectory)
                Files.deleteIfExists(sibling)
                Files.deleteIfExists(root)
            }
        }

    @Test
    fun `remote authority is rejected without local reinterpretation`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false)

        assertNull(resolver.resolve(request(workingDirectoryUri = "file://remote.example/work")))
    }

    @Test
    fun `malformed lexical path is rejected without escaping the resolver contract`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false)

        assertNull(resolver.resolve(request(directoryPrefix = "\u0000/")))
    }

    @Test
    fun `latest directory key cancels an obsolete scan`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            var publications = 0
            val service = TerminalCompletionSnapshotService(parentScope = this, maxConcurrentLoads = 1)
            val provider =
                service.createDirectoryProvider(
                    onSnapshotChanged = { publications++ },
                    resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false),
                    scanner =
                        TerminalDirectoryScanner { _, prefix ->
                            if (prefix == "a") {
                                firstStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    firstCancelled.complete(Unit)
                                }
                            }
                            listOf(TerminalFileEntry(prefix, isDirectory = true))
                        },
                )

            provider.listDirectory(request(entryNamePrefix = "a"))
            runCurrent()
            firstStarted.await()
            provider.listDirectory(request(entryNamePrefix = "b"))
            advanceUntilIdle()

            assertTrue(firstCancelled.isCompleted)
            assertEquals(1, publications)
            assertEquals("b", provider.listDirectory(request(entryNamePrefix = "b")).single().name)
            provider.close()
            service.close()
        }

    @Test
    fun `throwing scan is reported and can be retried`() =
        runTest {
            var attempts = 0
            val failures = ArrayList<Throwable>()
            val expected = listOf(TerminalFileEntry("recovered", isDirectory = true))
            val service =
                TerminalCompletionSnapshotService(
                    parentScope = this,
                    maxConcurrentLoads = 1,
                    onBackgroundFailure = failures::add,
                )
            val provider =
                service.createDirectoryProvider(
                    onSnapshotChanged = {},
                    resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false),
                    scanner =
                        TerminalDirectoryScanner { _, _ ->
                            if (++attempts == 1) error("scan failed")
                            expected
                        },
                )

            provider.listDirectory(request())
            advanceUntilIdle()
            assertEquals("scan failed", failures.single().message)

            assertTrue(provider.listDirectory(request()).isEmpty())
            advanceUntilIdle()
            assertEquals(expected, provider.listDirectory(request()))
            provider.close()
            service.close()
        }

    @Test
    fun `closing during a scan prevents result publication`() =
        runTest {
            var publications = 0
            val service = TerminalCompletionSnapshotService(parentScope = this, maxConcurrentLoads = 1)
            lateinit var provider: TerminalAsyncFileSystemProvider
            provider =
                service.createDirectoryProvider(
                    onSnapshotChanged = { publications++ },
                    resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false),
                    scanner =
                        TerminalDirectoryScanner { _, _ ->
                            provider.close()
                            listOf(TerminalFileEntry("late", isDirectory = false))
                        },
                )

            provider.listDirectory(request())
            advanceUntilIdle()

            assertEquals(0, publications)
            assertTrue(provider.listDirectory(request()).isEmpty())
            service.close()
        }

    @Test
    fun `directory scanner retains only the best bounded matches`() =
        runTest {
            val directory = Files.createTempDirectory("ketraterm-directory-scan")
            val names = listOf("zeta", "alpha", "gamma", "beta")
            try {
                names.forEach { name -> Files.createFile(directory.resolve(name)) }
                val scanner =
                    TerminalBoundedDirectoryScanner(
                        maxVisitedEntries = names.size,
                        maxMatchingEntries = 2,
                        scanBudgetNanos = TimeUnit.SECONDS.toNanos(1),
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )

                assertEquals(listOf("alpha", "beta"), scanner.scan(directory, "").map(TerminalFileEntry::name))
            } finally {
                names.forEach { name -> Files.deleteIfExists(directory.resolve(name)) }
                Files.deleteIfExists(directory)
            }
        }

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
    ): TerminalDirectoryListingRequest = TerminalDirectoryListingRequest(workingDirectoryUri, directoryPrefix, entryNamePrefix)

    private fun completionRequest(
        commandLine: String,
        workingDirectoryUri: String,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = workingDirectoryUri,
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
}
