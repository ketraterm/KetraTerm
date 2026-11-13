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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TerminalLocalFileSystemProviderTest {
    @Test
    fun `local URI resolution preserves local paths and rejects non-path components`() {
        val directory = Path.of("local directory").toAbsolutePath().normalize()
        val uri = directory.toUri().toString()
        assertEquals(directory, TerminalLocalFileUriResolver.resolve(uri))
        val localhost = java.net.URI("file", "localhost", java.net.URI(uri).path, null).toString()
        assertEquals(directory, TerminalLocalFileUriResolver.resolve(localhost))
        for (invalid in listOf("$uri?query", "$uri#fragment", "file://remote.example/share/path", "ssh://host/path", "file:relative")) {
            assertNull(TerminalLocalFileUriResolver.resolve(invalid), invalid)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `network roots in Windows directory metadata never reach the scanner`() =
        runTest {
            var scans = 0
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner { _, _ ->
                            scans++
                            emptyList()
                        },
                )
            val uris =
                listOf(
                    "file:////server/share/path",
                    "file://localhost//server/share/path",
                    "file:///%2Fserver/share/path",
                    "file://localhost/%2fserver/share/path",
                    "file:///%5C%5Cserver/share/path",
                    "file:////%3F/UNC/server/share/path",
                )
            for (uri in uris) {
                assertNull(TerminalLocalFileUriResolver.resolve(uri), uri)
                assertEquals(emptyList(), provider.listDirectory(TerminalDirectoryListingRequest(uri, "", "")), uri)
            }
            assertEquals(0, scans)
        }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `explicit UNC prefixes remain available with local Windows metadata`() =
        runTest {
            val scans = mutableListOf<Path>()
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner {
                                path,
                                _,
                            ->
                            scans.add(path)
                            listOf(TerminalFileEntry("child", false))
                        },
                )
            val localUri =
                Path
                    .of("local-directory")
                    .toAbsolutePath()
                    .toUri()
                    .toString()
            val entries = provider.listDirectory(TerminalDirectoryListingRequest(localUri, "//server/share/", ""))
            assertEquals(listOf(TerminalFileEntry("child", false)), entries)
            assertEquals(listOf(Path.of("//server/share/")), scans)
        }

    @Test
    fun `resolves and scans each request directly`() =
        runTest {
            val directory = createTempDirectory("completion-directory")
            val scans = mutableListOf<Pair<Path, String>>()
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner { scannedDirectory, prefix ->
                            scans += scannedDirectory to prefix
                            listOf(TerminalFileEntry("present", isDirectory = false))
                        },
                )

            val request = TerminalDirectoryListingRequest(directory.toUri().toString(), "", "pre")
            val first = provider.listDirectory(request)
            val second = provider.listDirectory(request)

            assertEquals(listOf("present"), first.map(TerminalFileEntry::name))
            assertEquals(first, second)
            assertEquals(listOf(directory to "pre", directory to "pre"), scans)
        }

    @Test
    fun `literal backslashes in entry prefixes reach the scanner unchanged`() =
        runTest {
            val directory = Path.of("completion-directory").toAbsolutePath().normalize()
            val entry = TerminalFileEntry("foo\\bar", isDirectory = false)
            val scans = mutableListOf<Pair<Path, String>>()
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner { scannedDirectory, prefix ->
                            scans += scannedDirectory to prefix
                            listOf(entry)
                        },
                )

            val entries = provider.listDirectory(TerminalDirectoryListingRequest(directory.toUri().toString(), "nested/", "foo\\b"))

            assertEquals(listOf(entry), entries)
            assertEquals(listOf(directory.resolve("nested") to "foo\\b"), scans)
        }

    @Test
    fun `canonical directory prefixes retain native filesystem meaning`() {
        val directory = Path.of("completion-directory").toAbsolutePath().normalize()
        val windows = directory.fileSystem.separator == "\\"
        val resolver = TerminalCompletionPathResolver()
        val cases =
            listOf(
                "foo\\bar/" to if (windows) null else directory.resolve("foo\\bar"),
                "C:/workspace/" to if (windows) Path.of("C:/workspace") else null,
                "//server/share/" to if (windows) Path.of("//server/share") else null,
            )

        for ((prefix, expected) in cases) {
            val request = TerminalDirectoryListingRequest(directory.toUri().toString(), prefix, "")
            assertEquals(expected, resolver.resolve(request), prefix)
        }
    }

    @Test
    fun `unsupported working-directory URI is a normal empty result`() =
        runTest {
            var scans = 0
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner { _, _ ->
                            scans++
                            emptyList()
                        },
                )

            val entries = provider.listDirectory(TerminalDirectoryListingRequest("ssh://host/repo", "", "pre"))

            assertEquals(emptyList(), entries)
            assertEquals(0, scans)
        }

    @Test
    fun `scanner failure propagates unchanged`() =
        runTest {
            val directory = createTempDirectory("completion-failure")
            val failure = IOException("directory access failed")
            val provider = TerminalLocalFileSystemProvider(scanner = TerminalDirectoryScanner { _, _ -> throw failure })

            val thrown =
                assertFailsWith<IOException> {
                    provider.listDirectory(TerminalDirectoryListingRequest(directory.toUri().toString(), "", "pre"))
                }

            assertSame(failure, thrown)
        }
}
