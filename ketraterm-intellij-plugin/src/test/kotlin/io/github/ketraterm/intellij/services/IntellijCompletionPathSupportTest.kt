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

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class IntellijCompletionPathSupportTest {
    private val projectDirectory = Path.of("completion-project").toAbsolutePath().normalize()

    @Test
    fun `containing roots respect path components rather than sibling name prefixes`() {
        val sibling = projectDirectory.resolveSibling("completion-project-other")

        assertNull(findContainingCompletionRoot(sibling.resolve("src"), listOf(projectDirectory)) { it })
        assertEquals(projectDirectory, findContainingCompletionRoot(projectDirectory, listOf(projectDirectory)) { it })
    }

    @Test
    fun `containing roots select the deepest match and preserve the first equal match`() {
        val nested = projectDirectory.resolve("nested")
        val roots =
            listOf(
                "unavailable" to null,
                "outer" to projectDirectory,
                "nested-first" to nested,
                "nested-second" to nested,
            )

        val selected = findContainingCompletionRoot(nested.resolve("src/main"), roots) { it.second }

        assertEquals("nested-first", selected?.first)
    }

    @Test
    fun `containing roots normalize relative paths and parent segments`() {
        val root = Path.of("completion-project", "nested", "..")
        val directory = Path.of("completion-project", ".", "src", "..", "test")

        assertEquals(root, findContainingCompletionRoot(directory, listOf(root)) { it })
        assertNull(findContainingCompletionRoot(Path.of("completion-project", "..", "other"), listOf(root)) { it })
    }

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
        assertTrue(ranges.startOffset(0) >= path.lastIndexOf('/') + 1)
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
    fun `literal backslashes stay in the filename query and highlighted ranges`() {
        val path = "src/foo\\bar.kt"
        val matcher = IntellijCompletionPathMatcher("foo\\b")

        assertTrue(matcher.matchesName("foo\\bar.kt"))
        assertFalse(matcher.matchesName("bar.kt"))
        assertFalse(matcher.matchesName("foobar.kt"))
        assertFalse(matcher.matchesName("foo/bar.kt"))
        assertTrue(matcher.matchesPath(path))
        assertFalse(matcher.matchesPath("src/foobar.kt"))
        assertFalse(matcher.matchesPath("src/foo/bar.kt"))
        val ranges = matcher.ranges(path)
        assertEquals(
            "foo\\b",
            buildString {
                for (index in 0 until ranges.rangeCount) {
                    append(path.substring(ranges.startOffset(index), ranges.endOffset(index)))
                }
            },
        )
    }

    @Test
    fun `qualified literal backslashes reject wildcard broadened parent matches`() {
        val matcher = IntellijCompletionPathMatcher("foo\\bar/ma")

        assertTrue(matcher.matchesName("main.kt"))
        assertTrue(matcher.matchesPath("src/foo\\bar/main.kt"))
        assertFalse(matcher.matchesPath("src/foobar/main.kt"))
        assertFalse(matcher.matchesPath("src/foo/bar/main.kt"))
        assertFalse(matcher.matchesPath("src/foo-other-bar/main.kt"))
    }

    @Test
    fun `absolute literal queries match absolute paths instead of relative display paths`() {
        val matcher = IntellijCompletionPathMatcher("/tmp/project/foo\\b")

        assertTrue(matcher.matchesName("foo\\bar.kt"))
        assertTrue(matcher.matchesPath("/tmp/project/foo\\bar.kt"))
        assertFalse(matcher.matchesPath("foo\\bar.kt"))
        assertFalse(matcher.matchesPath("/tmp/project/foobar.kt"))
        assertFalse(matcher.matchesPath("/tmp/project/foo/bar.kt"))
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
