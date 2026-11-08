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
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCompletionKind
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.*
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
    fun `completion readiness is accepted only within a primary prompt`() =
        runTest {
            for (boundary in listOf("\u001b]133;C\u0007", "\u001b]133;D;0\u0007", "\u001b[?1049h")) {
                Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                    assertTrue(fixture.session.shellCompletionAvailable)
                    fixture.feed(boundary)
                    assertFalse(fixture.session.shellCompletionAvailable)
                    fixture.feed(ready("b".repeat(32)))
                    assertFalse(fixture.session.shellCompletionAvailable)
                    assertNull(fixture.session.requestShellCompletions("Get-Chi", 7))
                    runCurrent()
                    assertTrue(fixture.connector.writtenBytes.isEmpty())
                }
            }
        }

    @Test
    fun `local primary clear retires completion without awaiting further output`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val query = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                assertFalse(query.isCompleted)
                assertTrue(fixture.session.clearBuffer())
                runCurrent()
                assertFalse(fixture.session.shellCompletionAvailable)
                assertTrue(query.isCompleted)
                assertNull(query.await())
                fixture.feed(ready("b".repeat(32)))
                assertFalse(fixture.session.shellCompletionAvailable)

                fixture.feed("\u001b]133;A\u0007PS> \u001b]133;B\u0007Get-Chi")
                for (byte in ready("c".repeat(32)).toByteArray()) fixture.connector.feedFromHost(byteArrayOf(byte))
                assertTrue(fixture.session.shellCompletionAvailable)
                val next = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                fixture.feed(reply("Get-Chi", epoch = "c".repeat(32), id = 2))
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @Test
    fun `visible prediction requires exact native confirmation before editing`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed("ldItem\u001b[6D")
                val expected = assertNotNull(fixture.session.captureCommandEdit())
                assertEquals("Get-Chi", expected.commandLine.commandText)
                assertTrue(expected.commandLine.requiresShellConfirmation)
                val edit = listOf(TerminalTextReplacementEvent(0, 7, "Get-ChildItem"))
                assertEquals(TerminalInputAdmission.STALE_CONTEXT, fixture.session.submitInput(expected, edit))
                val query = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                fixture.feed(reply("Get-Chi"))
                runCurrent()
                assertNotNull(query.await())
                assertEquals(TerminalInputAdmission.ACCEPTED, fixture.session.submitInput(expected, edit))
            }
        }

    @Test
    fun `changed wrapped prediction preserves native buffer confirmation`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), columns = 16).use { fixture ->
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                fixture.feed("ldItem -Name old\u001b[1;12H")
                fixture.session.requestRender(0)
                runCurrent()
                val expected = assertNotNull(fixture.session.captureCommandEdit())
                assertEquals("Get-Chi", expected.commandLine.commandText)
                assertTrue(expected.commandLine.requiresShellConfirmation)
                val previousRevision = fixture.session.activeShellCommandLineRevision.value
                val query = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()

                // The later packet changes only the prediction's wrapped continuation.
                fixture.feed("\u001b[2;1Hm -Name new\u001b[1;12H")
                fixture.feed(reply("Get-Chi"))
                fixture.session.requestRender(0)
                runCurrent()
                assertNotNull(query.await())
                assertEquals(expected.commandLine, fixture.session.activeShellCommandLine())
                assertTrue(fixture.session.activeShellCommandLineRevision.value > previousRevision)
                assertEquals(
                    TerminalInputAdmission.ACCEPTED,
                    fixture.session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 7, "Get-ChildItem"))),
                )
            }
        }

    @Test
    fun `actual editable suffix cannot confirm a screen prefix`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(" tail\u001b[5D")
                val expected = assertNotNull(fixture.session.captureCommandEdit())
                assertEquals("Get-Chi", expected.commandLine.commandText)
                assertTrue(expected.commandLine.requiresShellConfirmation)
                val query = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                fixture.feed(reply("Get-Chi tail", cursor = 7, end = 7))
                runCurrent()
                assertNull(query.await())
                assertEquals(
                    TerminalInputAdmission.STALE_CONTEXT,
                    fixture.session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 7, "wrong"))),
                )
                assertEquals(request(1), fixture.connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `native confirmation preserves typed spaces before prediction`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed("  argument\u001b[8D")
                val snapshot = assertNotNull(fixture.session.activeShellCommandLine())
                assertEquals("Get-Chi  ", snapshot.commandText)
                assertEquals(9, snapshot.cursorOffset)
                val query = async { fixture.session.requestShellCompletions(snapshot.commandText, snapshot.cursorOffset) }
                runCurrent()
                fixture.feed(reply("Get-Chi  "))
                runCurrent()
                assertEquals("Get-Chi  ", assertNotNull(query.await()).commandLine)
            }
        }

    @Test
    fun `visible suffix remains unavailable without a prompt native hook`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), ready = false).use { fixture ->
                fixture.feed("ldItem\u001b[6D")
                assertNull(fixture.session.activeShellCommandLine())
                fixture.feed(ready())
                assertTrue(assertNotNull(fixture.session.activeShellCommandLine()).requiresShellConfirmation)
                fixture.feed("\r\n\u001b]133;A\u0007$ \u001b]133;B\u0007word\u001b[2D")
                assertNull(fixture.session.activeShellCommandLine())
            }
        }

    @Test
    fun `validated PowerShell hook announces prefix reset only for its prompt`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), ready = false).use { fixture ->
                fun keys() = assertNotNull(fixture.session.captureCommandEdit()).prefixResetKeys

                assertEquals(emptyList(), keys())
                for (invalid in listOf(
                    ready("bad-epoch"),
                    ready().replace(";3;", ";2;"),
                    ready().replace("Ready;", "Unknown;"),
                    ready().replace("\u0007", ";Unknown\u0007"),
                    ready().replace("\u0007", ";PrefixReset;extra\u0007"),
                )) {
                    fixture.feed(invalid)
                    assertEquals(emptyList(), keys())
                }

                fixture.feed(ready())
                assertEquals(emptyList(), keys())

                fixture.feed(ready().replace("\u0007", ";PrefixReset\u0007"))
                val powerShellEdit = assertNotNull(fixture.session.captureCommandEdit())
                assertEquals(
                    listOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent(codepoint = 'r'.code)),
                    powerShellEdit.prefixResetKeys,
                )
                fixture.feed("\r\n\u001b]133;A\u0007$ \u001b]133;B\u0007echo e\u0301")
                assertEquals(emptyList(), keys())
                assertEquals(
                    listOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent(codepoint = 'r'.code)),
                    powerShellEdit.prefixResetKeys,
                )
                assertEquals(
                    TerminalInputAdmission.STALE_CONTEXT,
                    fixture.session.submitInput(powerShellEdit, listOf(TerminalTextReplacementEvent(0, 1, "wrong"))),
                )

                fixture.feed(
                    "\r\n\u001b]133;A\u0007PS> " + ready("b".repeat(32)).replace("\u0007", ";PrefixReset\u0007") +
                        "\u001b]133;B\u0007echo e\u0301",
                )
                assertEquals(powerShellEdit.prefixResetKeys, keys())
                runCurrent()
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `chunked native reply preserves escaped edits and pre-query acceptance`() =
        runTest {
            val fixture = Fixture(StandardTestDispatcher(testScheduler))
            fixture.use {
                val expected = assertNotNull(it.session.captureCommandEdit())
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                assertEquals(request(1), it.connector.writtenBytes.toString(Charsets.UTF_8))
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
                it.feed(reply("Get-Chi").replace("KetraTermCompletion;3;", "KetraTermCompletion;1;"))
                runCurrent()
                assertFalse(query.isCompleted)
                it.feed(reply("Get-Chi"))
                runCurrent()
                assertNotNull(query.await())
            }
        }

    @Test
    fun `user input invalidates native authority while repaint preserves it`() =
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
                    if (input) assertNull(query.await()) else assertNotNull(query.await())
                    assertEquals(
                        if (input) TerminalInputAdmission.STALE_CONTEXT else TerminalInputAdmission.ACCEPTED,
                        it.session.submitInput(expected, listOf(TerminalKeyEvent(codepoint = 'y'.code))),
                    )
                }
            }
        }

    @Test
    fun `title output after a valid response preserves editing authority`() =
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
                    TerminalInputAdmission.ACCEPTED,
                    it.session.submitInput(expected, listOf(TerminalKeyEvent(codepoint = 'y'.code))),
                )
            }
        }

    @Test
    fun `native confirmation is independent of reply position within a fragmented redraw`() =
        runTest {
            val redraw = listOf("\r", "\u001b[1;5H", "\u001b[K", "Get-", "Chi", "ldItem -Name prediction", "\u001b[1;12H")
            for (replyPosition in 0..redraw.size) {
                Fixture(StandardTestDispatcher(testScheduler), columns = 16).use { fixture ->
                    fixture.feed("ldItem -Name old\u001b[1;12H")
                    val expected = assertNotNull(fixture.session.captureCommandEdit())
                    val query = async { fixture.session.requestShellCompletions(expected) }
                    runCurrent()
                    for (index in 0..redraw.size) {
                        if (index == replyPosition) {
                            for (byte in reply("Get-Chi").toByteArray()) fixture.connector.feedFromHost(byteArrayOf(byte))
                            runCurrent()
                            assertNotNull(query.await(), "reply at redraw offset $replyPosition")
                        }
                        if (index < redraw.size) {
                            for (byte in redraw[index].toByteArray()) fixture.connector.feedFromHost(byteArrayOf(byte))
                        }
                        runCurrent()
                    }
                    assertEquals("Get-Chi", fixture.session.activeShellCommandLine()?.commandText)
                    assertTrue(fixture.session.isCommandEditCurrent(expected))
                    assertEquals(
                        TerminalInputAdmission.ACCEPTED,
                        fixture.session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 7, "Get-ChildItem"))),
                        "reply at redraw offset $replyPosition",
                    )
                }
            }
        }

    @Test
    fun `cancellation retains the wire slot until the old reply is consumed`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use {
                val query = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                query.cancelAndJoin()
                val next = async { it.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                assertFalse(next.isCompleted)
                it.feed(reply("Get-Chi"))
                runCurrent()
                it.feed(reply("Get-Chi", id = 1))
                assertFalse(next.isCompleted)
                it.feed(reply("Get-Chi", id = 2))
                it.feed(reply("Get-Chi").replace("KetraTermCompletion;3;", "KetraTermCompletion;1;"))
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
            advanceTimeBy(10_000.milliseconds)
            runCurrent()
            assertTrue(query.isCompleted)
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
    fun `a queued query consumed at a later prompt cannot steal its next reply`() =
        runTest {
            for (oldReplyBeforeRequest in listOf(true, false)) {
                Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                    val old = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                    runCurrent()
                    assertEquals(request(1), fixture.connector.writtenBytes.toString(Charsets.UTF_8))

                    val epoch = "b".repeat(32)
                    val nextPrompt =
                        "\u001b]133;C\u0007\r\n\u001b]133;D;0\u0007\u001b]133;A\u0007PS> " +
                            ready(epoch) + "\u001b]133;B\u0007Get-Chi"
                    for (byte in nextPrompt.toByteArray()) fixture.connector.feedFromHost(byteArrayOf(byte))
                    runCurrent()
                    assertNull(old.await())

                    // Input and output are independent streams: the old chord can execute after Ready.
                    val lateReply = reply("", epoch = epoch, id = 1)
                    if (oldReplyBeforeRequest) fixture.feed(lateReply)
                    val current = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                    runCurrent()
                    if (!oldReplyBeforeRequest) fixture.feed(lateReply)
                    runCurrent()
                    assertFalse(current.isCompleted)
                    assertEquals(request(1) + request(2), fixture.connector.writtenBytes.toString(Charsets.UTF_8))

                    fixture.feed(reply("Get-Chi", epoch = epoch, id = 2))
                    runCurrent()
                    assertEquals("Get-Chi", assertNotNull(current.await()).commandLine)
                }
            }
        }

    @Test
    fun `a new prompt retires old requests without reusing their identities`() =
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
                it.feed(reply("Get-Chi", epoch = "b".repeat(32), id = 2))
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @Test
    fun `repeated readiness for the same epoch never reuses a request id`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val first = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                fixture.feed(reply("Get-Chi"))
                runCurrent()
                assertNotNull(first.await())

                fixture.feed(ready())
                val second = async { fixture.session.requestShellCompletions("Get-Chi", 7) }
                runCurrent()
                fixture.feed(reply("Get-Chi", id = 1))
                runCurrent()
                assertFalse(second.isCompleted)
                fixture.feed(reply("Get-Chi", id = 2))
                runCurrent()
                assertNotNull(second.await())
                assertEquals(request(1) + request(2), fixture.connector.writtenBytes.toString(Charsets.UTF_8))
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
                    it.feed(reply("Get-Chi").replace("KetraTermCompletion;3;", "KetraTermCompletion;1;"))
                    runCurrent()
                    assertNotNull(next.await())
                }
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        ready: Boolean = true,
        columns: Int = 80,
    ) : AutoCloseable {
        val connector = MockConnector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(columns, 4),
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
        private fun request(id: Long): String =
            "\u001b[24~q" +
                id
                    .toString()
                    .padStart(19, '0')
                    .map { "\u001b[24~$it" }
                    .joinToString("") + "\u001b[24~e"

        private fun encoded(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun ready(epoch: String = "a".repeat(32)) = "\u001b]633;KetraTermCompletion;3;Ready;$epoch\u0007"

        private fun reply(
            line: String,
            epoch: String = "a".repeat(32),
            id: Int = 1,
            value: String = "Get-ChildItem",
            label: String = value,
            start: Int = 0,
            end: Int = line.length,
            cursor: Int = line.length,
            externalCommandName: String = "",
        ): String =
            "\u001b]633;KetraTermCompletion;3;Result;$epoch;$id;Ok;$cursor;$start;$end;${encoded(
                line,
            )};${encoded(externalCommandName)};1;Command;${encoded(value)};${encoded(label)};\u0007"
    }
}
