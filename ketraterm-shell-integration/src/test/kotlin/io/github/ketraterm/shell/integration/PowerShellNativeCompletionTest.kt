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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCompletionKind
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PowerShellNativeCompletionTest {
    @Test
    fun `chunked native reply preserves escaped edits and pre-query acceptance`() =
        runTest {
            val fixture = Fixture(StandardTestDispatcher(testScheduler))
            fixture.use {
                val expected = assertNotNull(it.session.captureCommandEdit())
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                assertEquals("\u001b[24~e", it.connector.writtenBytes.toString(Charsets.UTF_8))
                val reply = reply("Get-Chi", value = "Get-ChildItem", label = "Get-ChildItem", externalCommandName = "git.exe")
                for (byte in reply.toByteArray()) it.connector.feedFromHost(byteArrayOf(byte))
                runCurrent()

                val result = assertNotNull(query.await())
                assertEquals("git.exe", result.externalCommandName)
                assertEquals(0, result.replacementStartOffset)
                assertEquals(7, result.replacementEndOffset)
                assertEquals("Get-ChildItem", result.candidates.single().replacementText)
                assertEquals(TerminalShellCompletionKind.COMMAND, result.candidates.single().kind)
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    it.session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 7, "Get-ChildItem"))),
                )
            }
        }

    @Test
    fun `unsupported and mismatched buffers send no query`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), ready = false).use {
                assertNull(it.session.requestShellCompletions("Get-Chi", 7))
                it.feed(ready())
                assertNull(it.session.requestShellCompletions("different", 9))
                runCurrent()
                assertTrue(it.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `wrong epochs and ids cannot complete a pending query`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                it.feed(reply("Get-Chi", epoch = "b".repeat(32)))
                it.feed(reply("Get-Chi", id = 2))
                it.feed(reply("Get-Chi").replace("KetraTermCompletion;2;", "KetraTermCompletion;1;"))
                runCurrent()
                assertFalse(query.isCompleted)
                it.feed(reply("Get-Chi"))
                runCurrent()
                assertNotNull(query.await())
            }
        }

    @Test
    fun `user input and visually changed output cannot renew old edits`() =
        runTest {
            for (input in listOf(true, false)) {
                Fixture(StandardTestDispatcher(testScheduler)).use {
                    val expected = assertNotNull(it.session.captureCommandEdit())
                    val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                    runCurrent()
                    if (input) {
                        it.session.submitInput(TerminalKeyEvent(codepoint = 'x'.code))
                    } else {
                        it.feed("x\b \b")
                    }
                    it.feed(reply("Get-Chi"))
                    runCurrent()
                    assertNull(query.await())
                    assertEquals(
                        TerminalInputAdmission.STALE_CONTEXT,
                        it.session.submitInput(expected, listOf(TerminalKeyEvent(codepoint = 'y'.code))),
                    )
                }
            }
        }

    @Test
    fun `output after a valid response still invalidates pre-query editing authority`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val expected = assertNotNull(it.session.captureCommandEdit())
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                it.feed(reply("Get-Chi"))
                runCurrent()
                assertNotNull(query.await())
                it.feed("\u001b]0;changed title\u0007")
                assertEquals(
                    TerminalInputAdmission.STALE_CONTEXT,
                    it.session.submitInput(expected, listOf(TerminalKeyEvent(codepoint = 'y'.code))),
                )
            }
        }

    @Test
    fun `cancellation retains the wire slot until the old reply is consumed`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                query.cancelAndJoin()
                assertNull(it.session.requestShellCompletions("Get-Chi", 7))
                it.feed(reply("Get-Chi"))
                runCurrent()
                val next = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                it.feed(reply("Get-Chi", id = 1))
                assertFalse(next.isCompleted)
                it.feed(reply("Get-Chi", id = 2))
                it.feed(reply("Get-Chi").replace("KetraTermCompletion;2;", "KetraTermCompletion;1;"))
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @Test
    fun `timeout is bounded and session closure cancels the retained request`() =
        runTest {
            val fixture = Fixture(StandardTestDispatcher(testScheduler))
            val query = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
            runCurrent()
            advanceTimeBy(2_000.milliseconds)
            runCurrent()
            assertNull(query.await())
            assertNull(fixture.session.requestShellCompletions("Get-Chi", 7))
            fixture.close()
            runCurrent()
        }

    @Test
    fun `malformed ranges UTF8 and executable controls are rejected without changing the command`() =
        runTest {
            for (bad in listOf(
                reply("Get-Chi", start = 8),
                reply("Get-Chi", end = 100),
                reply("Get-Chi", value = "bad\r\ncommand"),
                reply("Get-Chi").replace(encoded("Get-ChildItem"), "/w=="),
            )) {
                Fixture(StandardTestDispatcher(testScheduler)).use {
                    val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                    runCurrent()
                    it.feed(bad)
                    runCurrent()
                    assertNull(query.await())
                    assertEquals("Get-Chi", it.session.activeShellCommandLine()?.commandText)
                }
            }
        }

    @Test
    fun `a new prompt retires old requests and resets correlation`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                it.feed(ready("b".repeat(32)))
                runCurrent()
                assertNull(query.await())
                val next = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                it.feed(reply("Get-Chi"))
                assertFalse(next.isCompleted)
                it.feed(reply("Get-Chi", epoch = "b".repeat(32)))
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @Test
    fun `viewport metadata changes invalidate a pending edit even without cell changes`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val expected = assertNotNull(it.session.captureCommandEdit())
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                assertTrue(it.session.tryUpdateViewportPixels(80, 4, 800, 80))
                it.feed(reply("Get-Chi"))
                runCurrent()
                assertNull(query.await())
                assertEquals(
                    TerminalInputAdmission.STALE_CONTEXT,
                    it.session.submitInput(expected, listOf(TerminalKeyEvent(codepoint = 'y'.code))),
                )
            }
        }

    @Test
    fun `closure cancels an active completion waiter`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                it.session.close()
                runCurrent()
                assertTrue(query.isCancelled)
            }
        }

    @Test
    fun `candidate and count bounds reject a query but permit the next correlated request`() =
        runTest {
            for (bad in listOf(
                reply("Get-Chi", value = "x".repeat(4097)),
                reply("Get-Chi", label = "x".repeat(513)),
                reply("Get-Chi", externalCommandName = "x".repeat(513)),
                reply("Get-Chi", externalCommandName = "git\n.exe"),
                reply("Get-Chi", externalCommandName = "git.exe").replace(encoded("git.exe"), "%%%"),
                reply("Get-Chi", externalCommandName = "git.exe").replace(encoded("git.exe"), "/w=="),
                reply("Get-Chi").replace(";1;Command;", ";129;Command;"),
                reply("Get-Chi").replace(";1;Command;", ";0;Command;"),
            )) {
                Fixture(StandardTestDispatcher(testScheduler)).use {
                    val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                    runCurrent()
                    it.feed(bad)
                    runCurrent()
                    assertNull(query.await())
                    val next = async { it.session.requestShellCompletions("Get-Chi", 7) }
                    runCurrent()
                    it.feed(reply("Get-Chi", id = 2))
                    it.feed(reply("Get-Chi").replace("KetraTermCompletion;2;", "KetraTermCompletion;1;"))
                    runCurrent()
                    assertNotNull(next.await())
                }
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        ready: Boolean = true,
    ) : AutoCloseable {
        val connector = MockConnector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration = OscShellIntegration,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )

        init {
            session.start()
            feed("\u001b]133;A\u0007PS> \u001b]133;B\u0007Get-Chi")
            if (ready) feed(ready())
        }

        fun feed(text: String) = connector.feedFromHost(text.toByteArray(Charsets.UTF_8))

        override fun close() = session.close()
    }

    companion object {
        private fun encoded(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun ready(epoch: String = "a".repeat(32)) = "\u001b]633;KetraTermCompletion;2;Ready;$epoch\u0007"

        private fun reply(
            line: String,
            epoch: String = "a".repeat(32),
            id: Int = 1,
            value: String = "Get-ChildItem",
            label: String = value,
            start: Int = 0,
            end: Int = line.length,
            externalCommandName: String = "",
        ): String =
            "\u001b]633;KetraTermCompletion;2;Result;$epoch;$id;Ok;${line.length};$start;$end;${encoded(
                line,
            )};${encoded(externalCommandName)};1;Command;${encoded(value)};${encoded(label)};\u0007"
    }
}
