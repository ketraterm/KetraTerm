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
package io.github.ketraterm.app.ui

import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalWorkingDirectoryResolverTest {
    @Test
    fun `resolves existing local file URI with escaped path`(
        @TempDir tempDirectory: Path,
    ) {
        val directory =
            java.nio.file.Files
                .createDirectory(tempDirectory.resolve("space % directory"))

        assertEquals(directory, LocalWorkingDirectoryResolver.resolve(directory.toUri().toASCIIString()))
        assertEquals(
            directory,
            LocalWorkingDirectoryResolver.resolve(
                URI("file", "localhost", directory.toUri().path, null).toASCIIString(),
            ),
        )
    }

    @Test
    fun `rejects remote malformed missing and non-directory locations`(
        @TempDir tempDirectory: Path,
    ) {
        val file =
            java.nio.file.Files
                .createFile(tempDirectory.resolve("file.txt"))

        assertNull(LocalWorkingDirectoryResolver.resolve(null))
        assertNull(LocalWorkingDirectoryResolver.resolve("not a URI"))
        assertNull(LocalWorkingDirectoryResolver.resolve("https://localhost/tmp"))
        assertNull(LocalWorkingDirectoryResolver.resolve("file://remote.invalid/tmp"))
        assertNull(LocalWorkingDirectoryResolver.resolve(file.toUri().toASCIIString()))
        assertNull(LocalWorkingDirectoryResolver.resolve(tempDirectory.resolve("missing").toUri().toASCIIString()))
    }
}
