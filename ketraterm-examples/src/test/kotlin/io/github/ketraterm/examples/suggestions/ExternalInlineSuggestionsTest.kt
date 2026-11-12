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
@file:OptIn(
    ExperimentalShellSuggestionsApi::class,
    ExperimentalCoroutinesApi::class,
)

package io.github.ketraterm.examples.suggestions

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.*
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.workspace.TerminalProfileKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class ExternalInlineSuggestionsTest {
    @Test
    fun `context captures command cursor directory and five completed outputs newest first`() =
        runTest {
            SessionFixture(StandardTestDispatcher(testScheduler)).use { f ->
                repeat(7) { f.complete("echo $it", "result $it", exitCode = it) }
                val request = SwingShellSuggestionRequest("echo ext", 8)
                val context = assertNotNull(readSuggestionContext(f.session, request, TerminalProfileKind.POWERSHELL))
                assertEquals(TerminalProfileKind.POWERSHELL, context.shellKind)
                assertSame(request, context.commandLine)
                assertEquals("file:///example", context.workingDirectoryUri)
                assertEquals((6 downTo 2).map { "echo $it" }, context.recentCommands.map { it.metadata.commandText })
                assertEquals((6 downTo 2).map { "result $it" }, context.recentCommands.map { it.output?.text })
                assertEquals((6 downTo 2).toList(), context.recentCommands.map { it.metadata.exitCode })
                assertTrue(context.recentCommands.all { it.metadata.finishedAtEpochMillis != null })
                f.complete("echo later", "later")
                assertEquals(
                    "result 6",
                    context.recentCommands
                        .first()
                        .output
                        ?.text,
                )
                assertTrue(f.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `bounded output retains truncation and missing history information`() =
        runTest {
            SessionFixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.complete("echo large", "x".repeat(3_000))
                val output = assertNotNull(readSuggestionContext(f.session, request)?.recentCommands?.first()?.output)
                assertTrue(output.text.length <= 2_048)
                assertTrue(output.truncated)
                assertFalse(output.historyIncomplete)
            }
            SessionFixture(StandardTestDispatcher(testScheduler), history = 5).use { f ->
                f.complete("echo evicted", (1..80).joinToString("\r\n") { "row $it" })
                val output = assertNotNull(readSuggestionContext(f.session, request)?.recentCommands?.first()?.output)
                assertTrue(output.historyIncomplete)
            }
            SessionFixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.complete("echo rows", (1..80).joinToString("\r\n") { "row $it" })
                val output = assertNotNull(readSuggestionContext(f.session, request)?.recentCommands?.first()?.output)
                assertTrue(output.text.lines().size <= 32)
                assertTrue(output.text.endsWith("row 80"))
                assertTrue(output.truncated)
            }
        }

    @Test
    fun `a producer without output extraction preserves unavailable output as null`() =
        runTest {
            val history = TerminalShellIntegrationState()
            history.recordCurrentWorkingDirectory("file:///example")
            history.recordCommandStart(1, true, "echo old", "file:///example")
            history.recordCommandFinished(2, 1)
            history.recordCommandStart(3, true, "still running", "file:///example")
            val integration = TerminalShellIntegrationFactory.host(history, TerminalShellCommandLineState())
            SessionFixture(StandardTestDispatcher(testScheduler), integration = integration).use { f ->
                val context = assertNotNull(readSuggestionContext(f.session, request))
                assertEquals(1, context.recentCommands.size)
                assertNull(context.recentCommands.single().output)
                assertEquals(
                    1,
                    context.recentCommands
                        .single()
                        .metadata.exitCode,
                )
            }
        }

    @Test
    fun `metadata changing during output extraction discards the mixed snapshot`() =
        runTest {
            val history = TerminalShellIntegrationState()
            history.recordCurrentWorkingDirectory("file:///example")
            history.recordCommandStart(1, true, "echo old", "file:///example")
            history.recordCommandFinished(2, 0)
            val host = TerminalShellIntegrationFactory.host(history, TerminalShellCommandLineState())
            val integration =
                TerminalShellIntegrationFactory { context ->
                    object : TerminalShellIntegration by host.create(context) {
                        override fun commandOutputText(
                            recordId: Int,
                            maxChars: Int,
                            maxRows: Int,
                        ): TerminalCommandOutputText? {
                            history.recordCurrentWorkingDirectory("file:///changed")
                            return null
                        }
                    }
                }
            SessionFixture(StandardTestDispatcher(testScheduler), integration = integration).use { f ->
                assertNull(readSuggestionContext(f.session, request))
            }
        }

    @Test
    fun `the provider is cold and emits a whole-command replacement after the simulated delay`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            SessionFixture(dispatcher).use { f ->
                var calls = 0
                val provider =
                    ExternalInlineSuggestions(f.session, { context ->
                        calls++
                        simulateCompletion(context)
                    }, dispatcher)
                val source = provider.open(request)
                assertEquals(0, calls)
                val results = mutableListOf<String>()
                val work = launch { source.suggestions.collect { results += it.single().replacementText } }
                runCurrent()
                assertEquals(1, calls)
                advanceTimeBy(749.milliseconds)
                runCurrent()
                assertTrue(results.isEmpty())
                advanceTimeBy(1.milliseconds)
                runCurrent()
                assertEquals(listOf("echo external inline suggestion"), results)
                work.join()
                val candidate =
                    source.suggestions
                        .toList()
                        .single()
                        .single()
                assertEquals(0, candidate.replacementStartOffset)
                assertEquals(request.commandText.length, candidate.replacementEndOffset)
                assertEquals("external-example", candidate.source)
            }
        }

    @Test
    fun `cancelled collection cannot publish a late external reply`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            SessionFixture(dispatcher).use { f ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val provider =
                    ExternalInlineSuggestions(f.session, {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        "echo external late reply"
                    }, dispatcher)
                var publications = 0
                val work =
                    launch {
                        provider.open(request).suggestions.collect { publications++ }
                    }
                runCurrent()
                assertTrue(entered.isCompleted)
                work.cancel()
                release.complete(Unit)
                runCurrent()
                work.join()
                assertTrue(work.isCancelled)
                assertEquals(0, publications)
            }
        }

    @Test
    fun `null oversized and control-bearing responses are discarded but provider failures propagate`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            SessionFixture(dispatcher).use { f ->
                for (response in listOf(null, "x".repeat(4_097), "echo ok\nwhoami", "echo\tbad")) {
                    val provider = ExternalInlineSuggestions(f.session, { response }, dispatcher)
                    assertTrue(
                        provider
                            .open(request)
                            .suggestions
                            .toList()
                            .isEmpty(),
                    )
                }
                val failing = ExternalInlineSuggestions(f.session, { throw IOException("Example service unavailable") }, dispatcher)
                assertFailsWith<IOException> { failing.open(request).suggestions.toList() }
            }
        }

    @Test
    fun `simulation uses matching directory history and ignores empty or mid-command requests`() =
        runTest {
            SessionFixture(StandardTestDispatcher(testScheduler)).use { f ->
                f.complete("echo external remembered", "output")
                val context = assertNotNull(readSuggestionContext(f.session, request))
                assertEquals("echo external remembered", simulateCompletion(context))
                assertEquals("echo external inline suggestion", simulateCompletion(context.copy(workingDirectoryUri = "file:///elsewhere")))
                assertNull(simulateCompletion(context.copy(commandLine = SwingShellSuggestionRequest("", 0))))
                assertNull(simulateCompletion(context.copy(commandLine = request.copy(cursorOffset = 2))))
            }
        }

    private class SessionFixture(
        dispatcher: CoroutineDispatcher,
        history: Int = 500,
        integration: TerminalShellIntegrationFactory = OscShellIntegration,
    ) : AutoCloseable {
        val connector = MockConnector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(100, 8, maxHistory = history),
                connector,
                shellIntegration = integration,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )

        init {
            session.start(100, 8)
            connector.feedFromHost("\u001b]7;file:///example\u0007".toByteArray())
        }

        fun complete(
            command: String,
            output: String,
            exitCode: Int = 0,
        ) {
            connector.feedFromHost(
                (
                    "\r\u001b]133;A\u0007$ \u001b]133;B\u0007" + command + "\r\n\u001b]133;C\u0007" +
                        output + "\r\n\u001b]133;D;$exitCode\u0007\r\u001b]133;A\u0007$ \u001b]133;B\u0007"
                ).toByteArray(),
            )
        }

        override fun close() = session.close()
    }

    private companion object {
        val request = SwingShellSuggestionRequest("echo ext", 8)
    }
}
