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

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.completion.api.TerminalFileEntry
import io.github.ketraterm.completion.host.TerminalDirectoryScanner
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** IntelliJ fixture tests for project-aware VFS directory snapshots. */
class IntellijProjectDirectoryScannerTest : BasePlatformTestCase() {
    /** Verifies filtering and ordering from project content without fallback I/O. */
    fun testProjectContentUsesFilteredVfsSnapshot() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("completion")
        myFixture.tempDirFixture.createFile("completion/Alpha.kt")
        myFixture.tempDirFixture.createFile("completion/beta.kt")
        myFixture.tempDirFixture.findOrCreateDir("completion/App")
        var fallbackCalls = 0
        val scanner =
            IntellijProjectDirectoryScanner(
                project = project,
                fallback =
                    TerminalDirectoryScanner { _, _ ->
                        fallbackCalls++
                        listOf(TerminalFileEntry("fallback", false))
                    },
                virtualFileResolver = { directory },
            )

        val entries = runBlocking { scanner.scan(Path.of("project-content"), "A") }

        assertEquals(0, fallbackCalls)
        assertEquals(
            listOf(
                TerminalFileEntry("Alpha.kt", false),
                TerminalFileEntry("App", true),
            ),
            entries,
        )
    }

    /** Verifies one VFS request cannot inspect or retain beyond its visit cap. */
    fun testProjectContentVisitBound() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("bounded-completion")
        repeat(10) { index -> myFixture.tempDirFixture.createFile("bounded-completion/file-$index.kt") }
        val scanner =
            IntellijProjectDirectoryScanner(
                project = project,
                virtualFileResolver = { directory },
                maxVisitedEntries = 3,
                maxMatchingEntries = 256,
            )

        val entries = runBlocking { scanner.scan(Path.of("project-content"), "") }

        assertTrue(entries.size <= 3)
    }
}
