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
package com.gagik.terminal.session

import com.gagik.core.TerminalBuffers
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.render.api.*
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.testkit.MockConnector
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionTest {
    @Test
    fun `DSR CSI 5 n replies OK status`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[5n".ascii())

        assertEquals("\u001B[0n", connector.writtenBytes.asciiText())
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
    fun `remote close records exit code`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.simulateClosed(7)

        assertEquals(7, session.exitCode)
        assertEquals(0, connector.closeCount)
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
    }

    @Test
    fun `onClosed does not recursively close connector`() {
        val connector = MockConnector()
        createStartedSession(connector)

        connector.simulateClosed(7)

        assertEquals(0, connector.closeCount)
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
    fun `response write and key write do not interleave`() {
        lateinit var session: TerminalSession
        val connector =
            SlowFirstWriteConnector {
                Thread {
                    it.countDown()
                    session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                }.apply {
                    name = "terminal-session-ordering-test"
                    start()
                }
            }
        session = createStartedSession(connector)

        connector.feedFromHost("\u001B[5n".ascii())

        assertTrue(connector.awaitTriggeredWriter(), "key writer was not started")
        assertTrue(connector.awaitWrites(2), "key write did not complete")
        assertEquals("\u001B[0na", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `start can only be called once`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        val error =
            assertThrows(IllegalStateException::class.java) {
                session.start(columns = 10, rows = 3)
            }

        assertEquals("session already started", error.message)
        assertEquals(1, connector.startCount)
        session.close()
    }

    @Test
    fun `input before start is ignored`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session = TerminalSession.create(terminal, connector)

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))

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

        session.resize(columns = 20, rows = 5)

        assertEquals(20, session.terminal.width)
        assertEquals(5, session.terminal.height)
        assertEquals(listOf(10 to 3, 20 to 5), connector.resizeCalls)
        session.close()
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
    fun `parser endOfInput is called once`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
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
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver =
                    TerminalHyperlinkResolver { id ->
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
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
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
    fun `requestRender publishes caller owned scrollback offset`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = OffsetRecordingRenderReader()
        val renderPublished = CountDownLatch(1)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
            )
        session.onDirty = { renderPublished.countDown() }

        session.requestRender(scrollbackOffset = 3)

        assertTrue(renderPublished.await(1, TimeUnit.SECONDS), "render was not published")
        assertAll(
            { assertEquals(3, renderReader.lastOffset) },
            { assertEquals(3, session.publisher.current()?.scrollbackOffset) },
        )
        session.close()
    }

    @Test
    fun `synchronized output mode defers rendering and flushes on disable`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        val renderPublished = CountDownLatch(1)
        val dirtyCalls = AtomicInteger(0)
        session.onDirty = {
            dirtyCalls.incrementAndGet()
            renderPublished.countDown()
        }

        // Enable synchronized output mode: CSI ? 2026 h
        connector.feedFromHost("\u001B[?2026h".toByteArray(StandardCharsets.US_ASCII))

        // Write some text to trigger render requests
        connector.feedFromHost("hello".toByteArray(StandardCharsets.US_ASCII))

        // Verify that no render has been published (wait less than the 100ms safety timeout)
        assertFalse(renderPublished.await(30, TimeUnit.MILLISECONDS))
        assertEquals(0, dirtyCalls.get())

        // Disable synchronized output mode: CSI ? 2026 l
        val renderLatch = CountDownLatch(1)
        session.onDirty = {
            dirtyCalls.incrementAndGet()
            renderLatch.countDown()
        }
        connector.feedFromHost("\u001B[?2026l".toByteArray(StandardCharsets.US_ASCII))

        // Verify that rendering immediately occurs
        assertTrue(renderLatch.await(1, TimeUnit.SECONDS), "render was not published after disable")
        assertEquals(1, dirtyCalls.get())
        assertEquals("hello", session.terminal.getLineAsString(0))
        session.close()
    }

    @Test
    fun `synchronized output mode automatically times out and flushes`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        val renderPublished = CountDownLatch(1)
        session.onDirty = {
            renderPublished.countDown()
        }

        // Enable synchronized output mode and write some text
        connector.feedFromHost("\u001B[?2026hhello".toByteArray(StandardCharsets.US_ASCII))

        // Verify it doesn't render immediately
        assertFalse(renderPublished.await(30, TimeUnit.MILLISECONDS))

        // Wait for safety timeout to expire (timeout is 100ms, wait 300ms to be safe)
        assertTrue(renderPublished.await(300, TimeUnit.MILLISECONDS), "render was not flushed by timeout")

        // Verify synchronized output mode is turned off in the core
        assertFalse(session.terminal.getModeSnapshot().isSynchronizedOutput)
        assertEquals("hello", session.terminal.getLineAsString(0))
        session.close()
    }

    @Test
    fun `notifyRenderDirty coalesces renders while worker is busy`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = BlockingFirstRenderReader()
        val dirtyCalls = AtomicInteger(0)
        val twoDirtyCalls = CountDownLatch(2)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
            )
        session.onDirty = {
            dirtyCalls.incrementAndGet()
            twoDirtyCalls.countDown()
        }

        session.notifyRenderDirty()
        assertTrue(renderReader.awaitFirstRead(), "first render did not start")

        repeat(5) {
            session.notifyRenderDirty()
        }

        renderReader.releaseFirstRead()

        assertTrue(twoDirtyCalls.await(1, TimeUnit.SECONDS), "coalesced render did not complete")
        Thread.sleep(100)

        assertEquals(2, dirtyCalls.get())
        assertEquals(2, renderReader.readCalls)
        session.close()
    }

    @Test
    fun `requestRender publishes latest viewport after in flight render`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = BlockingFirstOffsetRenderReader()
        val twoDirtyCalls = CountDownLatch(2)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
            )
        session.onDirty = {
            twoDirtyCalls.countDown()
        }

        session.requestRender(scrollbackOffset = 1)
        assertTrue(renderReader.awaitFirstRead(), "first render did not start")

        session.requestRender(scrollbackOffset = 2)
        session.requestRender(scrollbackOffset = 5)
        renderReader.releaseFirstRead()

        assertTrue(twoDirtyCalls.await(1, TimeUnit.SECONDS), "latest render was not published")
        Thread.sleep(100)

        assertAll(
            { assertEquals(2, renderReader.readCalls) },
            { assertEquals(listOf(1, 5), renderReader.offsets.toList()) },
            { assertEquals(5, session.publisher.current()?.scrollbackOffset) },
        )
        session.close()
    }

    @Test
    fun `requestRender waits for a new generation after publish failure`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = FailingFirstOffsetRenderReader()
        val dirtyCalls = AtomicInteger(0)
        val renderPublished = CountDownLatch(1)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
            )
        session.onDirty = {
            dirtyCalls.incrementAndGet()
            renderPublished.countDown()
        }

        session.requestRender(scrollbackOffset = 1)
        assertTrue(renderReader.awaitFirstRead(), "first render did not start")
        Thread.sleep(100)

        assertAll(
            { assertEquals(1, renderReader.readCalls) },
            { assertEquals(0, dirtyCalls.get()) },
            { assertNull(session.publisher.current()) },
        )

        session.requestRender(scrollbackOffset = 2)

        assertTrue(renderPublished.await(1, TimeUnit.SECONDS), "second render was not published")
        assertAll(
            { assertEquals(2, renderReader.readCalls) },
            { assertEquals(1, dirtyCalls.get()) },
            { assertEquals(2, session.publisher.current()?.scrollbackOffset) },
        )
        session.close()
    }

    @Test
    fun `onDirty exception does not republish completed generation`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = OffsetRecordingRenderReader()
        val firstDirtyCall = CountDownLatch(1)
        val secondDirtyCall = CountDownLatch(1)
        val dirtyCalls = AtomicInteger(0)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoder = NoOpInputEncoder,
            )
        session.onDirty = {
            val call = dirtyCalls.incrementAndGet()
            if (call == 1) {
                firstDirtyCall.countDown()
                throw IllegalStateException("dirty callback failed")
            }
            secondDirtyCall.countDown()
        }

        session.requestRender(scrollbackOffset = 1)
        assertTrue(firstDirtyCall.await(1, TimeUnit.SECONDS), "first dirty callback did not run")
        Thread.sleep(100)

        assertAll(
            { assertEquals(1, renderReader.readCalls) },
            { assertEquals(1, dirtyCalls.get()) },
            { assertEquals(1, session.publisher.current()?.scrollbackOffset) },
        )

        session.requestRender(scrollbackOffset = 2)

        assertTrue(secondDirtyCall.await(1, TimeUnit.SECONDS), "second dirty callback did not run")
        assertAll(
            { assertEquals(2, renderReader.readCalls) },
            { assertEquals(2, dirtyCalls.get()) },
            { assertEquals(2, session.publisher.current()?.scrollbackOffset) },
        )
        session.close()
    }

    @Test
    fun `readRenderFrame blocks host byte mutation until callback returns`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
            )
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val feedCompleted = CountDownLatch(1)

        session.start(columns = 10, rows = 3)

        val renderThread =
            Thread {
                session.readRenderFrame {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(1, TimeUnit.SECONDS), "render callback was not released")
                }
            }.apply {
                name = "terminal-session-render-lock-test"
                start()
            }

        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS), "render callback did not start")

        val feedThread =
            Thread {
                connector.feedFromHost("A".ascii())
                feedCompleted.countDown()
            }.apply {
                name = "terminal-session-feed-lock-test"
                start()
            }

        assertFalse(feedCompleted.await(100, TimeUnit.MILLISECONDS), "host bytes mutated during render callback")
        assertEquals(0, parser.acceptCalls)

        releaseCallback.countDown()
        renderThread.join(1000)
        feedThread.join(1000)

        assertTrue(feedCompleted.await(1, TimeUnit.SECONDS), "host byte feed did not complete")
        assertEquals(1, parser.acceptCalls)
        session.close()
    }

    @Test
    fun `readRenderFrame blocks resize until callback returns`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 3)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val resizeCompleted = CountDownLatch(1)

        val renderThread =
            Thread {
                session.readRenderFrame {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(1, TimeUnit.SECONDS), "render callback was not released")
                }
            }.apply {
                name = "terminal-session-render-resize-lock-test"
                start()
            }

        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS), "render callback did not start")

        val resizeThread =
            Thread {
                session.resize(columns = 20, rows = 5)
                resizeCompleted.countDown()
            }.apply {
                name = "terminal-session-resize-lock-test"
                start()
            }

        assertFalse(resizeCompleted.await(100, TimeUnit.MILLISECONDS), "resize completed during render callback")
        assertEquals(10, session.terminal.width)
        assertEquals(3, session.terminal.height)

        releaseCallback.countDown()
        renderThread.join(1000)
        resizeThread.join(1000)

        assertTrue(resizeCompleted.await(1, TimeUnit.SECONDS), "resize did not complete")
        assertEquals(20, session.terminal.width)
        assertEquals(5, session.terminal.height)
        session.close()
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
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = BlockingCopyRenderReader(copyEntered, releaseCopy),
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
            )
        session.start(columns = 10, rows = 3)

        val renderThread =
            Thread {
                session.readRenderFrame { frame ->
                    frame.copyLine(
                        row = 0,
                        codeWords = IntArray(frame.columns),
                        attrWords = LongArray(frame.columns),
                        flags = IntArray(frame.columns),
                    )
                }
            }.apply {
                name = "terminal-session-copyline-lock-test"
                start()
            }

        assertTrue(copyEntered.await(1, TimeUnit.SECONDS), "copyLine did not start")

        val feedThread =
            Thread {
                connector.feedFromHost("A".ascii())
                feedCompleted.countDown()
            }.apply {
                name = "terminal-session-feed-during-copyline-test"
                start()
            }

        assertFalse(feedCompleted.await(100, TimeUnit.MILLISECONDS), "host bytes mutated during copyLine")
        assertEquals(0, parser.acceptCalls)

        releaseCopy.countDown()
        renderThread.join(1000)
        feedThread.join(1000)

        assertTrue(feedCompleted.await(1, TimeUnit.SECONDS), "host byte feed did not complete")
        assertEquals(1, parser.acceptCalls)
        session.close()
    }

    @Test
    fun `UI callback cannot observe half mutated row`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val firstWriteDone = CountDownLatch(1)
        val releaseSecondWrite = CountDownLatch(1)
        val renderEntered = CountDownLatch(1)
        val renderSawCompleteRow = CountDownLatch(1)
        val parser = HalfRowParser(terminal, firstWriteDone, releaseSecondWrite)
        val session =
            TerminalSession(
                terminal = terminal,
                publisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = NoOpInputEncoder,
            )
        session.start(columns = 10, rows = 3)

        val feedThread =
            Thread {
                connector.feedFromHost("ignored".ascii())
            }.apply {
                name = "terminal-session-half-row-feed-test"
                start()
            }

        assertTrue(firstWriteDone.await(1, TimeUnit.SECONDS), "parser did not perform first write")

        val renderThread =
            Thread {
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
                    if (codeWords[0] == 'A'.code && codeWords[1] == 'B'.code) {
                        renderSawCompleteRow.countDown()
                    }
                }
            }.apply {
                name = "terminal-session-half-row-render-test"
                start()
            }

        assertFalse(renderEntered.await(100, TimeUnit.MILLISECONDS), "render callback observed half-mutated row")

        releaseSecondWrite.countDown()
        feedThread.join(1000)
        renderThread.join(1000)

        assertTrue(renderEntered.await(1, TimeUnit.SECONDS), "render callback did not run")
        assertTrue(renderSawCompleteRow.await(1, TimeUnit.SECONDS), "render callback did not see complete row")
        session.close()
    }

    private fun createStartedSession(
        connector: TerminalConnector,
        columns: Int = 10,
        rows: Int = 3,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = columns, height = rows)
        val session = TerminalSession.create(terminal, connector)
        session.start(columns, rows)
        return session
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
        private val terminal: com.gagik.core.api.TerminalBufferApi,
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
            check(releaseSecondWrite.await(1, TimeUnit.SECONDS)) {
                "second write was not released"
            }
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
                        check(releaseCopy.await(1, TimeUnit.SECONDS)) {
                            "copyLine was not released"
                        }
                        codeWords[codeOffset] = 'X'.code
                        flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
                    }
                },
            )
        }
    }

    private class BlockingFirstRenderReader : TerminalRenderFrameReader {
        private val firstReadEntered = CountDownLatch(1)
        private val releaseFirstRead = CountDownLatch(1)
        private val calls = AtomicInteger(0)

        val readCalls: Int
            get() = calls.get()

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            val call = calls.incrementAndGet()
            if (call == 1) {
                firstReadEntered.countDown()
                check(releaseFirstRead.await(1, TimeUnit.SECONDS)) {
                    "first render was not released"
                }
            }
            consumer.accept(SimpleRenderFrame)
        }

        fun awaitFirstRead(): Boolean = firstReadEntered.await(1, TimeUnit.SECONDS)

        fun releaseFirstRead() {
            releaseFirstRead.countDown()
        }
    }

    private class BlockingFirstOffsetRenderReader : TerminalRenderFrameReader {
        private val firstReadEntered = CountDownLatch(1)
        private val releaseFirstRead = CountDownLatch(1)
        private val calls = AtomicInteger(0)

        val offsets = CopyOnWriteArrayList<Int>()
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
            offsets.add(scrollbackOffset)
            if (call == 1) {
                firstReadEntered.countDown()
                check(releaseFirstRead.await(1, TimeUnit.SECONDS)) {
                    "first render was not released"
                }
            }
            consumer.accept(OffsetRenderFrame(scrollbackOffset))
        }

        fun awaitFirstRead(): Boolean = firstReadEntered.await(1, TimeUnit.SECONDS)

        fun releaseFirstRead() {
            releaseFirstRead.countDown()
        }
    }

    private class OffsetRecordingRenderReader : TerminalRenderFrameReader {
        private val calls = AtomicInteger(0)

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
            calls.incrementAndGet()
            lastOffset = scrollbackOffset
            consumer.accept(OffsetRenderFrame(scrollbackOffset))
        }
    }

    private class FailingFirstOffsetRenderReader : TerminalRenderFrameReader {
        private val firstReadEntered = CountDownLatch(1)
        private val calls = AtomicInteger(0)

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
            if (call == 1) {
                firstReadEntered.countDown()
                throw IllegalStateException("first render fails before publish")
            }
            consumer.accept(OffsetRenderFrame(scrollbackOffset))
        }

        fun awaitFirstRead(): Boolean = firstReadEntered.await(1, TimeUnit.SECONDS)
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

    private object SimpleRenderFrame : TerminalRenderFrame {
        override val columns: Int = 10
        override val rows: Int = 3
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = true,
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
        ) {
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    private class SlowFirstWriteConnector(
        private val startSecondWriter: (CountDownLatch) -> Thread,
    ) : TerminalConnector {
        private val writesDone = CountDownLatch(2)
        private val triggeredWriter = CountDownLatch(1)
        private val secondWriterAttempted = CountDownLatch(1)
        private val bytes = ArrayList<Byte>()
        private var listener: TerminalConnectorListener? = null
        private var writes: Int = 0
        private var writerThread: Thread? = null

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
                writerThread = startSecondWriter(secondWriterAttempted)
                triggeredWriter.countDown()
                check(secondWriterAttempted.await(1, TimeUnit.SECONDS)) {
                    "second writer did not attempt to write"
                }
            }

            synchronized(this.bytes) {
                var index = 0
                while (index < length) {
                    this.bytes += bytes[offset + index]
                    index++
                }
            }
            writesDone.countDown()
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() {
            writerThread?.join(1000)
        }

        fun feedFromHost(bytes: ByteArray) {
            listener?.onBytes(bytes, 0, bytes.size)
        }

        fun awaitTriggeredWriter(): Boolean = triggeredWriter.await(1, TimeUnit.SECONDS)

        fun awaitWrites(count: Int): Boolean {
            require(count == 2) { "this fixture only waits for the two expected writes" }
            val completed = writesDone.await(1, TimeUnit.SECONDS)
            writerThread?.join(1000)
            return completed
        }
    }
}
