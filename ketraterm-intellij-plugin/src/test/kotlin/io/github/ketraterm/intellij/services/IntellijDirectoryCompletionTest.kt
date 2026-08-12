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
import io.github.ketraterm.completion.host.TerminalCompletionPathResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** IntelliJ adapter contract tests for authority-preserving path resolution. */
class IntellijDirectoryCompletionTest {
    /** Verifies that a remote file authority is never reinterpreted as a local path. */
    @Test
    fun `remote authority is rejected without local reinterpretation`() {
        val resolver = TerminalCompletionPathResolver(homeDirectory = Path.of("home"), windows = false)

        assertNull(resolver.resolve(request(workingDirectoryUri = "file://remote.example/work")))
    }

    /** Verifies that tilde expansion uses only the explicitly supplied host home. */
    @Test
    fun `tilde expansion uses explicit host home`() {
        val home = Path.of("host-home").toAbsolutePath().normalize()
        val resolver = TerminalCompletionPathResolver(homeDirectory = home, windows = false)

        assertEquals(home.resolve("projects"), resolver.resolve(request(directoryPrefix = "~/projects/")))
    }

    private fun request(
        workingDirectoryUri: String = Path.of(".").toAbsolutePath().normalize().toUri().toString(),
        directoryPrefix: String = "",
        entryNamePrefix: String = "",
    ): TerminalDirectoryListingRequest =
        TerminalDirectoryListingRequest(workingDirectoryUri, directoryPrefix, entryNamePrefix)
}
