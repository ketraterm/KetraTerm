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
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TerminalSessionClearTest {
    @Test
    fun `local clear removes screen and history without input`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2, 10),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 2)
                    val resizeCalls = connector.resizeCalls.toList()
                    session.feed("one\r\ntwo\r\nthree")
                    val before = session.snapshot()
                    assertTrue(before.history > 0)
                    assertTrue(session.clearBuffer())
                    val after = session.snapshot()
                    assertEquals(0, after.history)
                    assertEquals(5, after.column)
                    assertEquals(1, after.row)
                    assertTrue(after.text.isBlank())
                    assertTrue(after.lineIds.none { it in before.lineIds })
                    assertNotEquals(before.historyGeneration, after.historyGeneration)
                    assertTrue(session.clearBuffer())
                    testScheduler.runCurrent()
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                    assertEquals(resizeCalls, connector.resizeCalls)
                    assertEquals(1, connector.startCount)
                    assertEquals(0, connector.closeCount)
                }
        }

    @Test
    fun `clear preserves modes margins pen saved cursor and tab stops`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(12, 4, 10),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(12, 4)
                    session.feed("\u001b[3g\u001b[5G\u001bH\u001b[2;4r\u001b[?6h\u001b[?2004h\u001b[31m\u001b[2;3H\u001b7X")
                    val before = session.snapshot()
                    val modes = session.getInputModeBits()
                    assertTrue(session.clearBuffer())
                    assertEquals(modes, session.getInputModeBits())
                    assertEquals(before.column, session.snapshot().column)
                    assertEquals(before.row, session.snapshot().row)
                    session.feed("Z")
                    val after = session.snapshot()
                    assertEquals(before.attributes[2 * 12 + 2], after.attributes[2 * 12 + 3])
                    session.feed("\u001b8")
                    assertEquals(2, session.snapshot().column)
                    assertEquals(2, session.snapshot().row)
                    session.feed("\r\t")
                    assertEquals(4, session.snapshot().column)
                    session.feed("\u001b[1;1H")
                    assertEquals(1, session.snapshot().row)
                    session.encodePaste(TerminalPasteEvent("x"))
                    testScheduler.runCurrent()
                    assertEquals("\u001b[200~x\u001b[201~", connector.writtenBytes.toString(Charsets.UTF_8))
                }
        }

    @Test
    fun `clear cancels pending wrap and erases wide clustered protected and linked cells`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2, 10),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 2)
                    session.feed("\u001b]8;;https://example.org\u0007\u001b[1\"q界e\u0301\u001b]8;;\u00071234567")
                    assertEquals(9, session.snapshot().column)
                    assertTrue(session.clearBuffer())
                    val blank = session.snapshot()
                    assertTrue(blank.text.isBlank())
                    assertTrue(blank.flags.all { it and (TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER) == 0 })
                    session.feed("X")
                    assertEquals(0, session.snapshot().row)
                    assertEquals('X', session.snapshot().text[9])
                }
        }

    @ParameterizedTest
    @ValueSource(ints = [47, 1047, 1049])
    fun `alternate clear preserves primary content`(mode: Int) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2, 10),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 2)
                    session.feed("one\r\ntwo\r\nthree")
                    val primary = session.snapshot()
                    session.feed("\u001b[?" + mode + "halt")
                    assertTrue(session.clearBuffer())
                    assertEquals(TerminalRenderBufferKind.ALTERNATE, session.snapshot().buffer)
                    assertTrue(session.snapshot().text.isBlank())
                    session.feed("\u001b[?" + mode + "l")
                    assertEquals(primary, session.snapshot())
                }
        }

    @Test
    fun `clear preserves incomplete parser input and cannot restore erased graphemes`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2, 10),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 2)
                    session.feed("a")
                    assertTrue(session.clearBuffer())
                    session.feed("\u0301")
                    assertTrue(session.snapshot().text.isBlank())
                    session.feed("\u001b[2;")
                    assertTrue(session.clearBuffer())
                    session.feed("4HX")
                    assertEquals('X', session.snapshot().text[13])
                    val wide = "界".toByteArray()
                    session.onBytes(wide, 0, 1)
                    assertTrue(session.clearBuffer())
                    session.onBytes(wide, 1, 2)
                    assertEquals('界', session.snapshot().text[14])
                }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `clear before start works and closed clear preserves final output`(remote: Boolean) =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 2, 10),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                assertTrue(session.clearBuffer())
                assertEquals(0, connector.startCount)
                session.start(10, 2)
                session.feed("retained")
                if (remote) connector.simulateClosed(0) else session.close()
                val before = session.snapshot()
                val generation = session.renderGeneration.value
                assertFalse(session.clearBuffer())
                assertEquals(before, session.snapshot())
                assertEquals(generation, session.renderGeneration.value)
            }
        }

    @Test
    fun `closure claimed before clear admission leaves output unchanged`() =
        runTest {
            val recorded = MockConnector()
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val connector =
                object : TerminalConnector by recorded {
                    override fun close() {
                        closeEntered.countDown()
                        check(releaseClose.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        recorded.close()
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 2),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.start(10, 2)
            session.feed("retained")
            val before = session.snapshot()
            SessionTestThread("close") { session.close() }.use { closer ->
                try {
                    assertTrue(closeEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertFalse(session.clearBuffer())
                    assertEquals(before, session.snapshot())
                } finally {
                    releaseClose.countDown()
                }
                closer.awaitCompletion()
            }
        }

    @Test
    fun `admitted clear orders later output and finishes during closure`() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recorded = MockConnector()
            val closeEntered = CountDownLatch(1)
            val connector =
                object : TerminalConnector by recorded {
                    override fun close() {
                        recorded.close()
                        closeEntered.countDown()
                    }
                }
            val integration =
                object : EmptyIntegration() {
                    override fun bufferCleared(buffer: TerminalRenderBufferKind) {
                        entered.countDown()
                        check(release.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 2),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory { integration },
                )
            session.start(10, 2)
            session.feed("old")
            try {
                SessionTestThread("clear") { assertTrue(session.clearBuffer()) }.use { clearer ->
                    try {
                        assertTrue(entered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        SessionTestThread("later-output") { session.feed("late") }.use { output ->
                            output.awaitBlockedBy(clearer)
                            SessionTestThread("close") { session.close() }.use { closer ->
                                try {
                                    assertTrue(closeEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                    closer.awaitBlockedBy(clearer)
                                } finally {
                                    release.countDown()
                                }
                                clearer.awaitCompletion()
                                output.awaitCompletion()
                                closer.awaitCompletion()
                            }
                        }
                    } finally {
                        release.countDown()
                    }
                }
                assertTrue(session.snapshot().text.isBlank())
                assertFalse(session.clearBuffer())
            } finally {
                release.countDown()
                session.close()
            }
        }

    @Test
    fun `clear waits for admitted output and later output starts on the blank grid`() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var first = true
            val integration =
                object : EmptyIntegration() {
                    override fun outputProcessed() {
                        if (first) {
                            first = false
                            entered.countDown()
                            check(release.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory { integration },
                ).use { session ->
                    session.start(10, 2)
                    SessionTestThread("output") { session.feed("old") }.use { output ->
                        try {
                            assertTrue(entered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            SessionTestThread("clear") { assertTrue(session.clearBuffer()) }.use { clearer ->
                                try {
                                    clearer.awaitBlockedBy(output)
                                } finally {
                                    release.countDown()
                                }
                                output.awaitCompletion()
                                clearer.awaitCompletion()
                            }
                        } finally {
                            release.countDown()
                        }
                    }
                    session.feed("new")
                    assertEquals("   new", session.snapshot().text.trimEnd())
                }
        }

    @Test
    fun `host models retain ownership and old conditional edits become stale`() =
        runTest {
            val state = TerminalShellIntegrationState()
            val command = TerminalShellCommandLineState()
            command.value = TerminalShellCommandLineSnapshot("old", 3, 3, 0)
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory.host(state, command),
                ).use { session ->
                    session.start(10, 2)
                    session.feed("old")
                    var oldId = 0L
                    session.readRenderFrame { oldId = it.lineId(0) }
                    state.recordPromptStart(oldId)
                    val expected = requireNotNull(session.captureCommandEdit())
                    assertTrue(session.clearBuffer())
                    assertEquals(TerminalInputAdmission.STALE_CONTEXT, session.submitInput(expected, emptyList()))
                    assertEquals(command.value, session.activeShellCommandLine())
                    assertEquals(1, state.recordCount())
                    session.readRenderFrame { frame ->
                        assertTrue((0 until frame.rows).none { frame.lineId(it) == oldId })
                    }
                }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `producer failure preserves committed clear and permits later output`(cancelled: Boolean) =
        runTest {
            val failure =
                if (cancelled) {
                    java.util.concurrent.CancellationException(
                        "producer cancellation",
                    )
                } else {
                    IllegalArgumentException("producer failure")
                }
            lateinit var session: TerminalSession
            val integration =
                object : EmptyIntegration() {
                    override fun bufferCleared(buffer: TerminalRenderBufferKind) {
                        assertThrows(IllegalStateException::class.java) { session.clearBuffer() }
                        throw failure
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 2),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    shellIntegration = TerminalShellIntegrationFactory { integration },
                )
            session.use {
                session.start(10, 2)
                session.feed("old")
                assertSame(failure, assertThrows(RuntimeException::class.java) { session.clearBuffer() })
                assertTrue(session.snapshot().text.isBlank())
                session.feed("new")
                assertTrue(session.snapshot().text.contains("new"))
                testScheduler.runCurrent()
            }
        }

    private open class EmptyIntegration : TerminalShellIntegration {
        override val state = TerminalShellIntegrationState()
        override val promptReady = MutableStateFlow(false)
        override val commandLineChanges = emptyFlow<Long>()

        override fun activeCommandLine(): TerminalShellCommandLineSnapshot? = null
    }

    private fun TerminalSession.feed(text: String) {
        val bytes = text.toByteArray()
        onBytes(bytes, 0, bytes.size)
    }

    private fun TerminalSession.snapshot(): Snapshot {
        var result: Snapshot? = null
        readRenderFrame { frame ->
            val codes = IntArray(frame.columns)
            val attrs = LongArray(frame.columns)
            val flags = IntArray(frame.columns)
            val allAttrs = mutableListOf<Long>()
            val allFlags = mutableListOf<Int>()
            val text = StringBuilder()
            repeat(frame.rows) { row ->
                frame.copyLine(row, codes, attrWords = attrs, flags = flags)
                for (column in codes.indices) {
                    text.append(if (codes[column] > 0) codes[column].toChar() else ' ')
                    allAttrs.add(attrs[column])
                    allFlags.add(flags[column])
                }
            }
            result =
                Snapshot(
                    text.toString(),
                    frame.cursor.column,
                    frame.cursor.row,
                    frame.historySize,
                    frame.activeBuffer,
                    List(frame.rows) { frame.lineId(it) },
                    frame.historyContentGeneration,
                    allAttrs,
                    allFlags,
                )
        }
        return requireNotNull(result)
    }

    private data class Snapshot(
        val text: String,
        val column: Int,
        val row: Int,
        val history: Int,
        val buffer: TerminalRenderBufferKind,
        val lineIds: List<Long>,
        val historyGeneration: Long,
        val attributes: List<Long>,
        val flags: List<Int>,
    )
}
