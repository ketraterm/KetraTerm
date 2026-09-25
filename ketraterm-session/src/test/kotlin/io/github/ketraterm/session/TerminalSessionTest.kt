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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.host.*
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.*
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    @Test
    fun `large clipboard writes follow session permissions through real session parsing`() =
        runTest {
            val text = "é🙂".repeat(1024)
            val bytes = ("\u001B]52;c;" + Base64.getEncoder().encodeToString(text.encodeToByteArray()) + "\u001B\\").ascii()
            for (permission in TerminalClipboardPermission.entries) {
                val connector = MockConnector()
                val events = RecordingHostEvents()
                val writeAllowed =
                    permission == TerminalClipboardPermission.ALLOW
                val policy =
                    TerminalClipboardPolicy(
                        writePermission = permission,
                    )
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        events,
                        HostPolicy(clipboardPolicy = policy),
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                    ).use { session ->
                        session.start(10, 3)
                        for (offset in bytes.indices step 511) {
                            connector.feedFromHost(bytes, offset, minOf(511, bytes.size - offset))
                        }
                        when {
                            permission == TerminalClipboardPermission.PROMPT -> {
                                assertEquals(text, events.clipboardPrompts.single().text)
                                assertTrue(events.clipboardWrites.isEmpty())
                                assertEquals(
                                    TerminalClipboardDecision.PROMPT_REQUIRED,
                                    events.clipboardAudits.single().decision,
                                )
                            }
                            writeAllowed -> {
                                assertEquals(text, events.clipboardWrites.single().text)
                                assertTrue(events.clipboardPrompts.isEmpty())
                                assertEquals(
                                    TerminalClipboardDecision.ALLOWED_BY_POLICY,
                                    events.clipboardAudits.single().decision,
                                )
                            }
                            else -> {
                                assertTrue(events.clipboardAudits.isEmpty())
                                assertTrue(events.clipboardWrites.isEmpty())
                                assertTrue(events.clipboardPrompts.isEmpty())
                            }
                        }
                        assertTrue(connector.writtenBytes.isEmpty())
                    }
            }
        }

    @Test
    fun `clipboard decoded boundary is enforced before callbacks and malformed large transfers recover`() =
        runTest {
            val connector = MockConnector()
            val events = RecordingHostEvents()
            val policy =
                HostPolicy(
                    clipboardPolicy =
                        TerminalClipboardPolicy(
                            writePermission = TerminalClipboardPermission.ALLOW,
                            maxDecodedBytes = 8192,
                        ),
                )
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    events,
                    policy,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                ).use { session ->
                    session.start(10, 3)
                    for (count in intArrayOf(8191, 8192, 8193, 8194)) {
                        val encoded = Base64.getEncoder().encodeToString(ByteArray(count) { 'a'.code.toByte() })
                        connector.feedFromHost("\u001B]52;c;$encoded\u0007".ascii())
                    }
                    assertEquals(listOf("a".repeat(8191), "a".repeat(8192)), events.clipboardWrites.map { it.text })
                    // 8193 shares a Base64-size bucket with 8192; 8194 exceeds the parser's encoded budget.
                    assertEquals(
                        listOf(
                            TerminalClipboardDecision.ALLOWED_BY_POLICY,
                            TerminalClipboardDecision.ALLOWED_BY_POLICY,
                            TerminalClipboardDecision.DENIED_PAYLOAD_TOO_LARGE,
                        ),
                        events.clipboardAudits.map { it.decision },
                    )
                    val invalidUtf8 = ByteArray(6000) { 'a'.code.toByte() }.also { it[it.lastIndex] = 0xFF.toByte() }
                    for (encoded in listOf(Base64.getEncoder().encodeToString(invalidUtf8), "YWFh".repeat(1500) + "!")) {
                        connector.feedFromHost("\u001B]52;c;$encoded\u0007".ascii())
                        assertEquals(TerminalClipboardDecision.DENIED_MALFORMED_PAYLOAD, events.clipboardAudits.last().decision)
                    }
                    assertEquals(2, events.clipboardWrites.size)
                    connector.feedFromHost("\u001B]52;c;Yg\u0007\u001B]52;c;\u0007".ascii())
                    assertEquals(listOf("b", ""), events.clipboardWrites.takeLast(2).map { it.text })
                    assertTrue(events.clipboardPrompts.isEmpty())
                }
        }

    @Test
    fun `inflight clipboard writes recheck changed permissions and decoded limits`() =
        runTest {
            val original =
                HostPolicy(
                    clipboardPolicy =
                        TerminalClipboardPolicy(
                            writePermission = TerminalClipboardPermission.ALLOW,
                            maxDecodedBytes = 16384,
                        ),
                )
            val denied = original.clipboardPolicy.copy(writePermission = TerminalClipboardPermission.DENY)
            val changes =
                listOf(
                    original.copy(clipboardPolicy = denied) to
                        TerminalClipboardDecision.DENIED_BY_POLICY,
                    original.copy(clipboardPolicy = original.clipboardPolicy.copy(maxDecodedBytes = 4096)) to
                        TerminalClipboardDecision.DENIED_PAYLOAD_TOO_LARGE,
                )
            for ((updated, expected) in changes) {
                val connector = MockConnector()
                val events = RecordingHostEvents()
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        events,
                        original,
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                    ).use { session ->
                        session.start(10, 3)
                        connector.feedFromHost(("\u001B]52;c;" + "YWFh".repeat(2048)).ascii())
                        session.setHostPolicy(updated)
                        connector.feedFromHost("\u0007".ascii())
                        assertEquals(expected, events.clipboardAudits.single().decision)
                        assertTrue(events.clipboardWrites.isEmpty())
                        assertTrue(events.clipboardPrompts.isEmpty())
                        session.setHostPolicy(original)
                        connector.feedFromHost(("\u001B]52;c;" + "YWFh".repeat(2048) + "\u0007").ascii())
                        assertEquals("aaa".repeat(2048), events.clipboardWrites.single().text)
                    }
            }
        }

    @Test
    fun `default clipboard budget accepts one MiB and session shutdown discards unfinished large writes`() =
        runTest {
            for (shutdown in listOf("local", "remote", "error")) {
                val connector = MockConnector()
                val events = RecordingHostEvents()
                val text = "a".repeat(1024 * 1024)
                val bytes = ("\u001B]52;c;" + Base64.getEncoder().encodeToString(text.ascii()) + "\u001B\\").ascii()
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        events,
                        HostPolicy(clipboardPolicy = TerminalClipboardPolicy(writePermission = TerminalClipboardPermission.ALLOW)),
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                    ).use { session ->
                        session.start(10, 3)
                        for (offset in bytes.indices step 8191) {
                            connector.feedFromHost(bytes, offset, minOf(8191, bytes.size - offset))
                        }
                        assertEquals(text, events.clipboardWrites.single().text)
                        connector.feedFromHost(bytes, 0, bytes.size - 2)
                        when (shutdown) {
                            "local" -> session.close()
                            "remote" -> connector.simulateClosed(0)
                            else -> connector.simulateCrash(IllegalStateException("transport failed"))
                        }
                        assertEquals(1, events.clipboardWrites.size)
                        assertEquals(1, events.clipboardAudits.size)
                        assertTrue(session.isClosed)
                    }
            }
        }

    @Test
    fun `published cursor presentation follows application style and restores host defaults`() =
        runTest {
            val connector = MockConnector()
            TerminalSession
                .create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                ).use { session ->
                    session.setCursorShape(TerminalRenderCursorShape.BAR)
                    session.start(10, 3)
                    connector.feedFromHost("\u001B[?1049h\u001B[2 q".ascii())
                    session.requestRender(0)
                    runCurrent()
                    val alternate = requireNotNull(session.renderPublisher.current())
                    assertEquals(TerminalRenderCursorShape.BLOCK, alternate.cursorShape)
                    assertFalse(alternate.cursorBlinking)
                    val generation = session.renderGeneration.value

                    connector.feedFromHost("\u001B[?1049l".ascii())
                    session.requestRender(0)
                    advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                    runCurrent()
                    val primary = requireNotNull(session.renderPublisher.current())
                    assertTrue(session.renderGeneration.value > generation)
                    assertEquals(TerminalRenderCursorShape.BAR, primary.cursorShape)
                    assertTrue(primary.cursorBlinking)

                    connector.feedFromHost("\u001B[2 q\u001B[0 q".ascii())
                    session.requestRender(0)
                    advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                    runCurrent()
                    val reset = requireNotNull(session.renderPublisher.current())
                    assertEquals(TerminalRenderCursorShape.BAR, reset.cursorShape)
                    assertTrue(reset.cursorBlinking)
                }
        }

    @Test
    fun `accepted DECCOLM synchronizes connector and core before following output and queries`() {
        val stream = "old\u001B[?3hwide\u001B[18t\u001B[?3lnarrow\u001B[18t"
        for (split in 0..stream.length) {
            val connector = MockConnector()
            val requests = mutableListOf<Pair<Int, Int>>()
            val events =
                object : HostEventSink by HostEventSink.NONE {
                    override fun requestColumnMode(
                        rows: Int,
                        columns: Int,
                    ): Boolean {
                        requests += columns to rows
                        return true
                    }
                }
            val session = createStartedSession(connector, columns = 80, rows = 3, hostEvents = events)
            try {
                connector.feedFromHost(stream.take(split).ascii())
                connector.feedFromHost(stream.drop(split).ascii())
                assertEquals(listOf(132 to 3, 80 to 3), requests)
                assertEquals(listOf(80 to 3, 132 to 3, 80 to 3), connector.resizeCalls)
                assertEquals("\u001B[8;3;132t\u001B[8;3;80t", connector.writtenBytes.asciiText())
                assertEquals(80, session.terminal.width)
                assertEquals("narrow", session.terminal.getLineAsString(0))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `denied and unhandled DECCOLM never resize the connector`() {
        for (policy in HostControlPolicy.entries) {
            val connector = MockConnector()
            val session =
                createStartedSession(connector, columns = 90, rows = 3, hostPolicy = HostPolicy(windowManipulationPolicy = policy))
            try {
                connector.feedFromHost("keep\u001B[?3h\u001B[?3l".ascii())
                assertEquals(listOf(90 to 3), connector.resizeCalls)
                assertEquals(90, session.terminal.width)
                assertEquals("keep", session.terminal.getLineAsString(0))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `connector resize failure does not apply destructive DECCOLM reset`() {
        val delegate = MockConnector()
        val failure = IllegalStateException("resize failed")
        val connector =
            object : TerminalConnector by delegate {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    if (columns == 132) throw failure
                    delegate.resize(columns, rows)
                }
            }
        val terminal = TerminalBuffers.create(width = 80, height = 3)
        val events =
            object : HostEventSink by HostEventSink.NONE {
                override fun requestColumnMode(
                    rows: Int,
                    columns: Int,
                ): Boolean = true
            }
        val session = TerminalSession.create(terminal, connector, hostEvents = events)
        try {
            session.start(80, 3)
            delegate.feedFromHost("keep".ascii())
            assertSame(failure, assertThrows(IllegalStateException::class.java) { delegate.feedFromHost("\u001B[?3h".ascii()) })
            assertEquals(80, terminal.width)
            assertEquals("keep", terminal.getLineAsString(0))
        } finally {
            session.close()
        }
    }

    @Test
    fun `color scheme replies track live host theme and policy updates through transport`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        try {
            connector.feedFromHost("\u001B[?996n".ascii())
            session.setThemePalette(
                TerminalColorPalette(defaultForeground = 0xff000000.toInt(), defaultBackground = 0xffffffff.toInt(), isDark = false),
            )
            assertEquals("\u001B[?997;1n", connector.writtenBytes.asciiText())
            connector.feedFromHost("\u001B]11;#000000\u0007\u001B[?996n".ascii())
            assertEquals("\u001B[?997;1n\u001B[?997;2n", connector.writtenBytes.asciiText())

            session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
            connector.feedFromHost("\u001B[?996n".ascii())
            session.setThemePalette(TerminalColorPalette())
            session.setHostPolicy(HostPolicy())
            connector.feedFromHost("\u001B[?996n\u001B[5n".ascii())
            assertEquals("\u001B[?997;1n\u001B[?997;2n\u001B[?997;1n\u001B[0n", connector.writtenBytes.asciiText())
        } finally {
            session.close()
        }
    }

    @Test
    fun `DSR CSI 5 n replies OK status`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[5n".ascii())

        assertEquals("\u001B[0n", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `rich host Kitty capability reaches parser host core response pipeline`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session =
            TerminalSession.create(
                terminal = terminal,
                connector = connector,
                kittyKeyboardSupportedFlags = KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK,
            )
        session.start(columns = 10, rows = 3)

        connector.feedFromHost("\u001B[=31u\u001B[?u".ascii())

        assertEquals("\u001B[?31u", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `CPR CSI 6 n replies one-based cursor position`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[2;3H\u001B[6n".ascii())

        assertEquals("\u001B[2;3R", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `close does not set exitCode to zero`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()

        assertNull(session.exitCode)
        assertEquals(1, connector.closeCount)
    }

    @Test
    fun `lifecycle state is retained from creation through closure`() =
        runTest {
            val connector = MockConnector()
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val session =
                TerminalSession.create(
                    terminal = terminal,
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )

            assertSame(TerminalSessionState.Created, session.state.value)

            session.start(columns = 10, rows = 3)
            assertSame(TerminalSessionState.Running, session.state.value)

            session.close()
            val closed = session.state.value as TerminalSessionState.Closed
            assertTrue(closed.event.locallyRequested)
            assertEquals(closed, session.state.first())
            assertFalse(session.isCoroutineScopeActive)
        }

    @Test
    fun `remote close records exit code`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.simulateClosed(7)

        assertEquals(7, session.exitCode)
        assertEquals(0, connector.closeCount)
        assertEquals(
            TerminalSessionState.Closed(
                TerminalSessionCloseEvent(exitCode = 7, failure = null, locallyRequested = false),
            ),
            session.state.value,
        )
    }

    @Test
    fun `remote error records failure and does not fake exit code`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        val failure = IllegalStateException("transport failed")

        connector.simulateCrash(failure)

        assertEquals(failure, session.failure)
        assertNull(session.exitCode)
        assertEquals(0, connector.closeCount)
        assertEquals(
            TerminalSessionState.Closed(
                TerminalSessionCloseEvent(exitCode = null, failure = failure, locallyRequested = false),
            ),
            session.state.value,
        )
    }

    @Test
    fun `onClosed does not recursively close connector`() {
        val connector = MockConnector()
        createStartedSession(connector)

        connector.simulateClosed(7)

        assertEquals(0, connector.closeCount)
    }

    @Test
    fun `local cleanup after remote close does not emit duplicate close event`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.simulateClosed(1)
        session.close()

        assertEquals(
            TerminalSessionState.Closed(
                TerminalSessionCloseEvent(exitCode = 1, failure = null, locallyRequested = false),
            ),
            session.state.value,
        )
    }

    @Test
    fun `input key writes through connector`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))

        assertEquals("a", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `text-only commits follow negotiated keyboard modes through the connector`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        try {
            session.encodeKey(TerminalKeyEvent.text("\u00e9"))
            connector.feedFromHost("\u001B[>1u".ascii())
            session.encodeKey(TerminalKeyEvent.text("\u4e2d"))
            connector.feedFromHost("\u001B[>8u".ascii())
            session.encodeKey(TerminalKeyEvent.text("suppressed without associated text reporting"))
            connector.feedFromHost("\u001B[<u".ascii())
            session.encodeKey(TerminalKeyEvent.text("\uD83D\uDE00"))
            connector.feedFromHost("\u001B[<u".ascii())
            session.encodeKey(TerminalKeyEvent.text("e\u0301", type = TerminalKeyEventType.REPEAT))
            session.encodeKey(TerminalKeyEvent.text("x", type = TerminalKeyEventType.RELEASE))
            assertArrayEquals("\u00e9\u4e2d\uD83D\uDE00e\u0301".encodeToByteArray(), connector.writtenBytes)
        } finally {
            session.close()
        }
    }

    @Test
    fun `ctrl L input writes form feed clear screen request`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.encodeKey(TerminalKeyEvent.codepoint('L'.code, TerminalModifiers.CTRL))

        assertArrayEquals(byteArrayOf(0x0c), connector.writtenBytes)
        session.close()
    }

    @Test
    fun `text replacement writes delete backspace and paste in order`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.encodeTextReplacement(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 1,
                deleteBeforeCursorCount = 2,
                replacementText = "status",
            ),
        )

        assertArrayEquals("\u001B[3~\u007F\u007Fstatus".ascii(), connector.writtenBytes)
        session.close()
    }

    @Test
    fun `text replacement does not interleave with concurrent input`() {
        lateinit var session: TerminalSession
        val connector =
            ReplacementInterleavingConnector {
                SessionTestThread("terminal-session-replacement-ordering-test") {
                    session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                }
            }
        session = createStartedSession(connector)
        session.use {
            session.encodeTextReplacement(
                TerminalTextReplacementEvent(
                    deleteAfterCursorCount = 1,
                    deleteBeforeCursorCount = 1,
                    replacementText = "status",
                ),
            )

            connector.awaitWrites()
            assertArrayEquals("\u001B[3~\u007Fstatusa".ascii(), connector.writtenBytes)
        }
    }

    @Test
    fun `local close emits local lifecycle event once`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()
        session.close()

        assertTrue(session.isClosed)
        assertEquals(
            TerminalSessionState.Closed(
                TerminalSessionCloseEvent(exitCode = null, failure = null, locallyRequested = true),
            ),
            session.state.value,
        )
    }

    @Test
    fun `response write and key write do not interleave`() {
        lateinit var session: TerminalSession
        val connector =
            SlowFirstWriteConnector {
                SessionTestThread("terminal-session-ordering-test") {
                    session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                }
            }
        session = createStartedSession(connector)
        session.use {
            connector.feedFromHost("\u001B[5n".ascii())

            connector.awaitWrites()
            assertEquals("\u001B[0na", connector.writtenBytes.asciiText())
        }
    }

    @Test
    fun `start can only be called once`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        val error =
            assertThrows(IllegalStateException::class.java) {
                session.start(columns = 10, rows = 3)
            }

        assertEquals("session already started or closed", error.message)
        assertEquals(1, connector.startCount)
        session.close()
    }

    @Test
    fun `input before start is ignored`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session = TerminalSession.create(terminal, connector)

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
        session.encodeTextReplacement(TerminalTextReplacementEvent(1, 1, "text"))

        assertEquals("", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `onBytes rejects invalid ranges`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        val bytes = ByteArray(4)

        assertThrows(IllegalArgumentException::class.java) {
            session.onBytes(bytes, offset = 3, length = 2)
        }

        session.close()
    }

    @Test
    fun `mock connector ignores writes and host feed after local close`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()
        connector.write("a".ascii())

        assertEquals("", connector.writtenBytes.asciiText())
        assertThrows(IllegalStateException::class.java) {
            connector.feedFromHost("b".ascii())
        }
    }

    @Test
    fun `resize mutates core and calls connector resize`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 3)

        val resized = session.resize(columns = 20, rows = 5)

        assertEquals(0 to 0, resized)
        assertEquals(20, session.terminal.width)
        assertEquals(5, session.terminal.height)
        assertEquals(listOf(10 to 3, 20 to 5), connector.resizeCalls)
        session.close()
    }

    @ParameterizedTest
    @CsvSource("false, 8, 3", "false, 8, 4", "true, 8, 3", "true, 8, 4")
    fun `resize tolerates a stale primary anchor before alternate publication`(
        usePairResult: Boolean,
        columns: Int,
        rows: Int,
    ) = runTest {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 16)
        val session =
            TerminalSession.create(
                terminal,
                connector,
                workerDispatcher = StandardTestDispatcher(testScheduler),
            )
        session.start(columns = 8, rows = 3)
        try {
            connector.feedFromHost((0..6).joinToString("\r\n") { "row$it" }.ascii())
            runCurrent()
            val beforeResizeGeneration = session.renderGeneration.value
            assertEquals(TerminalRenderBufferKind.PRIMARY, session.renderPublisher.current()?.activeBuffer)
            assertEquals(4, session.renderPublisher.current()?.historySize)

            connector.feedFromHost("\u001B[?1049h".ascii())
            assertEquals(TerminalRenderBufferKind.PRIMARY, session.renderPublisher.current()?.activeBuffer)

            if (usePairResult) {
                assertEquals(0 to 0, session.resize(columns, rows, oldScrollbackOffset = 2))
            } else {
                assertEquals(
                    TerminalViewportResizeResult(scrollbackOffset = 0, historySize = 0, discardedCount = 0L),
                    session.resizeViewport(columns, rows, oldScrollbackOffset = 2),
                )
            }

            assertEquals(columns, terminal.width)
            assertEquals(rows, terminal.height)
            assertEquals(listOf(8 to 3, columns to rows), connector.resizeCalls)
            advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
            runCurrent()
            assertTrue(session.renderGeneration.value > beforeResizeGeneration)
            val published = requireNotNull(session.renderPublisher.current())
            assertEquals(TerminalRenderBufferKind.ALTERNATE, published.activeBuffer)
            assertEquals(columns, published.columns)
            assertEquals(rows, published.rows)
            assertEquals(0, published.scrollbackOffset)
            assertEquals(0, published.historySize)
        } finally {
            session.close()
        }
    }

    @Test
    fun `viewport resize captures the new discarded baseline after bounded history reflow`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 4)
        val session = TerminalSession.create(terminal, connector)
        session.start(columns = 8, rows = 3)
        try {
            connector.feedFromHost((0..6).joinToString("\r\n") { "row${it.toString().repeat(5)}" }.ascii())
            session.readRenderFrame { frame ->
                assertEquals(4, frame.historySize)
                assertEquals(0L, frame.discardedCount)
            }

            val resized = session.resizeViewport(columns = 4, rows = 3, oldScrollbackOffset = 3)

            assertTrue(resized.scrollbackOffset > 0, "Reflow must exercise a retained scrollback anchor")
            assertEquals(4, resized.historySize)
            assertTrue(resized.discardedCount > 0L, "Narrowing must discard reflowed rows at the history limit")
            session.readRenderFrame(resized.scrollbackOffset) { frame ->
                assertEquals(4, frame.columns)
                assertEquals(resized.scrollbackOffset, frame.scrollbackOffset)
                assertEquals(resized.historySize, frame.historySize)
                assertEquals(resized.discardedCount, frame.discardedCount)
            }
            assertEquals(listOf(8 to 3, 4 to 3), connector.resizeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun `viewport resize baseline excludes output produced by connector notification`() {
        val backingConnector = MockConnector()
        val connector =
            object : TerminalConnector by backingConnector {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    backingConnector.resize(columns, rows)
                    if (columns == 4) backingConnector.feedFromHost("\r\nlate".ascii())
                }
            }
        val terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 4)
        val session = TerminalSession.create(terminal, connector)
        session.start(columns = 8, rows = 3)
        try {
            backingConnector.feedFromHost((0..6).joinToString("\r\n") { "row${it.toString().repeat(5)}" }.ascii())

            val resized = session.resizeViewport(columns = 4, rows = 3, oldScrollbackOffset = 3)

            assertTrue(resized.discardedCount > 0L)
            session.readRenderFrame { frame ->
                assertEquals(resized.historySize, frame.historySize)
                assertEquals(resized.discardedCount + 1L, frame.discardedCount)
            }
            assertEquals("late", session.terminal.getLineAsString(2))
            assertEquals(listOf(8 to 3, 4 to 3), backingConnector.resizeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun `ambiguous width policy applies to future host writes`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 6, rows = 2)

        session.setTreatAmbiguousAsWide(true)
        connector.feedFromHost("\u20ACX".toByteArray(StandardCharsets.UTF_8))

        assertAll(
            { assertEquals(0x20AC, session.terminal.getCodepointAt(0, 0)) },
            { assertEquals(-1, session.terminal.getCodepointAt(1, 0)) },
            { assertEquals('X'.code, session.terminal.getCodepointAt(2, 0)) },
            { assertTrue(session.terminal.getModeSnapshot().treatAmbiguousAsWide) },
        )
        session.close()
    }

    @Test
    fun `bytes are consumed synchronously before callback returns`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        val bytes = "hello\u001B[5n".ascii()

        connector.feedFromHost(bytes)
        bytes.fill('?'.code.toByte())

        assertEquals("hello", session.terminal.getLineAsString(0))
        assertEquals("\u001B[0n", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `OSC 133 markers populate shared shell integration state`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007prompt> \u001B]133;B\u0007\u001B]133;C\u0007run\r\nfailed\u001B]133;D;2\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 prompt marker skips leading blank layout row in multiline Bash prompt`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007\r\ngagik@host MINGW64 ~\r\n$ \u001B]133;B\u0007".ascii(),
        )

        val decorations = session.shellDecorations()
        assertAll(
            { assertFalse(decorations.promptStarts[0]) },
            { assertTrue(decorations.promptStarts[1]) },
            { assertFalse(decorations.promptStarts[2]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 prompt marker remains on first row when prompt content begins there`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007gagik@host\r\n$ \u001B]133;B\u0007".ascii(),
        )

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
            { assertFalse(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 empty prompt span preserves original marker anchor`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007\r\n\u001B]133;B\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
            { assertFalse(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 7 updates session directory and OSC 133 snapshots it onto the command`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]7;file:///workspace/My%20Project\u001B\\".ascii(),
        )
        connector.feedFromHost(
            "\u001B]133;A\u0007PS> \u001B]133;B\u0007build\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertAll(
            { assertEquals("file:///workspace/My%20Project", session.currentWorkingDirectoryUri()) },
            {
                assertEquals(
                    "file:///workspace/My%20Project",
                    session.shellIntegrationState.commandWorkingDirectoryUri(recordId),
                )
            },
        )
        session.close()
    }

    @Test
    fun `OSC 52 audit and allowed write callbacks are forwarded through session wrapper`() {
        val connector = MockConnector()
        val events = RecordingHostEvents()
        val session =
            createStartedSession(
                connector = connector,
                hostEvents = events,
                hostPolicy =
                    HostPolicy(
                        clipboardPolicy =
                            TerminalClipboardPolicy(
                                writePermission = TerminalClipboardPermission.ALLOW,
                            ),
                    ),
            )

        connector.feedFromHost("\u001B]52;c;SGVsbG8=\u0007".ascii())

        assertAll(
            { assertEquals(1, events.clipboardAudits.size) },
            { assertEquals("Hello", events.clipboardWrites.single().text) },
            { assertEquals(events.clipboardAudits.single(), events.clipboardWrites.single().audit) },
        )
        session.close()
    }

    @Test
    fun `OSC 52 prompt callback is forwarded through session wrapper`() {
        val connector = MockConnector()
        val events = RecordingHostEvents()
        val session =
            createStartedSession(
                connector = connector,
                hostEvents = events,
                hostPolicy =
                    HostPolicy(
                        clipboardPolicy =
                            TerminalClipboardPolicy(
                                writePermission = TerminalClipboardPermission.PROMPT,
                            ),
                    ),
            )

        connector.feedFromHost("\u001B]52;c;SGVsbG8=\u0007".ascii())

        assertAll(
            { assertEquals(1, events.clipboardAudits.size) },
            { assertEquals("Hello", events.clipboardPrompts.single().text) },
            { assertEquals(events.clipboardAudits.single(), events.clipboardPrompts.single().audit) },
            { assertTrue(events.clipboardWrites.isEmpty()) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start captures same line command text after prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\u001B]133;C\u0007\r\nok\u001B]133;D;0\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[0]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertEquals("git status", session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 active command line captures visible text before command start`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status".ascii())

        assertEquals(
            TerminalShellCommandLineSnapshot(
                commandText = "git status",
                cursorOffset = "git status".length,
                cursorColumn = "PS> git status".length,
                cursorRow = 0,
            ),
            session.activeShellCommandLine(),
        )
        session.close()
    }

    @Test
    fun `OSC 133 active command line is unavailable after command start`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\u001B]133;C\u0007".ascii())

        assertNull(session.activeShellCommandLine())
        session.close()
    }

    @Test
    fun `OSC 133 active command line is unavailable without prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> git status".ascii())

        assertNull(session.activeShellCommandLine())
        session.close()
    }

    @Test
    fun `OSC 133 active command line is unavailable when cursor is before visible command end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\u001B[2D".ascii())

        assertNull(session.activeShellCommandLine())
        session.close()
    }

    @Test
    fun `OSC 133 active command line reads bounded scrollback when prompt moved above live rows`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 2)

        connector.feedFromHost("\u001B]133;A\u0007P> \u001B]133;B\u0007one\r\ntwo\r\nthree".ascii())

        assertEquals(
            TerminalShellCommandLineSnapshot(
                commandText = "one\ntwo\nthree",
                cursorOffset = "one\ntwo\nthree".length,
                cursorColumn = "three".length,
                cursorRow = 2,
            ),
            session.activeShellCommandLine(),
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start captures previous line command text at column zero`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git status\r\n\u001B]133;C\u0007output\u001B]133;D;1\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[1]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertEquals("git status", session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start preserves hard line breaks in multiline command text`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007PS> \u001B]133;B\u0007echo first\r\nsecond\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[1]
        assertEquals("echo first\nsecond", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start joins soft wrapped command rows without a newline`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007P> \u001B]133;B\u0007abcdefghijk\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertEquals("abcdefghijk", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start preserves argument separator at soft wrap`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo hello\u001B]133;C\u0007".ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo hello", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves a soft wrapped row containing only spaces`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            val command = "echo" + " ".repeat(9) + "hello"
            connector.feedFromHost(
                ("\u001B]133;A\u0007P> \u001B]133;B\u0007" + command + "\u001B]133;C\u0007").ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo" + " ".repeat(9) + "hello", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves wrapped argument separators after prompt enters scrollback`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 2).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo first second third\u001B]133;C\u0007".ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[1]
            assertEquals("echo first second third", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves spaces before wide glyph wrap padding`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 10, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo  \u754C\u001B]133;C\u0007"
                    .toByteArray(StandardCharsets.UTF_8),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo  \u754C", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves spaces before grapheme cluster wrap padding`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 10, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echo  \uD83D\uDC69\u200D\uD83D\uDCBB\u001B]133;C\u0007"
                    .toByteArray(StandardCharsets.UTF_8),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo  \uD83D\uDC69\u200D\uD83D\uDCBB", session.shellIntegrationState.commandText(recordId))
        }
    }

    @Test
    fun `OSC 133 command start preserves erased cells at a soft wrap boundary`() {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            connector.feedFromHost(
                "\u001B]133;A\u0007P> \u001B]133;B\u0007echoXhello\u001B[1;8H\u001B[X\u001B[2;6H\u001B]133;C\u0007".ascii(),
            )

            val recordId = session.shellDecorations().commandRecordIds[0]
            assertEquals("echo hello", session.shellIntegrationState.commandText(recordId))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["echo hello", "echo         hello", "echo  \u754C", "echo  \uD83D\uDC69\u200D\uD83D\uDCBB"])
    fun `command text and frame and cache fingerprints agree across soft wrap widths`(command: String) {
        val extractor = ShellIntegrationCommandTextExtractor()
        val boundedExtractor = ShellIntegrationCommandTextExtractor(maxTextLength = command.length - 1)
        var unwrappedFingerprint: LongArray? = null
        for (columns in listOf(80, 8, 10)) {
            val connector = MockConnector()
            createStartedSession(connector, columns = columns, rows = 4).use { session ->
                connector.feedFromHost(
                    ("\u001B]133;A\u0007P> \u001B]133;B\u0007" + command + "\u001B[0m")
                        .toByteArray(StandardCharsets.UTF_8),
                )
                val frameFingerprint = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
                session.readRenderFrame { frame ->
                    val lineId = frame.lineId(0)
                    val cursor = frame.cursor
                    assertEquals(command, extractor.extract(frame, lineId, 3, cursor.row, cursor.column))
                    assertEquals(
                        TerminalShellCommandFingerprintStatus.COMPLETE,
                        extractor.fingerprint(frame, lineId, 3, cursor.row, cursor.column, frameFingerprint),
                    )
                    assertNull(boundedExtractor.extract(frame, lineId, 3, cursor.row, cursor.column))
                    assertEquals(
                        TerminalShellCommandFingerprintStatus.INVALID,
                        boundedExtractor.fingerprint(
                            frame,
                            lineId,
                            3,
                            cursor.row,
                            cursor.column,
                            LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS),
                        ),
                    )
                }
                val cache = TerminalRenderCache(columns, 4).also { it.updateFrom(session) }
                val cacheFingerprint = LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS)
                assertEquals(
                    TerminalShellCommandFingerprintStatus.COMPLETE,
                    extractor.fingerprint(cache, cache.lineIds[0], 3, cache.cursorRow, cache.cursorColumn, cacheFingerprint),
                )
                assertArrayEquals(frameFingerprint, cacheFingerprint)
                assertEquals(command.length.toLong(), frameFingerprint[TERMINAL_SHELL_COMMAND_FINGERPRINT_UTF16_LENGTH_INDEX])
                assertEquals(
                    TerminalShellCommandFingerprintStatus.INVALID,
                    boundedExtractor.fingerprint(
                        cache,
                        cache.lineIds[0],
                        3,
                        cache.cursorRow,
                        cache.cursorColumn,
                        LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS),
                    ),
                )
                if (columns == 80) {
                    unwrappedFingerprint = frameFingerprint
                } else {
                    assertArrayEquals(unwrappedFingerprint, frameFingerprint, "fingerprint at width $columns")
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, TerminalRenderCellFlags.CLUSTER])
    fun `wrapped command fingerprint rejects invalid cell flags or missing cluster data`(invalidFlags: Int) {
        val connector = MockConnector()
        createStartedSession(connector, columns = 8, rows = 4).use { session ->
            connector.feedFromHost("\u001B]133;A\u0007P> \u001B]133;B\u0007echo hello\u001B[0m".ascii())
            val cache = TerminalRenderCache(8, 4).also { it.updateFrom(session) }
            cache.flags[4] = invalidFlags

            assertEquals(
                TerminalShellCommandFingerprintStatus.INVALID,
                ShellIntegrationCommandTextExtractor().fingerprint(
                    cache,
                    cache.lineIds[0],
                    3,
                    cache.cursorRow,
                    cache.cursorColumn,
                    LongArray(TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS),
                ),
            )
        }
    }

    @Test
    fun `OSC 133 command start extracts multiline command whose prompt moved into scrollback`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 2)

        connector.feedFromHost(
            "\u001B]133;A\u0007P> \u001B]133;B\u0007one\r\ntwo\r\nthree\u001B]133;C\u0007".ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[1]
        assertEquals("one\ntwo\nthree", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start preserves grapheme cluster command text`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            "\u001B]133;A\u0007P> \u001B]133;B\u0007e\u0301\u001B]133;C\u0007"
                .toByteArray(StandardCharsets.UTF_8),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertEquals("e\u0301", session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start rejects command text above the retention bound`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 5000, rows = 2)
        val oversizedCommand = "a".repeat(DEFAULT_SHELL_INTEGRATION_COMMAND_TEXT_LENGTH + 1)

        connector.feedFromHost(
            ("\u001B]133;A\u0007P> \u001B]133;B\u0007" + oversizedCommand + "\u001B]133;C\u0007").ascii(),
        )

        val recordId = session.shellDecorations().commandRecordIds[0]
        assertNull(session.shellIntegrationState.commandText(recordId))
        session.close()
    }

    @Test
    fun `OSC 133 command start stores unknown command text without prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("PS> git status\u001B]133;C\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[0]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertNull(session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command start stores unknown command text after orphan prompt end`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost("PS> \u001B]133;B\u0007git status\u001B]133;C\u0007".ascii())

        val decorations = session.shellDecorations()
        val recordId = decorations.commandRecordIds[0]
        assertAll(
            { assertTrue(recordId != TerminalShellIntegrationCommandRecord.NONE) },
            { assertNull(session.shellIntegrationState.commandText(recordId)) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 decorations re-anchor after clear screen and history`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 30, rows = 4)

        connector.feedFromHost(
            (
                "\u001B]133;A\u0007PS> \u001B]133;B\u0007bad\u001B]133;C\u0007\r\n" +
                    "failed\u001B]133;D;1\u0007" +
                    "\u001B[H\u001B[2J\u001B[3J" +
                    "\u001B]133;A\u0007PS> \u001B]133;B\u0007"
            ).ascii(),
        )

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.lineIds.all { it > 0L }, "clear-history render frame exposed a zero line id") },
            { assertTrue(decorations.promptStarts.any { it }, "new prompt marker did not re-anchor after clear") },
        )
        session.close()
    }

    @Test
    fun `OSC 133 command finish followed by prompt preserves next prompt marker`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007failed\r\n\u001B]133;D;2\u0007\u001B]133;A\u0007PS> ".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 prompt start abandons unfinished command before stale finish marker`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007partial\r\n\u001B]133;A\u0007PS> \u001B]133;D;2\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 zero exit code records succeeded lifecycle`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007ok\u001B]133;D;0\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.SUCCEEDED, decorations.commandLifecycleStates[0]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 duplicate command start abandons first command and finishes newest command`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("\u001B]133;C\u0007partial\r\n\u001B]133;C\u0007new\r\n\u001B]133;D;1\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE) },
            { assertTrue(decorations.commandRecordIds[1] != TerminalShellIntegrationCommandRecord.NONE) },
            { assertNotEquals(decorations.commandRecordIds[0], decorations.commandRecordIds[1]) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.ABANDONED, decorations.commandLifecycleStates[0]) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.FAILED, decorations.commandLifecycleStates[1]) },
        )
        session.close()
    }

    @Test
    fun `OSC 133 finish without command start is ignored by command timeline`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 20, rows = 4)

        connector.feedFromHost("orphan\u001B]133;D;1\u0007".ascii())

        val decorations = session.shellDecorations()
        assertAll(
            { assertEquals(TerminalShellIntegrationCommandRecord.NONE, decorations.commandRecordIds[0]) },
            { assertEquals(TerminalShellIntegrationCommandLifecycle.NONE, decorations.commandLifecycleStates[0]) },
        )
        session.close()
    }

    @Test
    fun `resize preserves shared shell integration timeline`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 4)

        connector.feedFromHost("\u001B]133;A\u0007prompt> ".ascii())
        assertTrue(session.shellDecorations().promptStarts[0])

        session.resize(columns = 4, rows = 4)

        val decorations = session.shellDecorations()
        assertAll(
            { assertTrue(decorations.promptStarts[0]) },
            { assertFalse(decorations.promptStarts[1]) },
        )
        session.close()
    }

    @Test
    fun `parser endOfInput is called once`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
            )

        session.start(columns = 10, rows = 3)
        session.close()
        connector.simulateClosed(0)
        session.close()

        assertEquals(1, parser.endOfInputCalls)
    }

    @Test
    fun `readRenderFrame exposes frame through session reader`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 3)

        session.readRenderFrame { frame ->
            assertAll(
                { assertEquals(10, frame.columns) },
                { assertEquals(3, frame.rows) },
            )
        }

        session.close()
    }

    @Test
    fun `hyperlinkUri delegates to session resolver`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver =
                    { id ->
                        if (id == 42) "https://example.com" else null
                    },
            )

        assertAll(
            { assertEquals("https://example.com", session.hyperlinkUri(42)) },
            { assertNull(session.hyperlinkUri(7)) },
        )
        session.close()
    }

    @Test
    fun `readRenderFrame forwards caller owned scrollback offset`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = OffsetRecordingRenderReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
            )

        session.readRenderFrame(scrollbackOffset = 4) { frame ->
            assertEquals(4, frame.scrollbackOffset)
        }

        assertEquals(4, renderReader.lastOffset)
        session.close()
    }

    @Test
    fun `requestRender publishes caller owned scrollback offset`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                val previousGeneration = session.renderGeneration.value
                session.requestRender(scrollbackOffset = 3)

                runCurrent()
                assertTrue(session.renderGeneration.value > previousGeneration)
                assertAll(
                    { assertEquals(3, renderReader.lastOffset) },
                    { assertEquals(3, session.renderPublisher.current()?.scrollbackOffset) },
                )
            }
        }

    @Test
    fun `synchronized output mode defers rendering and flushes on disable`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val session = TerminalSession.create(terminal, connector, workerDispatcher = dispatcher)
            session.start(columns = 10, rows = 3)

            // Enable synchronized output mode: CSI ? 2026 h
            connector.feedFromHost("\u001B[?2026h".toByteArray(StandardCharsets.US_ASCII))

            // Write some text to trigger render requests
            connector.feedFromHost("hello".toByteArray(StandardCharsets.US_ASCII))

            runCurrent()
            assertEquals(-1L, session.renderGeneration.value)

            // Disable synchronized output mode: CSI ? 2026 l
            connector.feedFromHost("\u001B[?2026l".toByteArray(StandardCharsets.US_ASCII))

            runCurrent()
            assertTrue(session.renderGeneration.value >= 0L)
            assertEquals("hello", session.terminal.getLineAsString(0))
            session.close()
        }

    @Test
    fun `synchronized output mode automatically times out and flushes`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val session = TerminalSession.create(terminal, connector, workerDispatcher = dispatcher)
            session.start(columns = 10, rows = 3)

            // Enable synchronized output mode and write some text
            connector.feedFromHost("\u001B[?2026hhello".toByteArray(StandardCharsets.US_ASCII))

            runCurrent()
            assertEquals(-1L, session.renderGeneration.value)

            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertTrue(session.renderGeneration.value >= 0L)

            // Verify synchronized output mode is turned off in the core
            assertFalse(session.terminal.getModeSnapshot().isSynchronizedOutput)
            assertEquals("hello", session.terminal.getLineAsString(0))
            session.close()
        }

    @Test
    fun `render requests coalesce while worker is busy`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                renderReader.beforeRead = { call ->
                    if (call == 1) repeat(5) { session.requestRender(scrollbackOffset = 0) }
                }
                session.requestRender(scrollbackOffset = 0)
                runCurrent()

                assertEquals(2, renderReader.readCalls)
                assertEquals(6L, session.renderGeneration.value)
            }
        }

    @Test
    fun `sustained host output publishes only the latest generation once per interval`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = dispatcher,
                )

            session.requestRender(scrollbackOffset = 0)
            runCurrent()
            assertAll(
                { assertEquals(1, renderReader.readCalls) },
                { assertEquals(0, renderReader.lastOffset) },
            )

            repeat(3) {
                session.onBytes(byteArrayOf('x'.code.toByte()), offset = 0, length = 1)
            }
            runCurrent()
            assertEquals(1, renderReader.readCalls)

            advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
            runCurrent()
            assertAll(
                { assertEquals(2, renderReader.readCalls) },
                { assertEquals(4L, session.renderGeneration.value) },
            )
            session.close()
        }

    @Test
    fun `explicit viewport request interrupts host output publication interval`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = dispatcher,
                )

            session.requestRender(scrollbackOffset = 0)
            runCurrent()
            session.onBytes(byteArrayOf('x'.code.toByte()), offset = 0, length = 1)
            session.requestRender(scrollbackOffset = 5)
            runCurrent()

            assertAll(
                { assertEquals(2, renderReader.readCalls) },
                { assertEquals(5, renderReader.lastOffset) },
                { assertEquals(5, session.renderPublisher.current()?.scrollbackOffset) },
            )
            session.close()
        }

    @Test
    fun `requestRender publishes latest viewport after in flight render`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                renderReader.beforeRead = { call ->
                    if (call == 1) {
                        session.requestRender(scrollbackOffset = 2)
                        session.requestRender(scrollbackOffset = 5)
                    }
                }
                session.requestRender(scrollbackOffset = 1)
                runCurrent()

                assertAll(
                    { assertEquals(2, renderReader.readCalls) },
                    { assertEquals(listOf(1, 5), renderReader.offsets.toList()) },
                    { assertEquals(5, session.renderPublisher.current()?.scrollbackOffset) },
                )
            }
        }

    @Test
    fun `requestRender waits for a new generation after publish failure`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            renderReader.beforeRead = { call -> check(call != 1) { "first render fails before publish" } }
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                session.requestRender(scrollbackOffset = 1)
                runCurrent()

                assertAll(
                    { assertEquals(1, renderReader.readCalls) },
                    { assertEquals(-1L, session.renderGeneration.value) },
                    { assertNull(session.renderPublisher.current()) },
                )

                session.requestRender(scrollbackOffset = 2)

                runCurrent()
                assertAll(
                    { assertEquals(2, renderReader.readCalls) },
                    { assertEquals(2, session.renderPublisher.current()?.scrollbackOffset) },
                )
            }
        }

    @Test
    fun `subscriber failure does not stop render publication`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoder = NoOpInputEncoder,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                val subscriberFailure = IllegalStateException("subscriber failed")
                var observedFailure: Throwable? = null
                val collectorScope =
                    CoroutineScope(
                        SupervisorJob() +
                            StandardTestDispatcher(testScheduler) +
                            CoroutineExceptionHandler { _, failure -> observedFailure = failure },
                    )
                try {
                    collectorScope.launch {
                        session.renderGeneration.first { it >= 0L }
                        throw subscriberFailure
                    }

                    session.requestRender(scrollbackOffset = 1)
                    runCurrent()
                    val firstGeneration = session.renderGeneration.value
                    assertSame(subscriberFailure, observedFailure)

                    assertAll(
                        { assertEquals(1, renderReader.readCalls) },
                        { assertEquals(1, session.renderPublisher.current()?.scrollbackOffset) },
                    )

                    session.requestRender(scrollbackOffset = 2)

                    runCurrent()
                    assertTrue(session.renderGeneration.value > firstGeneration)
                    assertAll(
                        { assertEquals(2, renderReader.readCalls) },
                        { assertEquals(2, session.renderPublisher.current()?.scrollbackOffset) },
                    )
                } finally {
                    collectorScope.cancel()
                }
            }
        }

    @Test
    fun `readRenderFrame blocks host byte mutation until callback returns`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
                workerDispatcher = StandardTestDispatcher(),
            )
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val feedCompleted = CountDownLatch(1)

        session.start(columns = 10, rows = 3)

        session.use {
            SessionTestThread("terminal-session-render-lock-test") {
                session.readRenderFrame {
                    callbackEntered.countDown()
                    releaseCallback.await()
                }
            }.use { renderThread ->
                try {
                    assertTrue(callbackEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "render callback did not start")
                    SessionTestThread("terminal-session-feed-lock-test") {
                        connector.feedFromHost("A".ascii())
                        feedCompleted.countDown()
                    }.use { feedThread ->
                        try {
                            feedThread.awaitBlockedBy(renderThread)
                            assertEquals(1L, feedCompleted.count, "host bytes mutated during render callback")
                            assertEquals(0, parser.acceptCalls)
                        } finally {
                            releaseCallback.countDown()
                        }
                        renderThread.awaitCompletion()
                        feedThread.awaitCompletion()
                    }
                } finally {
                    releaseCallback.countDown()
                }
            }
            assertEquals(0L, feedCompleted.count, "host byte feed did not complete")
            assertEquals(1, parser.acceptCalls)
        }
    }

    @Test
    fun `readRenderFrame blocks resize until callback returns`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 3)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val resizeCompleted = CountDownLatch(1)

        session.use {
            SessionTestThread("terminal-session-render-resize-lock-test") {
                session.readRenderFrame {
                    callbackEntered.countDown()
                    releaseCallback.await()
                }
            }.use { renderThread ->
                try {
                    assertTrue(callbackEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "render callback did not start")
                    SessionTestThread("terminal-session-resize-lock-test") {
                        session.resize(columns = 20, rows = 5)
                        resizeCompleted.countDown()
                    }.use { resizeThread ->
                        try {
                            resizeThread.awaitBlockedBy(renderThread)
                            assertEquals(1L, resizeCompleted.count, "resize completed during render callback")
                            assertEquals(10, session.terminal.width)
                            assertEquals(3, session.terminal.height)
                        } finally {
                            releaseCallback.countDown()
                        }
                        renderThread.awaitCompletion()
                        resizeThread.awaitCompletion()
                    }
                } finally {
                    releaseCallback.countDown()
                }
            }
            assertEquals(0L, resizeCompleted.count, "resize did not complete")
            assertEquals(20, session.terminal.width)
            assertEquals(5, session.terminal.height)
        }
    }

    @Test
    fun `onBytes cannot mutate while copyLine is running inside render callback`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val feedCompleted = CountDownLatch(1)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = BlockingCopyRenderReader(copyEntered, releaseCopy),
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
                workerDispatcher = StandardTestDispatcher(),
            )
        session.start(columns = 10, rows = 3)

        session.use {
            SessionTestThread("terminal-session-copyline-lock-test") {
                session.readRenderFrame { frame ->
                    frame.copyLine(
                        row = 0,
                        codeWords = IntArray(frame.columns),
                        attrWords = LongArray(frame.columns),
                        flags = IntArray(frame.columns),
                    )
                }
            }.use { renderThread ->
                try {
                    assertTrue(copyEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "copyLine did not start")
                    SessionTestThread("terminal-session-feed-during-copyline-test") {
                        connector.feedFromHost("A".ascii())
                        feedCompleted.countDown()
                    }.use { feedThread ->
                        try {
                            feedThread.awaitBlockedBy(renderThread)
                            assertEquals(1L, feedCompleted.count, "host bytes mutated during copyLine")
                            assertEquals(0, parser.acceptCalls)
                        } finally {
                            releaseCopy.countDown()
                        }
                        renderThread.awaitCompletion()
                        feedThread.awaitCompletion()
                    }
                } finally {
                    releaseCopy.countDown()
                }
            }
            assertEquals(0L, feedCompleted.count, "host byte feed did not complete")
            assertEquals(1, parser.acceptCalls)
        }
    }

    @Test
    fun `UI callback cannot observe half mutated row`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val firstWriteDone = CountDownLatch(1)
        val releaseSecondWrite = CountDownLatch(1)
        val renderEntered = CountDownLatch(1)
        val parser = HalfRowParser(terminal, firstWriteDone, releaseSecondWrite)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
                workerDispatcher = StandardTestDispatcher(),
            )
        session.start(columns = 10, rows = 3)

        session.use {
            SessionTestThread("terminal-session-half-row-feed-test") {
                connector.feedFromHost("ignored".ascii())
            }.use { feedThread ->
                try {
                    assertTrue(firstWriteDone.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "parser did not perform first write")
                    SessionTestThread("terminal-session-half-row-render-test") {
                        session.readRenderFrame { frame ->
                            renderEntered.countDown()
                            val codeWords = IntArray(frame.columns)
                            val attrWords = LongArray(frame.columns)
                            val flags = IntArray(frame.columns)
                            frame.copyLine(
                                row = 0,
                                codeWords = codeWords,
                                attrWords = attrWords,
                                flags = flags,
                            )
                            assertEquals('A'.code, codeWords[0])
                            assertEquals('B'.code, codeWords[1])
                        }
                    }.use { renderThread ->
                        try {
                            renderThread.awaitBlockedBy(feedThread)
                            assertEquals(1L, renderEntered.count, "render callback observed half-mutated row")
                        } finally {
                            releaseSecondWrite.countDown()
                        }
                        feedThread.awaitCompletion()
                        renderThread.awaitCompletion()
                    }
                } finally {
                    releaseSecondWrite.countDown()
                }
            }
            assertEquals(0L, renderEntered.count, "render callback did not run")
        }
    }

    private fun createStartedSession(
        connector: TerminalConnector,
        columns: Int = 10,
        rows: Int = 3,
        hostEvents: HostEventSink = HostEventSink.NONE,
        hostPolicy: HostPolicy = HostPolicy(),
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = columns, height = rows)
        val session =
            TerminalSession.create(
                terminal,
                connector,
                hostEvents = hostEvents,
                hostPolicy = hostPolicy,
                workerDispatcher = StandardTestDispatcher(),
            )
        session.start(columns, rows)
        return session
    }

    private class RecordingHostEvents : HostEventSink by HostEventSink.NONE {
        val clipboardAudits = mutableListOf<TerminalClipboardAuditEvent>()
        val clipboardWrites = mutableListOf<TerminalClipboardWriteEvent>()
        val clipboardPrompts = mutableListOf<TerminalClipboardPromptEvent>()

        override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
            clipboardAudits += event
        }

        override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
            clipboardWrites += event
        }

        override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
            clipboardPrompts += event
        }
    }

    private fun TerminalSession.shellDecorations(): ShellDecorationSnapshot {
        var lineIds = LongArray(0)
        var promptStarts = BooleanArray(0)
        var commandStarts = BooleanArray(0)
        var commandEnds = BooleanArray(0)
        var commandRecordIds = IntArray(0)
        var commandLifecycleStates = IntArray(0)
        readRenderFrame { frame ->
            lineIds = LongArray(frame.rows)
            var row = 0
            while (row < frame.rows) {
                lineIds[row] = frame.lineId(row)
                row++
            }
            promptStarts = BooleanArray(frame.rows)
            commandStarts = BooleanArray(frame.rows)
            commandEnds = BooleanArray(frame.rows)
            commandRecordIds = IntArray(frame.rows)
            commandLifecycleStates = IntArray(frame.rows)
            shellIntegrationState.copyViewport(
                lineIds = lineIds,
                rowCount = frame.rows,
                promptStarts = promptStarts,
                commandStarts = commandStarts,
                commandEnds = commandEnds,
                commandRecordIds = commandRecordIds,
                commandLifecycleStates = commandLifecycleStates,
            )
        }
        return ShellDecorationSnapshot(
            lineIds = lineIds,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )
    }

    private data class ShellDecorationSnapshot(
        val lineIds: LongArray,
        val promptStarts: BooleanArray,
        val commandStarts: BooleanArray,
        val commandEnds: BooleanArray,
        val commandRecordIds: IntArray,
        val commandLifecycleStates: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ShellDecorationSnapshot

            if (!promptStarts.contentEquals(other.promptStarts)) return false
            if (!commandStarts.contentEquals(other.commandStarts)) return false
            if (!commandEnds.contentEquals(other.commandEnds)) return false
            if (!commandRecordIds.contentEquals(other.commandRecordIds)) return false
            if (!commandLifecycleStates.contentEquals(other.commandLifecycleStates)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = promptStarts.contentHashCode()
            result = 31 * result + commandStarts.contentHashCode()
            result = 31 * result + commandEnds.contentHashCode()
            result = 31 * result + commandRecordIds.contentHashCode()
            result = 31 * result + commandLifecycleStates.contentHashCode()
            return result
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.asciiText(): String = toString(StandardCharsets.US_ASCII)

    private class RecordingParser : TerminalOutputParser {
        var endOfInputCalls: Int = 0
            private set

        @Volatile
        var acceptCalls: Int = 0
            private set

        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            acceptCalls++
        }

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() {
            endOfInputCalls++
        }

        override fun reset() = Unit
    }

    private class HalfRowParser(
        private val terminal: TerminalBuffer,
        private val firstWriteDone: CountDownLatch,
        private val releaseSecondWrite: CountDownLatch,
    ) : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            terminal.writeCodepoint('A'.code)
            firstWriteDone.countDown()
            releaseSecondWrite.await()
            terminal.writeCodepoint('B'.code)
        }

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private class BlockingCopyRenderReader(
        private val copyEntered: CountDownLatch,
        private val releaseCopy: CountDownLatch,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(
                object : TerminalRenderFrame {
                    override val columns: Int = 2
                    override val rows: Int = 1
                    override val frameGeneration: Long = 0
                    override val structureGeneration: Long = 0
                    override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
                    override val cursor: TerminalRenderCursor =
                        TerminalRenderCursor(
                            column = 0,
                            row = 0,
                            visible = true,
                            blinking = false,
                            shape = TerminalRenderCursorShape.BLOCK,
                            generation = 0,
                        )

                    override fun lineGeneration(row: Int): Long = 0

                    override fun lineWrapped(row: Int): Boolean = false

                    override fun copyLine(
                        row: Int,
                        codeWords: IntArray,
                        codeOffset: Int,
                        attrWords: LongArray,
                        attrOffset: Int,
                        flags: IntArray,
                        flagOffset: Int,
                        extraAttrWords: LongArray?,
                        extraAttrOffset: Int,
                        hyperlinkIds: IntArray?,
                        hyperlinkOffset: Int,
                        clusterSink: TerminalRenderClusterSink?,
                        clusterDataSink: TerminalRenderClusterDataSink?,
                    ) {
                        copyEntered.countDown()
                        releaseCopy.await()
                        codeWords[codeOffset] = 'X'.code
                        flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
                    }
                },
            )
        }
    }

    private class OffsetRecordingRenderReader : TerminalRenderFrameReader {
        private val calls = AtomicInteger(0)
        val offsets = mutableListOf<Int>()
        var beforeRead: (Int) -> Unit = {}

        var lastOffset: Int = -1
            private set

        val readCalls: Int
            get() = calls.get()

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            val call = calls.incrementAndGet()
            lastOffset = scrollbackOffset
            offsets += scrollbackOffset
            beforeRead(call)
            consumer.accept(OffsetRenderFrame(scrollbackOffset))
        }
    }

    private class OffsetRenderFrame(
        override val scrollbackOffset: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 10
        override val rows: Int = 3
        override val historySize: Int = 10
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = scrollbackOffset == 0,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    private class SlowFirstWriteConnector(
        private val startSecondWriter: () -> SessionTestThread,
    ) : TerminalConnector {
        private val bytes = ArrayList<Byte>()
        private var listener: TerminalConnectorListener? = null
        private var writes: Int = 0
        private var writerThread: SessionTestThread? = null

        val writtenBytes: ByteArray
            get() =
                synchronized(bytes) {
                    ByteArray(bytes.size) { index -> bytes[index] }
                }

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val currentWrite =
                synchronized(this) {
                    writes++
                    writes
                }

            if (currentWrite == 1) {
                val writer = startSecondWriter()
                writerThread = writer
                writer.awaitBlockedBy(Thread.currentThread())
            }

            synchronized(this.bytes) {
                var index = 0
                while (index < length) {
                    this.bytes += bytes[offset + index]
                    index++
                }
            }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() {
            writerThread?.close()
        }

        fun feedFromHost(bytes: ByteArray) {
            listener?.onBytes(bytes, 0, bytes.size)
        }

        fun awaitWrites() {
            checkNotNull(writerThread).awaitCompletion()
            assertEquals(2, writes)
        }
    }

    private class ReplacementInterleavingConnector(
        private val startConcurrentWriter: () -> SessionTestThread,
    ) : TerminalConnector {
        private val bytes = ArrayList<Byte>()
        private var writes = 0
        private var writerThread: SessionTestThread? = null

        val writtenBytes: ByteArray
            get() = synchronized(bytes) { ByteArray(bytes.size) { index -> bytes[index] } }

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val currentWrite = synchronized(this) { ++writes }
            if (currentWrite == 1) {
                val writer = startConcurrentWriter()
                writerThread = writer
                writer.awaitBlockedBy(Thread.currentThread())
            }
            synchronized(this.bytes) {
                var index = 0
                while (index < length) {
                    this.bytes += bytes[offset + index]
                    index++
                }
            }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() {
            writerThread?.close()
        }

        fun awaitWrites() {
            checkNotNull(writerThread).awaitCompletion()
            assertEquals(3, writes)
        }
    }
}
