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
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.parser.api.TerminalGraphicsOutputParser
import io.github.ketraterm.parser.api.TerminalOutputParserFactory
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GraphicsDecodeOrderingTest {
    @Test
    fun `pinned Kitty PNG and zlib streams retain exact pixels across ingress splits`() =
        runTest {
            for ((name, hash, stride) in listOf(
                Triple("kitty-png", "0757689459b112eb2031dd659bf19a914213fa65abbb909929ac7cb67dc7f96e", 4),
                Triple("kitty-zlib-rgb", "e52c37f81d95c772cc4efc5b3f641915c100bdd0d8bc55ac6b247550525c6b1e", 3),
            )) {
                for (chunk in listOf(1, 7, 4096)) {
                    val terminal = TerminalBuffers.create(80, 24)
                    terminal.resize(80, 24, 0, 640, 384)
                    val connector = MockConnector()
                    val dispatcher = StandardTestDispatcher(testScheduler)
                    TerminalSession
                        .create(
                            terminal,
                            connector,
                            workerDispatcher = dispatcher,
                            ioDispatcher = dispatcher,
                            cleanupDispatcher = dispatcher,
                        ).use { session ->
                            session.start(80, 24)
                            val bytes = checkNotNull(javaClass.getResourceAsStream("/$name.bin")).use { it.readBytes() }
                            var offset = 0
                            while (offset < bytes.size) {
                                val count = minOf(chunk, bytes.size - offset)
                                session.onBytes(bytes, offset, count)
                                offset += count
                            }
                            val image = assertNotNull(image(session), "$name chunk=$chunk")
                            val pixels = IntArray(image.width * image.height)
                            image.copyArgb(pixels)
                            val raw = ByteArray(pixels.size * stride)
                            for (i in pixels.indices) {
                                raw[i * stride] = (pixels[i] ushr 16).toByte()
                                raw[i * stride + 1] = (pixels[i] ushr 8).toByte()
                                raw[i * stride + 2] = pixels[i].toByte()
                                if (stride == 4) raw[i * stride + 3] = (pixels[i] ushr 24).toByte()
                            }
                            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(raw).toHexString())
                        }
                }
            }
        }

    @Test
    fun `decode releases frame reads and input but commits before suffix and queued mutation`() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                    parserFactory = blockedParser(entered, release),
                )
            val workers = ArrayList<SessionTestThread>()
            try {
                session.start(10, 3)
                val ingress =
                    SessionTestThread("graphics-ingress") {
                        connector.feedFromHost((QUERY + "\u001b[cX").encodeToByteArray())
                    }.also(workers::add)
                entered.awaitEvent()
                assertEquals(0, cursorColumn(session))
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes("key".encodeToByteArray()))
                val mutation =
                    SessionTestThread("graphics-mutation") {
                        session.withMutationAdmission({ true }) {
                            assertEquals(1, cursorColumn(session), "Later output must precede host mutation")
                            session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
                        }
                    }.also(workers::add)
                mutation.awaitBlockedBy(ingress)
                release.countDown()
                ingress.awaitCompletion()
                mutation.awaitCompletion()
                runCurrent()
                assertTrue(connector.writtenBytes.decodeToString().startsWith("key\u001b_Gi=7;OK\u001b\\\u001b[?"))
                val writtenBeforeDenial = connector.writtenBytes
                connector.feedFromHost(QUERY.encodeToByteArray())
                runCurrent()
                assertContentEquals(writtenBeforeDenial, connector.writtenBytes)
                assertNull(image(session), "Queries must never retain an image")
            } finally {
                release.countDown()
                session.close()
                workers.forEach(SessionTestThread::close)
            }
        }

    @Test
    fun `owner validity is rechecked after the graphics admission wait`() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val current = AtomicBoolean(true)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                    parserFactory = blockedParser(entered, release),
                )
            val workers = ArrayList<SessionTestThread>()
            try {
                val bytes = QUERY.encodeToByteArray()
                val ingress = SessionTestThread("graphics-owner-ingress") { session.onBytes(bytes, 0, bytes.size) }.also(workers::add)
                entered.awaitEvent()
                val pending =
                    SessionTestThread("graphics-owner-mutation") {
                        assertNull(session.withMutationAdmission(current::get) { fail("Obsolete owner mutated the terminal") })
                    }.also(workers::add)
                pending.awaitBlockedBy(ingress)
                current.set(false)
                release.countDown()
                ingress.awaitCompletion()
                pending.awaitCompletion()
            } finally {
                release.countDown()
                session.close()
                workers.forEach(SessionTestThread::close)
            }
        }

    @Test
    fun `conditional edit capture rejects partial output batches across a decode yield`() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val commandLine = TerminalShellCommandLineSnapshot("git s", 5, 5, 0)
            val model = TerminalShellCommandLineState(commandLine)
            val connector = MockConnector()
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                    parserFactory = blockedParser(entered, release),
                    shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model),
                ).use { session ->
                    session.start(10, 3)
                    val previous = assertNotNull(session.captureCommandEdit())
                    SessionTestThread("graphics-edit-ingress") {
                        connector.feedFromHost((QUERY + "X").encodeToByteArray())
                    }.use { ingress ->
                        try {
                            entered.awaitEvent()
                            assertNull(session.captureCommandEdit(), "A partial output batch is not an editing boundary")
                            release.countDown()
                            ingress.awaitCompletion()
                            runCurrent()
                            assertEquals(1, cursorColumn(session))
                            val events = listOf(TerminalKeyEvent.text("x"))
                            assertEquals(TerminalInputAdmission.STALE_CONTEXT, session.submitInput(previous, events))
                            val current = assertNotNull(session.captureCommandEdit())
                            assertEquals(commandLine, current.commandLine)
                            assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(current, events))
                        } finally {
                            release.countDown()
                        }
                    }
                }
        }

    @ParameterizedTest
    @ValueSource(strings = ["close-success", "close-failure", "capacity"])
    fun `asynchronous close releases connector before waiting for decode and discards late results`(scenario: String) =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val connectorClosed = CountDownLatch(1)
            val recorded = MockConnector()
            val failure = IllegalStateException("cleanup")
            val connector =
                object : TerminalConnector by recorded {
                    override fun close() {
                        recorded.close()
                        connectorClosed.countDown()
                        if (scenario == "close-failure") throw failure
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    parserFactory = blockedParser(entered, release),
                )
            val workers = Executors.newSingleThreadExecutor()
            try {
                session.start(10, 3)
                val bytes = (QUERY.replace("a=q", "a=t") + "X").encodeToByteArray()
                val ingress = workers.submit { session.onBytes(bytes, 0, bytes.size) }
                entered.awaitEvent()
                var expectedFailure: Throwable? = if (scenario == "close-failure") failure else null
                if (scenario == "capacity") {
                    SwingUtilities.invokeAndWait {
                        assertEquals(
                            TerminalInputAdmission.CAPACITY_EXCEEDED,
                            session.submitBytes(ByteArray(OutboundWriter.MAX_QUEUED_BYTES + 1)),
                        )
                    }
                    expectedFailure = assertIs<OutboundCapacityException>(session.failure)
                } else {
                    session.requestClose()
                }
                assertTrue(session.isClosed)
                connectorClosed.awaitEvent()
                assertEquals(0, cursorColumn(session))
                release.countDown()
                ingress.get(10, TimeUnit.SECONDS)
                val closed =
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { session.state.first { it is TerminalSessionState.Closed } }
                    } as TerminalSessionState.Closed
                assertSame(expectedFailure, closed.event.failure)
                assertEquals(scenario != "capacity", closed.event.locallyRequested)
                assertEquals(0, cursorColumn(session))
                assertNull(image(session))
                assertEquals("", recorded.writtenBytes.decodeToString())
            } finally {
                release.countDown()
                session.close()
                workers.shutdownNow()
            }
        }

    private fun blockedParser(
        entered: CountDownLatch,
        release: CountDownLatch,
    ) = TerminalOutputParserFactory { sink, budget ->
        val parser = TerminalParsers.create(sink, budget) as TerminalGraphicsOutputParser
        object : TerminalGraphicsOutputParser by parser {
            override fun consumePendingGraphics(consumer: (TerminalGraphicsCommand, ByteArray, Int) -> Unit) {
                parser.consumePendingGraphics { command, bytes, length ->
                    entered.countDown()
                    release.awaitEvent()
                    consumer(command, bytes, length)
                }
            }
        }
    }

    private fun cursorColumn(session: TerminalSession): Int {
        var column = -1
        session.readRenderFrame { it.copyCursor { col, _, _, _, _, _ -> column = col } }
        return column
    }

    private fun image(session: TerminalSession): TerminalRenderImage? {
        var image: TerminalRenderImage? = null
        session.readRenderFrame { it.copyImages { resource, _, _, _, _, _, _, _, _, _, _, _ -> image = resource } }
        return image
    }

    private fun CountDownLatch.awaitEvent() = assertTrue(await(10, TimeUnit.SECONDS), "Expected handshake did not arrive")

    private companion object {
        // One RGB pixel compressed with zlib. Actual decoding, not an injected success.
        const val QUERY = "\u001b_Ga=q,i=7,f=24,o=z,s=1,v=1;eJz7z8AAAAMAAQA=\u001b\\"
    }
}
