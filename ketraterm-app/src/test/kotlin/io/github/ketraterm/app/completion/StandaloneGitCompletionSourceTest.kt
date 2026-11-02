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

import io.github.ketraterm.completion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneGitCompletionSourceTest {
    private val directory = Path.of("git-completion-fixture").toAbsolutePath()

    @Test
    fun `remote arguments query configuration and reference arguments select the appropriate branch group`() =
        runTest {
            val source =
                StandaloneGitCompletionLoader(StandardTestDispatcher(testScheduler)) { builder ->
                    val output =
                        if (builder.command().last() == "remote") {
                            assertEquals(listOf("git", "--no-optional-locks", "--no-pager", "remote"), builder.command())
                            "origin\nupstream\nbad\tname\n"
                        } else {
                            "*\u0000refs/heads/main\u0000\n" +
                                " \u0000refs/heads/local-only\u0000\n" +
                                " \u0000refs/remotes/origin/feature/one\u0000\n" +
                                " \u0000refs/remotes/upstream/other\u0000\n" +
                                " \u0000refs/remotes/origin/HEAD\u0000refs/remotes/origin/main\n"
                        }
                    TestGitProcess(output.toByteArray())
                }
            for (command in listOf("push", "pull", "fetch")) {
                assertEquals(setOf("origin", "upstream"), complete(source, "git $command ").map { it.replacementText }.toSet())
            }
            assertEquals(setOf("main", "local-only"), complete(source, "git push origin ").map { it.replacementText }.toSet())
            for (command in listOf("fetch", "pull")) {
                assertEquals(listOf("feature/one"), complete(source, "git $command origin fe").map { it.replacementText })
                assertEquals(listOf("other"), complete(source, "git $command upstream ").map { it.replacementText })
                assertTrue(complete(source, "git $command unknown ").isEmpty())
            }
        }

    @Test
    fun `only local Git reference arguments start a process`() =
        runTest {
            val source = StandaloneGitCompletionLoader(StandardTestDispatcher(testScheduler)) { error("Unexpected Git process") }
            for (line in listOf(
                "git s",
                "git switch -",
                "git switch -c new",
                "git add file",
                "cd ",
                "unknown ",
                "git checkout -- fe",
                "git checkout main -- fe",
                "git reset -- fe",
            )) {
                assertTrue(complete(source, line).isEmpty(), line)
            }
            for (uri in listOf(null, "", "ssh://server/repo", "file://remote.example/repo", "file://wsl.localhost/Ubuntu/repo")) {
                val request = request("git switch fe").copy(workingDirectoryUri = uri)
                assertTrue(
                    TerminalCompletionSources
                        .git(
                            "git",
                            source::load,
                        ).complete(request, TerminalCompletionContext.resolve(request), 20)
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `switch uses local branches and checkout adds remote branches and tags`() =
        runTest {
            val output =
                "*\u0000refs/heads/main\u0000\n" +
                    " \u0000refs/heads/feature/local\u0000\n" +
                    " \u0000refs/remotes/origin/feature\u0000\n" +
                    " \u0000refs/remotes/origin/HEAD\u0000refs/remotes/origin/main\n" +
                    " \u0000refs/tags/release\u0000\n"
            val processes = ArrayList<TestGitProcess>()
            val source =
                StandaloneGitCompletionLoader(StandardTestDispatcher(testScheduler)) { builder ->
                    assertEquals(directory.toFile(), builder.directory())
                    assertEquals("git", builder.command().first())
                    assertTrue("for-each-ref" in builder.command())
                    assertFalse(builder.command().any { it.contains("git switch") })
                    assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
                    TestGitProcess(output.toByteArray()).also(processes::add)
                }
            assertEquals(listOf("feature/local"), complete(source, "git switch ").map { it.displayText })
            assertEquals(
                setOf("feature/local", "origin/feature", "release"),
                complete(source, "git checkout ").map { it.displayText }.toSet(),
            )
            assertTrue(processes.all { it.destroyed.get() })
        }

    @Test
    fun `values use shared matching limits and shell escaping`() =
        runTest {
            val source =
                StandaloneGitCompletionLoader(StandardTestDispatcher(testScheduler)) {
                    TestGitProcess(" \u0000refs/heads/feature/o'hare\u0000\n \u0000refs/heads/other\u0000\n".toByteArray())
                }
            val line = "git switch feature/o"
            val candidate = complete(source, line, limit = 1).single()
            assertEquals("feature/o'hare", candidate.displayText)
            assertEquals(11, candidate.replacementStartOffset)
            assertEquals(line.length, candidate.replacementEndOffset)
            val edited = "git switch " + candidate.replacementText
            assertEquals("feature/o'hare", TerminalCompletionContext.resolve(request(edited)).activePrefix)
            assertEquals(StandaloneGitCompletionLoader.SOURCE_ID, candidate.source)
        }

    @Test
    fun `failed oversized and malformed queries cannot supply partial or invalid references`() =
        runTest {
            for (process in listOf(
                TestGitProcess(" \u0000refs/heads/feature\u0000\n".toByteArray(), exitCode = 128),
                TestGitProcess(ByteArray(StandaloneGitCompletionLoader.MAX_OUTPUT_BYTES + 1) { 'a'.code.toByte() }),
                TestGitProcess("bad record\n \u0000refs/heads/\u0000\n \u0000refs/heads/bad\tname\u0000\n".toByteArray()),
            )) {
                val source = StandaloneGitCompletionLoader(StandardTestDispatcher(testScheduler)) { process }
                assertTrue(complete(source, "git switch ").isEmpty())
                assertTrue(process.destroyed.get())
            }
        }

    @Test
    fun `cancellation terminates a process blocked writing its reply`() =
        runBlocking {
            withTimeout(5.seconds) {
                val process = TestGitProcess(blockRead = true)
                val source = StandaloneGitCompletionLoader { process }
                val query = launch { complete(source, "git switch ") }
                try {
                    process.readStarted.await()
                    query.cancelAndJoin()
                    assertTrue(process.destroyed.get())
                } finally {
                    process.destroy()
                    query.cancelAndJoin()
                }
            }
        }

    @Test
    fun `deadline terminates the process and returns no references`() =
        runBlocking {
            withTimeout(5.seconds) {
                val scheduler = TestCoroutineScheduler()
                val process = TestGitProcess(blockRead = true)
                val source = StandaloneGitCompletionLoader { process }
                val query = async(UnconfinedTestDispatcher(scheduler)) { complete(source, "git switch ") }
                try {
                    process.readStarted.await()
                    scheduler.advanceTimeBy(StandaloneGitCompletionLoader.QUERY_TIMEOUT_MILLIS)
                    scheduler.runCurrent()
                    assertTrue(query.await().isEmpty())
                    assertTrue(process.destroyed.get())
                } finally {
                    process.destroy()
                    query.cancelAndJoin()
                }
            }
        }

    private suspend fun complete(
        source: StandaloneGitCompletionLoader,
        line: String,
        limit: Int = 20,
    ): List<TerminalCompletionCandidate> {
        val request = request(line)
        return TerminalCompletionSources
            .git(StandaloneGitCompletionLoader.SOURCE_ID, source::load)
            .complete(request, TerminalCompletionContext.resolve(request), limit)
    }

    private fun request(line: String) =
        TerminalCompletionRequest(
            line,
            line.length,
            workingDirectoryUri = directory.toUri().toString(),
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )

    private class TestGitProcess(
        output: ByteArray = byteArrayOf(),
        private val exitCode: Int = 0,
        blockRead: Boolean = false,
    ) : Process() {
        val destroyed = AtomicBoolean()
        val readStarted = CompletableDeferred<Unit>()
        private val stopped = CountDownLatch(1)
        private val input =
            if (!blockRead) {
                ByteArrayInputStream(output)
            } else {
                object : InputStream() {
                    override fun read(): Int {
                        readStarted.complete(Unit)
                        while (!destroyed.get()) {
                            try {
                                stopped.await()
                            } catch (_: InterruptedException) {
                                // Simulate a pipe whose read completes only when the process exits.
                            }
                        }
                        return -1
                    }
                }
            }

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun waitFor(): Int = exitCode

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyed.set(true)
            stopped.countDown()
        }
    }
}
