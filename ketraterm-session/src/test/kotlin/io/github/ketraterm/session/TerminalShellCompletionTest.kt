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
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.TerminalInputAdmission.*
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalShellCompletionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `confirmation admits original and freshly captured contexts without a second native query`(captureAfterQuery: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val original = checkNotNull(fixture.session.captureCommandEdit())
                val query = async { fixture.session.requestShellCompletions("git s", 5) }
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
    @ValueSource(strings = ["input", "output", "resize", "equal", "ready"])
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
                    "output" -> fixture.feedMetadata()
                    "resize" -> fixture.session.resizeViewport(81, 4)
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
                fixture.reply(requestId = if (change == "ready") 1 else 2)
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
                advanceTimeBy(9_500)
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
                val first = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                fixture.reply()
                runCurrent()
                assertNotNull(first.await())

                val next = async { fixture.session.requestShellCompletions("git s", 5) }
                runCurrent()
                assertFalse(next.isCompleted)
                assertEquals("qq", fixture.writtenText())
                fixture.reply(requestId = 2)
                runCurrent()
                assertNotNull(next.await())
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        requiresConfirmation: Boolean = true,
    ) : AutoCloseable {
        val connector = MockConnector()
        val model = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot("git s", 5, 5, 0, requiresConfirmation))
        private lateinit var channel: TerminalShellCompletionChannel
        private val host = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        channel = context.shellCompletion
                        host.create(context)
                    },
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )

        init {
            session.start()
            ready()
        }

        fun ready() = channel.ready("epoch", listOf(TerminalKeyEvent.text("q")))

        fun reply(
            commandLine: String = "git s",
            requestId: Long = 1,
            cursorOffset: Int = 5,
        ) {
            channel.respond(
                "epoch",
                requestId,
                TerminalShellCompletionResult(
                    commandLine,
                    cursorOffset,
                    4,
                    5,
                    listOf(TerminalShellCompletionCandidate("status", "status", "", TerminalShellCompletionKind.ARGUMENT)),
                ),
            )
            feedMetadata()
        }

        fun feedMetadata() = connector.feedFromHost("\u001b]633;completion metadata\u0007".toByteArray())

        fun writtenText(): String = connector.writtenBytes.toString(Charsets.UTF_8)

        override fun close() = session.close()
    }
}
