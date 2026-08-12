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
import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner
import io.github.ketraterm.completion.host.TerminalCompletionPathResolver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandaloneDirectoryCompletionServiceTest {
    @Test
    fun `remote OSC 7 authority is rejected instead of becoming a local path`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = Path.of("home"), windows = false)

        assertNull(resolver.resolve(request("file://remote.example/work", "")))
    }

    @Test
    fun `localhost OSC 7 authority remains local`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = Path.of("home"), windows = false)
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
        val resolver = TerminalCompletionPathResolver(homeDirectory = home, windows = false)

        assertEquals(home.resolve("projects").normalize(), resolver.resolve(request("file:///workspace", "~/projects/")))
    }

    @Test
    fun `Windows drive and UNC syntax are rejected on non-Windows hosts`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = null, windows = false)

        assertNull(resolver.resolve(request("file:///workspace", "C:/Users/")))
        assertNull(resolver.resolve(request("file:///workspace", "//server/share/")))
    }

    @Test
    fun `standalone service publishes a ready directory snapshot`() {
        val directory = Files.createTempDirectory("ketraterm-standalone-completion")
        val child = Files.createDirectory(directory.resolve("src"))
        val published = CountDownLatch(1)
        val service = StandaloneDirectoryCompletionService()
        val provider = service.createProvider(published::countDown)
        try {
            val request = request(directory.toUri().toString(), "")

            assertTrue(provider.listDirectory(request).isEmpty())
            assertTrue(published.await(5, TimeUnit.SECONDS), "directory snapshot did not publish")
            assertEquals(listOf("src"), provider.listDirectory(request).map(TerminalFileEntry::name))
        } finally {
            provider.close()
            service.close()
            Files.deleteIfExists(child)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `scanner caps matches and returns deterministic order`() {
        val directory = Files.createTempDirectory("ketraterm-bounded-scan")
        try {
            Files.createDirectory(directory.resolve("zeta"))
            Files.createDirectory(directory.resolve("Alpha"))
            Files.createDirectory(directory.resolve("beta"))
            val scanner =
                TerminalBoundedDirectoryScanner(
                    maxVisitedEntries = 16,
                    maxMatchingEntries = 2,
                    scanBudgetNanos = Long.MAX_VALUE,
                    nanoTime = { 0L },
                )

            val entries = runBlocking { scanner.scan(directory, "") }

            assertEquals(2, entries.size)
            assertEquals(
                entries.sortedWith(compareBy<TerminalFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }),
                entries,
            )
        } finally {
            directory.toFile().deleteRecursively()
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
    ): TerminalDirectoryListingRequest =
        TerminalDirectoryListingRequest(
            workingDirectoryUri = workingDirectoryUri,
            directoryPrefix = directoryPrefix,
            entryNamePrefix = entryNamePrefix,
        )
}
