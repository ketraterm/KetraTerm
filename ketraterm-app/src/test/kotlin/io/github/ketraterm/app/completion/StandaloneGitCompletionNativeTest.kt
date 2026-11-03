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

import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("native")
class StandaloneGitCompletionNativeTest {
    @Test
    fun `registry follows cwd and Git overrides for live references and file arguments`(
        @TempDir directory: Path,
    ) = runBlocking {
        val installed =
            try {
                git(directory, "--version")
                true
            } catch (_: IOException) {
                false
            }
        assumeTrue(installed, "Git is not installed")
        val first = Files.createDirectory(directory.resolve("first repo"))
        val second = Files.createDirectory(directory.resolve("second"))
        for (repo in listOf(first, second)) {
            git(repo, "init", "--quiet", "--initial-branch=main")
            git(
                repo,
                "-c",
                "user.name=Completion Test",
                "-c",
                "user.email=completion@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.hooksPath=${directory.resolve("no-hooks")}",
                "commit",
                "--allow-empty",
                "--quiet",
                "-m",
                "fixture",
            )
        }
        git(first, "branch", "feature-first")
        git(first, "remote", "add", "origin", "https://example.invalid/origin.git")
        git(first, "remote", "add", "upstream", "https://example.invalid/upstream.git")
        git(first, "tag", "release-first")
        git(first, "update-ref", "refs/remotes/origin/feature-first", "HEAD")
        git(first, "update-ref", "refs/remotes/upstream/feature-other", "HEAD")
        git(second, "branch", "feature-second")
        git(second, "remote", "add", "secondary", "https://example.invalid/second.git")
        Files.writeString(first.resolve("file-first.txt"), "fixture")
        Files.writeString(first.resolve("feature-first.txt"), "fixture")
        Files.writeString(second.resolve("file-second.txt"), "fixture")
        var workingDirectory = directory.toUri().toString()
        val registry = StandaloneCompletionRegistry.create(directory.resolve("learning.json"), persistenceEnabled = false)
        try {
            val provider =
                registry.createProvider(
                    shellCapabilities = TerminalShellCapabilities.POSIX,
                    workingDirectoryUriProvider = { workingDirectory },
                )

            suspend fun suggestions(line: String) = provider.suggestions(SwingShellSuggestionRequest(line, line.length)).last()

            val line = "git -C 'first repo' -C '' switch feature-"
            val branch = suggestions(line).single()
            assertEquals("feature-first", branch.replacementText)
            assertEquals(StandaloneGitCompletionLoader.SOURCE_ID, branch.source)
            assertEquals(line.indexOf("feature-"), branch.replacementStartOffset)
            assertEquals(listOf("file-first.txt"), suggestions("git -C 'first repo' add file-").map { it.replacementText })
            assertEquals(listOf("release-first"), suggestions("git -C 'first repo' checkout release-").map { it.replacementText })
            assertTrue(suggestions("git -C 'first repo' switch release-").isEmpty())
            assertEquals(listOf("origin/feature-first"), suggestions("git -C 'first repo' merge origin/").map { it.replacementText })
            for (command in listOf("checkout", "checkout main", "reset", "reset HEAD")) {
                val paths = suggestions("git -C 'first repo' $command -- feature-")
                assertEquals(listOf("feature-first.txt"), paths.map { it.replacementText })
                assertEquals("path", paths.single().source)
            }
            for (command in listOf("push", "fetch", "pull")) {
                assertEquals(listOf("origin"), suggestions("git -C 'first repo' $command or").map { it.replacementText })
            }
            assertEquals(listOf("main"), suggestions("git -C 'first repo' push origin ma").map { it.replacementText })
            assertEquals(listOf("release-first"), suggestions("git -C 'first repo' push origin release-").map { it.replacementText })
            assertEquals(listOf("feature-first"), suggestions("git -C 'first repo' fetch origin fe").map { it.replacementText })
            assertEquals(listOf("feature-other"), suggestions("git -C 'first repo' pull upstream fe").map { it.replacementText })
            assertEquals(listOf("feature-first"), suggestions("git -C 'first repo' push --delete origin fe").map { it.replacementText })
            assertEquals(listOf("upstream"), suggestions("git -C 'first repo' fetch --multiple origin up").map { it.replacementText })
            assertTrue(suggestions("git -C 'first repo' fetch --all ").isEmpty())
            assertTrue(suggestions("git -C 'first repo' push --all origin ").isEmpty())
            assertEquals(listOf("main"), suggestions("git -C 'first repo' push --tags origin ma").map { it.replacementText })
            val refspec = "git -C 'first repo' push origin +main:fe"
            val refspecEdit = suggestions(refspec).single()
            assertEquals(
                "git -C 'first repo' push origin +main:feature-first",
                refspec.replaceRange(refspecEdit.replacementStartOffset, refspecEdit.replacementEndOffset, refspecEdit.replacementText),
            )

            workingDirectory = second.toUri().toString()
            assertEquals(listOf("secondary"), suggestions("git fetch sec").map { it.replacementText })
            assertEquals(listOf("feature-second"), suggestions("git switch feature-").map { it.replacementText })
            git(second, "branch", "feature-new")
            assertEquals(setOf("feature-new", "feature-second"), suggestions("git switch feature-").map { it.replacementText }.toSet())
            workingDirectory = "file://wsl.localhost/Ubuntu/repo"
            assertTrue(suggestions("git switch feature-").isEmpty())
        } finally {
            registry.closeWithoutFlush()
        }
    }

    private fun git(
        directory: Path,
        vararg arguments: String,
    ) {
        val builder = ProcessBuilder(listOf("git") + arguments).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        builder.environment()["GIT_CONFIG_GLOBAL"] = directory.resolve("absent-global-config").toString()
        val process = builder.start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Git fixture command did not finish")
            assertEquals(0, process.exitValue(), process.inputStream.readNBytes(16_384).toString(Charsets.UTF_8))
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
        }
    }
}
