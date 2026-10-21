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
import io.github.ketraterm.parser.api.TerminalGraphicsOutputParser
import io.github.ketraterm.parser.api.TerminalOutputParserFactory
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GraphicsStartupCloseTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `shutdown releases connector during synchronous startup decode and discards its image`(cleanupFails: Boolean) =
        runTest {
            val decodeEntered = CountDownLatch(1)
            val releaseDecode = CountDownLatch(1)
            val connectorClosed = CountDownLatch(1)
            val recorded = MockConnector()
            val failure = IllegalStateException("connector cleanup failed")
            val connector =
                object : TerminalConnector by recorded {
                    override fun start(listener: TerminalConnectorListener) {
                        recorded.start(listener)
                        val bytes = "\u001b_Ga=T,i=7,f=24,o=z,s=1,v=1,C=1,c=1,r=1;eJz7z8AAAAMAAQA=\u001b\\X".encodeToByteArray()
                        listener.onBytes(bytes, 0, bytes.size)
                    }

                    override fun close() {
                        recorded.close()
                        connectorClosed.countDown()
                        if (cleanupFails) throw failure
                    }
                }
            val parserFactory =
                TerminalOutputParserFactory { sink, budget ->
                    val delegate = TerminalParsers.create(sink, budget) as TerminalGraphicsOutputParser
                    object : TerminalGraphicsOutputParser by delegate {
                        override fun consumePendingGraphics(consumer: (TerminalGraphicsCommand, ByteArray, Int) -> Unit) {
                            delegate.consumePendingGraphics { command, bytes, length ->
                                decodeEntered.countDown()
                                releaseDecode.awaitEvent()
                                consumer(command, bytes, length)
                            }
                        }
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cleanup = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val starter = Executors.newSingleThreadExecutor()
            val terminal = TerminalBuffers.create(10, 3).apply { resize(10, 3, 0, 80, 48) }
            val session =
                TerminalSession.create(
                    terminal,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = cleanup,
                    parserFactory = parserFactory,
                )
            try {
                val startup = starter.submit { session.start(10, 3) }
                decodeEntered.awaitEvent()
                assertSame(TerminalSessionState.Created, session.state.value)
                session.requestClose()
                assertTrue(session.isClosed)
                // This event must occur while the startup callback still owns the ingress range.
                connectorClosed.awaitEvent()
                assertEquals(TerminalInputAdmission.CLOSED, session.submitBytes(byteArrayOf(1)))
                releaseDecode.countDown()
                startup.get(10, TimeUnit.SECONDS)
                val closed =
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { session.state.first { it is TerminalSessionState.Closed } }
                    } as TerminalSessionState.Closed
                assertSame(if (cleanupFails) failure else null, closed.event.failure)
                assertTrue(closed.event.locallyRequested)
                assertEquals(1, recorded.closeCount)
                assertEquals("", recorded.writtenBytes.decodeToString())
                session.readRenderFrame { frame ->
                    var images = 0
                    frame.copyImages { _, _, _, _, _, _, _, _, _, _, _, _ -> images++ }
                    assertEquals(0, images)
                    frame.copyCursor { column, row, _, _, _, _ ->
                        assertEquals(0, column)
                        assertEquals(0, row)
                    }
                }
            } finally {
                releaseDecode.countDown()
                session.close()
                starter.shutdownNow()
                cleanup.close()
            }
        }

    private fun CountDownLatch.awaitEvent() = assertTrue(await(10, TimeUnit.SECONDS), "Expected lifecycle handshake did not arrive")
}
