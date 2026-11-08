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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegration
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneSuggestionFixtureTest {
    @Test
    fun `line wait resumes when a cursor report drains after the final prompt frame`() =
        runTest {
            val connector = MockConnector()
            val ioScheduler = TestCoroutineScheduler()
            TerminalSession
                .create(
                    TerminalBuffers.create(100, 24),
                    connector,
                    shellIntegration = OscShellIntegration,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(ioScheduler),
                    cleanupDispatcher = StandardTestDispatcher(testScheduler),
                ).use { session ->
                    session.start()
                    connector.feedFromHost(
                        (
                            "\u001b]133;A\u0007PS> " +
                                "\u001b]633;KetraTermCompletion;2;Ready;${"a".repeat(32)};PrefixReset\u0007" +
                                "\u001b]133;B\u0007\u001b[6n"
                        ).encodeToByteArray(),
                    )
                    runCurrent()
                    assertEquals("", session.activeShellCommandLine()?.commandText)
                    assertTrue(session.shellCompletionAvailable)
                    assertNull(session.captureCommandEdit(), "Cursor report is still queued")
                    val ready = async { StandaloneSuggestionFixture.awaitEditableLine(session, "", requireShellCompletion = true) }
                    runCurrent()
                    assertFalse(ready.isCompleted)
                    val rendered = session.renderGeneration.value

                    ioScheduler.runCurrent()
                    runCurrent()
                    assertEquals("\u001b[1;5R", connector.writtenBytes.toString(Charsets.UTF_8))
                    assertNotNull(session.captureCommandEdit())
                    assertEquals(rendered, session.renderGeneration.value, "Write retirement must not manufacture a render")
                    assertTrue(ready.isCompleted, "Writer retirement must wake capture waiters without more shell output")
                    ready.await()
                }
        }

    @Test
    fun `line wait does not finish while matching output is still being processed`() =
        runTest {
            val buffer = TerminalBuffers.create(width = 100, height = 24)
            val connector = MockConnector()
            var duringOutput: () -> Unit = {}
            val integration =
                TerminalShellIntegrationFactory { context ->
                    val delegate = OscShellIntegration.create(context)
                    object : TerminalShellIntegration by delegate {
                        override fun outputProcessed() {
                            delegate.outputProcessed()
                            duringOutput()
                        }
                    }
                }
            TerminalSession
                .create(
                    buffer,
                    buffer as TerminalRenderFrameReader,
                    connector,
                    shellIntegration = integration,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).use { session ->
                    session.start(columns = 100, rows = 24)
                    connector.feedFromHost("\u001b]133;A\u0007PS> \u001b]133;B\u0007git sta".encodeToByteArray())
                    session.requestRender(0)
                    runCurrent()

                    var ready: Deferred<Unit>? = null
                    duringOutput = {
                        assertEquals("git st", session.activeShellCommandLine()?.commandText)
                        assertNull(session.captureCommandEdit())
                        ready =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                StandaloneSuggestionFixture.awaitEditableLine(session, "git st")
                            }
                        assertFalse(assertNotNull(ready).isCompleted, "Visible text can precede edit admission")
                    }
                    connector.feedFromHost("\b\u001b[K".encodeToByteArray())
                    duringOutput = {}
                    session.requestRender(0)
                    runCurrent()
                    assertTrue(assertNotNull(ready).isCompleted)
                    assertNotNull(ready).await()
                }
        }

    @Test
    fun `line wait does not treat a partial PowerShell deletion redraw as ready for suggestions`() =
        runTest {
            val buffer = TerminalBuffers.create(width = 100, height = 24)
            val connector = MockConnector()
            TerminalSession
                .create(
                    buffer,
                    buffer as TerminalRenderFrameReader,
                    connector,
                    shellIntegration = OscShellIntegration,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).use { session ->
                    session.start(columns = 100, rows = 24)
                    connector.feedFromHost(
                        (
                            "\u001b]133;A\u0007PS> " +
                                "\u001b]633;KetraTermCompletion;2;Ready;${"a".repeat(32)}\u0007" +
                                "\u001b]133;B\u0007git sta"
                        ).encodeToByteArray(),
                    )
                    session.requestRender(0)
                    runCurrent()
                    val ready = async { StandaloneSuggestionFixture.awaitEditableLine(session, "git st") }
                    runCurrent()
                    assertFalse(ready.isCompleted)

                    // The cursor moves before the shell erases the deleted character.
                    connector.feedFromHost("\b".encodeToByteArray())
                    session.requestRender(0)
                    runCurrent()
                    val partial = assertNotNull(session.activeShellCommandLine())
                    assertEquals("git st", partial.commandText)
                    assertTrue(partial.requiresShellConfirmation)
                    assertFalse(ready.isCompleted, "A provisional prefix is not a completed edit")

                    connector.feedFromHost("\u001b[K".encodeToByteArray())
                    session.requestRender(0)
                    runCurrent()
                    assertTrue(ready.isCompleted)
                    ready.await()
                    assertFalse(assertNotNull(session.captureCommandEdit()).commandLine.requiresShellConfirmation)
                }
        }
}
