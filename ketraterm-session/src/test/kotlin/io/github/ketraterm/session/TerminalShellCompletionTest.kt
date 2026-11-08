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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalInputEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.TerminalInputAdmission.*
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalShellCompletionTest {
    @ParameterizedTest
    @ValueSource(strings = ["before-next-request", "during-next-request", "dropped"])
    fun `request identity survives an earlier request crossing a prompt boundary`(oldReply: String) =
        runTest {
            Fixture(
                StandardTestDispatcher(testScheduler),
                requestInput = { requestId -> listOf(TerminalKeyEvent.text("q$requestId")) },
            ).use { fixture ->
                val previous = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertEquals("q1", fixture.writtenText())

                fixture.clearPrompt()
                fixture.model.value = fixture.model.value
                fixture.ready("nextEpoch")
                runCurrent()
                assertNull(previous.await())
                if (oldReply == "before-next-request") fixture.reply(requestId = 1)

                val expected = checkNotNull(fixture.session.captureCommandEdit())
                val current = async { fixture.session.requestShellCompletions(expected) }
                runCurrent()
                assertEquals("q1q2", fixture.writtenText(), "The new prompt must receive a distinct explicit identifier")
                if (oldReply == "during-next-request") fixture.reply(requestId = 1)
                fixture.reply(requestId = 2, epoch = "epoch")
                runCurrent()
                assertFalse(current.isCompleted, "Neither an old request in the new prompt nor the wrong epoch can confirm this query")
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("never"))))

                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(current.await())
                assertEquals(ACCEPTED, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("t"))))
                runCurrent()
                assertEquals("q1q2t", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "oversized", "throws"])
    fun `invalid request encoding preserves receipt and does not consume an identifier`(invalidEncoding: String) =
        runTest {
            var rejectEncoding = false
            val identifiers = mutableListOf<Long>()
            Fixture(
                StandardTestDispatcher(testScheduler),
                requiresConfirmation = false,
                requestInput = { requestId ->
                    identifiers += requestId
                    if (!rejectEncoding) {
                        listOf(TerminalKeyEvent.text("q$requestId"))
                    } else {
                        when (invalidEncoding) {
                            "empty" -> emptyList()
                            "oversized" -> List(65) { TerminalKeyEvent.text("never") }
                            else -> throw IllegalArgumentException("Request encoder failed")
                        }
                    }
                },
            ).use { fixture ->
                val expected = checkNotNull(fixture.session.captureCommandEdit())
                val first = async { fixture.session.requestShellCompletions(expected) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(first.await())

                rejectEncoding = true
                val invalid =
                    async {
                        kotlin.test.assertFailsWith<IllegalArgumentException> { fixture.session.requestShellCompletions(expected) }
                    }
                runCurrent()
                invalid.await()
                assertTrue(fixture.session.isCommandEditCurrent(expected), "Rejected encoding cannot revoke an existing receipt")
                assertEquals("q1", fixture.writtenText())

                rejectEncoding = false
                val next = async { fixture.session.requestShellCompletions(expected) }
                runCurrent()
                assertEquals(listOf(1L, 2L, 2L), identifiers)
                assertEquals("q1q2", fixture.writtenText())
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(next.await())
                assertEquals(ACCEPTED, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    @Test
    fun `maximum sized request input is detached before its asynchronous write`() =
        runTest {
            val ioScheduler = TestCoroutineScheduler()
            val input = MutableList<TerminalInputEvent>(64) { TerminalKeyEvent.text("q") }
            Fixture(
                StandardTestDispatcher(testScheduler),
                ioDispatcher = StandardTestDispatcher(ioScheduler),
                requestInput = { input },
            ).use { fixture ->
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertEquals("", fixture.writtenText())
                input.clear()
                ioScheduler.runCurrent()
                assertEquals("q".repeat(64), fixture.writtenText())
                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["failed", "mismatch", "cancelled"])
    fun `ordinary contexts need native proof after their editor state enters a completer`(outcome: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), requiresConfirmation = false, observationBacked = true).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                assertTrue(fixture.session.isCommandEditCurrent(original), "An in-flight request remains viable")
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                when (outcome) {
                    "failed" -> fixture.failReply()
                    "mismatch" -> fixture.reply(commandLine = "git status", cursorOffset = 10)
                    "cancelled" -> {
                        query.cancelAndJoin()
                        fixture.reply()
                    }
                }
                runCurrent()
                if (outcome == "cancelled") assertTrue(query.isCancelled) else assertNull(query.await())
                assertFalse(fixture.session.isCommandEditCurrent(original), "Missing proof cannot publish ordinary provider results")
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                fixture.observe(original.commandLine.copy(commandText = "git st", cursorOffset = 6))
                val differentObservation = checkNotNull(fixture.session.captureCommandEdit())
                assertFalse(checkNotNull(fixture.session.activeShellCommandLine()).requiresShellConfirmation)
                assertTrue(
                    differentObservation.commandLine.requiresShellConfirmation,
                    "Fresh capture must request the missing native proof",
                )
                assertTrue(fixture.session.isCommandEditCurrent(differentObservation))
                assertEquals(
                    STALE_CONTEXT,
                    fixture.session.submitInput(differentObservation, listOf(TerminalKeyEvent.text("never"))),
                    "Another screen observation cannot bypass the same editor state's missing proof",
                )
                fixture.observe(original.commandLine)

                val confirmed = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                assertEquals("qq", fixture.writtenText())
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(confirmed.await())
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
                runCurrent()
                assertEquals("qqt", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["input", "producer", "resize", "prompt", "ready"])
    fun `new editor authority supersedes an earlier native proof requirement`(change: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), requiresConfirmation = false).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                fixture.failReply()
                runCurrent()
                assertNull(query.await())
                when (change) {
                    "input" -> fixture.session.submitInput(TerminalKeyEvent.text("x"))
                    "producer" -> fixture.model.value = fixture.model.value
                    "resize" -> fixture.session.resizeViewport(81, 4)
                    "prompt" -> {
                        fixture.model.value = fixture.model.value
                        fixture.clearPrompt()
                    }
                    "ready" -> {
                        fixture.model.value = fixture.model.value
                        fixture.ready()
                    }
                }
                runCurrent()
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                val current = checkNotNull(fixture.session.captureCommandEdit())
                assertEquals(ACCEPTED, fixture.session.submitInput(current, listOf(TerminalKeyEvent.text("t"))))
                runCurrent()
                assertEquals(if (change == "input") "qxt" else "qt", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `native authority and ordinary observation revisions have distinct lifetimes`(confirmed: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), requiresConfirmation = false, observationBacked = true).use { fixture ->
                val expected = checkNotNull(fixture.session.captureCommandEdit())
                if (confirmed) {
                    val query = async { fixture.session.requestShellCompletions(expected) }
                    runCurrent()
                    fixture.reply()
                    runCurrent()
                    assertNotNull(query.await())
                }
                fixture.observe(expected.commandLine.copy(cursorColumn = 2, cursorRow = 1, requiresShellConfirmation = true))
                assertEquals(confirmed, fixture.session.isCommandEditCurrent(expected))
                fixture.observe(null)
                assertEquals(confirmed, fixture.session.isCommandEditCurrent(expected))
                fixture.observe(expected.commandLine)
                assertEquals(confirmed, fixture.session.isCommandEditCurrent(expected), "An ordinary observation cannot survive ABA")
                assertEquals(
                    if (confirmed) ACCEPTED else STALE_CONTEXT,
                    fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("t"))),
                )
            }
        }

    @Test
    fun `a provisional request can confirm while its screen observation is unavailable`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), observationBacked = true).use { fixture ->
                val expected = checkNotNull(fixture.session.captureCommandEdit())
                fixture.observe(null)
                assertTrue(fixture.session.isCommandEditCurrent(expected))
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("never"))))
                val query = async { fixture.session.requestShellCompletions(expected) }
                runCurrent()
                assertTrue(fixture.session.isCommandEditCurrent(expected))
                assertEquals("q", fixture.writtenText())
                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
                assertNull(fixture.session.captureCommandEdit(), "A confirmed token does not fabricate a screen anchor")
                assertTrue(fixture.session.isCommandEditCurrent(expected))
                assertEquals(ACCEPTED, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    @Test
    fun `late metadata preserves a confirmed receipt and original edit`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val expected = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions(expected) }
                runCurrent()
                fixture.reply()
                runCurrent()
                val result = checkNotNull(query.await())
                fixture.feedMetadata()
                fixture.connector.feedFromHost("\u001b]0;changed title\u0007\u001b[?25h".toByteArray())
                runCurrent()
                assertTrue(fixture.session.isCommandEditCurrent(expected))
                assertSame(result, fixture.session.requestShellCompletions(expected))
                assertEquals("q", fixture.writtenText())
                assertEquals(ACCEPTED, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `context query preserves its original capture across queued or drained terminal output`(drainedBeforeQuery: Boolean) =
        runTest {
            val io = StandardTestDispatcher(TestCoroutineScheduler())
            Fixture(StandardTestDispatcher(testScheduler), ioDispatcher = io).use { fixture ->
                io.scheduler.runCurrent()
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val revision = fixture.session.activeShellCommandLineRevision.value
                fixture.connector.feedFromHost("\u001b[6n".encodeToByteArray())
                if (drainedBeforeQuery) io.scheduler.runCurrent()
                val query = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                assertFalse(query.isCompleted)

                io.scheduler.runCurrent()
                runCurrent()
                io.scheduler.runCurrent()
                assertEquals("\u001b[1;1Rq", fixture.writtenText())
                fixture.reply()
                runCurrent()

                assertNotNull(query.await())
                assertEquals(revision, fixture.session.activeShellCommandLineRevision.value)
                assertTrue(fixture.session.isCommandEditCurrent(original))
                val fresh = checkNotNull(fixture.session.captureCommandEdit())
                val next = async { fixture.session.requestShellCompletions(fresh) }
                runCurrent()
                assertNotNull(next.await())
                assertEquals("\u001b[1;1Rq", fixture.writtenText(), "The fresh context reuses the valid native receipt")
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
                assertFalse(fixture.session.isCommandEditCurrent(fresh))
            }
            io.scheduler.runCurrent()
        }

    @Test
    fun `foreign and cancelled edit contexts cannot start native queries`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Fixture(dispatcher).use { fixture ->
                Fixture(dispatcher).use { other ->
                    val foreign = checkNotNull(other.session.captureCommandEdit())
                    val cancelled = checkNotNull(fixture.session.captureCommandEdit())
                    cancelled.cancel()
                    assertNull(fixture.session.requestShellCompletions(foreign))
                    assertNull(fixture.session.requestShellCompletions(cancelled))
                    runCurrent()
                    assertEquals("", fixture.writtenText())
                    assertEquals("", other.writtenText())
                }
            }
        }

    @Test
    fun `native query waits for a queued terminal reply after initial edit capture`() =
        runTest {
            val io = StandardTestDispatcher(TestCoroutineScheduler())
            Fixture(StandardTestDispatcher(testScheduler), ioDispatcher = io).use { fixture ->
                io.scheduler.runCurrent()
                val original = checkNotNull(fixture.session.captureCommandEdit())
                fixture.connector.feedFromHost("\u001b[6n".encodeToByteArray())
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()

                assertFalse(query.isCompleted, "A pending terminal response must delay native admission")
                assertEquals("", fixture.writtenText())
                io.scheduler.runCurrent()
                assertEquals("\u001b[1;1R", fixture.writtenText())
                runCurrent()
                assertFalse(query.isCompleted)
                io.scheduler.runCurrent()
                assertEquals("\u001b[1;1Rq", fixture.writtenText(), "Only one native query follows the terminal reply")

                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
            }
            io.scheduler.runCurrent()
        }

    @Test
    fun `cancelling while admission is busy leaves no native query reserved`() =
        runTest {
            val io = StandardTestDispatcher(TestCoroutineScheduler())
            Fixture(StandardTestDispatcher(testScheduler), ioDispatcher = io).use { fixture ->
                io.scheduler.runCurrent()
                fixture.connector.feedFromHost("\u001b[6n".encodeToByteArray())
                val cancelled = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(cancelled.isCompleted)
                cancelled.cancelAndJoin()
                io.scheduler.runCurrent()
                runCurrent()
                assertEquals("\u001b[1;1R", fixture.writtenText())

                val next = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                io.scheduler.runCurrent()
                assertEquals("\u001b[1;1Rq", fixture.writtenText())
                fixture.reply()
                runCurrent()
                assertNotNull(next.await())
            }
            io.scheduler.runCurrent()
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `concurrent requests for same buffer share one native query and both receive result`(requiresConfirmation: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), requiresConfirmation).use { fixture ->
                val first = async { fixture.session.requestShellCompletions("git s", 5) }
                val second = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertEquals("q", fixture.writtenText(), "Only one query must be sent to the shell")
                fixture.reply()
                runCurrent()
                val result1 = checkNotNull(first.await())
                val result2 = checkNotNull(second.await())
                assertSame(result1, result2)
                assertEquals("q", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelling either shared caller leaves the other query and edit confirmation active`(cancelFirst: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val first = async { fixture.session.requestShellCompletions("git s", 5) }
                val second = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                (if (cancelFirst) first else second).cancelAndJoin()

                fixture.reply()
                runCurrent()
                assertNotNull((if (cancelFirst) second else first).await())
                assertEquals("q", fixture.writtenText())
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["equal", "away-and-back", "input"])
    fun `same text in a changed context waits for the old query then requests its own confirmation`(change: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val old = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                when (change) {
                    "equal" -> fixture.model.value = fixture.model.value
                    "away-and-back" -> {
                        val original = fixture.model.value
                        fixture.model.value = null
                        fixture.model.value = original
                    }
                    "input" -> fixture.session.submitInput(TerminalKeyEvent.text("x"))
                }
                val latest = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(latest.isCompleted)
                assertEquals(if (change == "input") "qx" else "q", fixture.writtenText())

                fixture.reply()
                runCurrent()

                assertNull(old.await(), "The old query cannot confirm the changed context")
                assertFalse(latest.isCompleted, "Equal text cannot join an obsolete query")
                assertEquals(if (change == "input") "qxq" else "qq", fixture.writtenText())
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(latest.await())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["same", "away-and-back"])
    fun `native confirmation preserves editing authority across repaint`(repaint: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.connector.feedFromHost("git s".encodeToByteArray())
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val revision = fixture.session.activeShellCommandLineRevision.value
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()

                if (repaint == "away-and-back") {
                    fixture.connector.feedFromHost("\r\u001b[2Kother".encodeToByteArray())
                }
                fixture.connector.feedFromHost("\r\u001b[2Kgit s".encodeToByteArray())
                runCurrent()
                assertEquals(original.commandLine, fixture.session.activeShellCommandLine())
                assertEquals(revision, fixture.session.activeShellCommandLineRevision.value)

                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
                assertEquals(revision, fixture.session.activeShellCommandLineRevision.value)
                assertTrue(fixture.session.isCommandEditCurrent(original))

                val fresh = checkNotNull(fixture.session.captureCommandEdit())
                val next = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertNotNull(next.await())
                assertEquals("q", fixture.writtenText())
                assertTrue(fixture.session.isCommandEditCurrent(fresh))
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["success", "mismatch", "failed", "unavailable", "cancelled", "changed-buffer", "closed"])
    fun `native query outcomes without a retryable context change do not invalidate suggestions`(outcome: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.connector.feedFromHost("git s".encodeToByteArray())
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                if (outcome != "success") {
                    fixture.connector.feedFromHost("\r\u001b[2Kgit s".encodeToByteArray())
                }
                when (outcome) {
                    "cancelled" -> query.cancelAndJoin()
                    "changed-buffer" -> fixture.model.value = fixture.model.value?.copy(commandText = "git st", cursorOffset = 6)
                    "closed" -> fixture.session.close()
                }
                runCurrent()
                val revision = fixture.session.activeShellCommandLineRevision.value

                when (outcome) {
                    "failed" -> fixture.failReply()
                    "unavailable" -> fixture.clearPrompt()
                    "mismatch" -> fixture.reply(commandLine = "git status", cursorOffset = 10)
                    "closed" -> fixture.reply(deliverOutput = false)
                    else -> fixture.reply()
                }
                runCurrent()

                assertEquals(revision, fixture.session.activeShellCommandLineRevision.value)
                when (outcome) {
                    "success" -> assertNotNull(query.await())
                    "cancelled", "closed" -> assertTrue(query.isCancelled)
                    else -> assertNull(query.await())
                }
            }
        }

    @Test
    fun `duplicate readiness does not reuse request identifiers`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), requiresConfirmation = false).use { fixture ->
                val first = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(first.await())
                fixture.ready()

                val next = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply(requestId = 1)
                runCurrent()
                assertFalse(next.isCompleted, "A delayed duplicate reply cannot satisfy a later query")
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `confirmation admits original and freshly captured contexts without a second native query`(captureAfterQuery: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                assertEquals("q", fixture.writtenText())
                fixture.reply()
                runCurrent()
                val result = checkNotNull(query.await())
                val expected = if (captureAfterQuery) checkNotNull(fixture.session.captureCommandEdit()) else original

                assertSame(result, fixture.session.requestShellCompletions("git s", 5))
                assertEquals(ACCEPTED, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("t"))))
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(expected, listOf(TerminalKeyEvent.text("never"))))
                runCurrent()
                assertEquals("qt", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["git status", "git s "])
    fun `real suffixes including whitespace cannot confirm a screen prefix`(editorBuffer: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply(commandLine = editorBuffer)
                runCurrent()

                assertNull(query.await())
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                val fresh = checkNotNull(fixture.session.captureCommandEdit())
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(fresh, listOf(TerminalKeyEvent.text("never"))))
                runCurrent()
                assertEquals("q", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["unavailable", "different-text", "different-caret", "moved-anchor", "prediction-cleared"])
    fun `candidate collection reuses native confirmation during screen projection changes`(projection: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), observationBacked = true).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val confirmation = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                fixture.reply()
                runCurrent()
                val result = checkNotNull(confirmation.await())

                fixture.observe(
                    when (projection) {
                        "unavailable" -> null
                        "different-text" -> original.commandLine.copy(commandText = "git status", cursorOffset = 10, cursorColumn = 10)
                        "different-caret" -> original.commandLine.copy(cursorOffset = 4, cursorColumn = 4)
                        "moved-anchor" -> original.commandLine.copy(cursorColumn = 2, cursorRow = 1)
                        "prediction-cleared" -> original.commandLine.copy(requiresShellConfirmation = false)
                        else -> error("Unknown projection: $projection")
                    },
                )

                // The UI confirms its retained context before the provider asks for the same candidates.
                val candidates = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertTrue(candidates.isCompleted, "Candidate collection must not wait for another shell query")
                assertSame(result, candidates.await(), "Screen projection is not the native editor's confirmed buffer")
                assertEquals("q", fixture.writtenText())
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["different-text", "different-caret", "prompt-ended"])
    fun `a native receipt cannot supply a different or invalidated request`(change: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), observationBacked = true).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val confirmation = async { fixture.session.requestShellCompletions(original) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(confirmation.await())
                fixture.observe(null)
                if (change == "prompt-ended") fixture.clearPrompt()

                val text = if (change == "different-text") "git t" else "git s"
                val cursor = if (change == "different-caret") 4 else 5
                assertNull(fixture.session.requestShellCompletions(text, cursor))
                runCurrent()
                assertEquals("q", fixture.writtenText())
            }
        }

    @Test
    fun `cancelling the first context preserves confirmed candidates for a surviving caller`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), observationBacked = true).use { fixture ->
                val firstContext = checkNotNull(fixture.session.captureCommandEdit())
                val secondContext = checkNotNull(fixture.session.captureCommandEdit())
                val first = async { fixture.session.requestShellCompletions(firstContext) }
                val second = async { fixture.session.requestShellCompletions(secondContext) }
                runCurrent()
                assertEquals("q", fixture.writtenText())

                firstContext.cancel()
                first.cancelAndJoin()
                fixture.reply()
                runCurrent()
                val result = checkNotNull(second.await())
                fixture.observe(null)

                assertNull(fixture.session.requestShellCompletions(firstContext), "A cancelled caller cannot reuse its context")
                assertTrue(fixture.session.isCommandEditCurrent(secondContext))
                assertSame(
                    result,
                    fixture.session.requestShellCompletions("git s", 5),
                    "The surviving caller can collect confirmed candidates",
                )
                assertEquals("q", fixture.writtenText())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["input", "resize", "clear", "equal", "ready"])
    fun `confirmation and its cached result expire after intervening state`(change: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
                val confirmed = checkNotNull(fixture.session.captureCommandEdit())

                when (change) {
                    "input" -> assertEquals(ACCEPTED, fixture.session.submitInput(TerminalKeyEvent.text("x")))
                    "resize" -> fixture.session.resizeViewport(81, 4)
                    "clear" -> fixture.session.clearBuffer()
                    "equal" -> fixture.model.value = fixture.model.value
                    "ready" -> fixture.ready()
                }
                runCurrent()
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(confirmed, listOf(TerminalKeyEvent.text("never"))))
                val fresh = checkNotNull(fixture.session.captureCommandEdit())
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(fresh, listOf(TerminalKeyEvent.text("never"))))

                val next = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(next.isCompleted, "A stale confirmation must not return its cached native candidates")
                assertEquals(if (change == "input") "qxq" else "qq", fixture.writtenText())
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @Test
    fun `cancelled query cannot confirm a provisional context when its reply arrives`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                query.cancelAndJoin()
                fixture.reply()
                runCurrent()

                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                val fresh = checkNotNull(fixture.session.captureCommandEdit())
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(fresh, listOf(TerminalKeyEvent.text("never"))))
                runCurrent()
                assertEquals("q", fixture.writtenText())
            }
        }

    @Test
    fun `latest query waits for a cancelled wire request then confirms the current buffer`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val old = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                old.cancelAndJoin()
                fixture.model.value = TerminalShellCommandLineSnapshot("git st", 6, 6, 0, true)

                val latest = async { fixture.session.requestShellCompletions("git st", 6) }
                runCurrent()
                assertFalse(latest.isCompleted, "A cancelled wire request must not discard the final typing update")
                assertEquals("q", fixture.writtenText(), "Only one native query may be in flight")

                fixture.reply()
                runCurrent()
                assertFalse(latest.isCompleted, "The old reply cannot confirm the new buffer")
                assertEquals("qq", fixture.writtenText())
                fixture.reply(commandLine = "git st", cursorOffset = 6, requestId = 2)
                runCurrent()
                assertNotNull(latest.await())
            }
        }

    @Test
    fun `replacement query revalidates its buffer after the cancelled slot retires`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val old = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                old.cancelAndJoin()
                val latest = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(latest.isCompleted)

                fixture.model.value = TerminalShellCommandLineSnapshot("other", 5, 5, 0, true)
                fixture.reply()
                runCurrent()
                assertNull(latest.await())
                assertEquals("q", fixture.writtenText())
            }
        }

    @Test
    fun `waiting for retirement and a new reply share one deadline`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val old = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                old.cancelAndJoin()
                val latest = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                advanceTimeBy(9_500.milliseconds)
                fixture.reply()
                runCurrent()
                assertEquals("qq", fixture.writtenText())
                assertFalse(latest.isCompleted)

                advanceTimeBy(500.milliseconds)
                runCurrent()
                assertNull(latest.await())
                fixture.reply(requestId = 2)
                runCurrent()
                val fresh = checkNotNull(fixture.session.captureCommandEdit())
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(fresh, listOf(TerminalKeyEvent.text("never"))))
            }
        }

    @Test
    fun `cold native completion can finish after module discovery without repeating the query`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                advanceTimeBy(3_500.milliseconds)
                runCurrent()
                assertFalse(query.isCompleted)
                assertEquals("q", fixture.writtenText())

                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
                assertEquals("q", fixture.writtenText())
            }
        }

    @Test
    fun `cancelling a waiting replacement does not retire another request`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val old = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                old.cancelAndJoin()
                val intermediate = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                intermediate.cancelAndJoin()
                val latest = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(latest.isCompleted)
                assertEquals("q", fixture.writtenText())

                fixture.reply()
                runCurrent()
                assertEquals("qq", fixture.writtenText())
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(latest.await())
            }
        }

    @Test
    fun `session closure cancels a replacement waiting for wire retirement`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val old = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                old.cancelAndJoin()
                val latest = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.session.close()
                runCurrent()
                assertTrue(latest.isCancelled)
                assertEquals("q", fixture.writtenText())
            }
        }

    @Test
    fun `confirmed contexts still honor cancellation and session ownership`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Fixture(dispatcher).use { fixture ->
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(query.await())
                val confirmed = checkNotNull(fixture.session.captureCommandEdit())

                Fixture(dispatcher).use { other ->
                    assertEquals(STALE_CONTEXT, other.session.submitInput(confirmed, listOf(TerminalKeyEvent.text("never"))))
                }
                confirmed.cancel()
                assertEquals(CANCELLED, fixture.session.submitInput(confirmed, listOf(TerminalKeyEvent.text("never"))))
                runCurrent()
                assertEquals("q", fixture.writtenText())
            }
        }

    @Test
    fun `ordinary complete snapshots still invoke the completer for each explicit request`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), requiresConfirmation = false).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val first = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(first.await())

                val next = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(next.isCompleted)
                assertEquals("qq", fixture.writtenText())
                assertEquals(STALE_CONTEXT, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("never"))))
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(next.await())
                assertEquals(ACCEPTED, fixture.session.submitInput(original, listOf(TerminalKeyEvent.text("t"))))
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        requiresConfirmation: Boolean = true,
        ioDispatcher: CoroutineDispatcher = dispatcher,
        private val observationBacked: Boolean = false,
        private val requestInput: (Long) -> List<TerminalInputEvent> = { listOf(TerminalKeyEvent.text("q")) },
    ) : AutoCloseable {
        val connector = MockConnector()
        val model = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot("git s", 5, 5, 0, requiresConfirmation))
        private var observation = model.value
        private var observationRevision = 0L
        private var epoch = "epoch"
        private lateinit var channel: TerminalShellCompletionChannel
        private val host = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        channel = context.shellCompletion
                        val integration = host.create(context)
                        object : TerminalShellIntegration by integration {
                            override val commandLineObservationRevision: Long get() = observationRevision

                            override fun activeCommandLine(): TerminalShellCommandLineSnapshot? =
                                if (observationBacked) observation else integration.activeCommandLine()

                            override fun <T> withCommandLine(action: (Long, TerminalShellCommandLineSnapshot?) -> T): T? =
                                integration.withCommandLine { revision, snapshot ->
                                    action(revision, if (observationBacked) observation else snapshot)
                                }
                        }
                    },
                workerDispatcher = dispatcher,
                ioDispatcher = ioDispatcher,
                cleanupDispatcher = dispatcher,
            )

        init {
            session.start()
            ready()
        }

        fun ready(epoch: String = "epoch") {
            this.epoch = epoch
            channel.ready(epoch, requestInput)
        }

        fun observe(snapshot: TerminalShellCommandLineSnapshot?) {
            check(observationBacked)
            observation = snapshot
            observationRevision++
        }

        fun clearPrompt() {
            channel.clearPrompt()
            feedMetadata()
        }

        fun failReply() {
            channel.respond(epoch, 1, null)
            feedMetadata()
        }

        fun reply(
            commandLine: String = "git s",
            requestId: Long = 1,
            cursorOffset: Int = 5,
            deliverOutput: Boolean = true,
            epoch: String = this.epoch,
        ) {
            channel.respond(
                epoch,
                requestId,
                TerminalShellCompletionResult(
                    commandLine,
                    cursorOffset,
                    4,
                    5,
                    listOf(TerminalShellCompletionCandidate("status", "status", "", TerminalShellCompletionKind.ARGUMENT)),
                ),
            )
            if (deliverOutput) feedMetadata()
        }

        fun feedMetadata() = connector.feedFromHost("\u001b]633;completion metadata\u0007".toByteArray())

        fun writtenText(): String = connector.writtenBytes.toString(Charsets.UTF_8)

        override fun close() = session.close()
    }
}
