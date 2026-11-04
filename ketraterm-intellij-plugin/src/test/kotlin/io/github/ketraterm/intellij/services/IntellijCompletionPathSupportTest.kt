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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class IntellijCompletionPathSupportTest {
    private val projectDirectory = Path.of("completion-project").toAbsolutePath().normalize()

    @Test
    fun `filename matches do not highlight unrelated ancestor letters`() {
        val path = "gradle/build/generated/settings.gradle.kts"
        val ranges = IntellijCompletionPathMatcher("sgk").ranges(path)
        assertEquals(
            "sgk",
            buildString {
                for (index in 0 until ranges.rangeCount) {
                    append(path.substring(ranges.startOffset(index), ranges.endOffset(index)))
                }
            },
        )
        org.junit.Assert.assertTrue(ranges.startOffset(0) >= path.lastIndexOf('/') + 1)
    }

    @Test
    fun `directory qualifiers and non ASCII names retain original offsets`() {
        val path = "src/main/\uD83D\uDE80Settings.gradle.kts"
        val ranges = IntellijCompletionPathMatcher("src/main/sgk").ranges(path)
        assertEquals(
            "srcmainsgk",
            buildString {
                for (index in 0 until ranges.rangeCount) {
                    append(path.substring(ranges.startOffset(index), ranges.endOffset(index)))
                }
            }.replace("/", "").lowercase(),
        )
        assertEquals(0, IntellijCompletionPathMatcher("").ranges(path).rangeCount)
    }

    @Test
    fun `only absent directory metadata admits the project fallback`() {
        var fallbacks = 0

        fun fallback(): Path {
            fallbacks++
            return projectDirectory
        }

        assertEquals(projectDirectory, resolveIntellijCompletionWorkingDirectory(null, ::fallback))
        for (uri in listOf("", "not a URI", "ssh://remote/repo", "file://remote.example/repo", "file://wsl.localhost/Ubuntu/repo")) {
            assertNull(uri, resolveIntellijCompletionWorkingDirectory(uri, ::fallback))
        }
        assertEquals(projectDirectory, resolveIntellijCompletionWorkingDirectory(projectDirectory.toUri().toString(), ::fallback))
        assertEquals(1, fallbacks)
    }
}
