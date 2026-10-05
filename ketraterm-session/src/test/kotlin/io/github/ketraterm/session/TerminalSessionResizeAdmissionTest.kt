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
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalSessionResizeAdmissionTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `closure during core reflow suppresses connector resize without blocking connector cleanup`() {
        val resizeEntered = CountDownLatch(1)
        val releaseResize = CountDownLatch(1)
        val connectorClosed = CountDownLatch(1)
        val core = TerminalBuffers.create(10, 3)
        var gateResize = false
        val terminal =
            object : TerminalBuffer by core {
                override fun resize(
                    newWidth: Int,
                    newHeight: Int,
                    oldScrollbackOffset: Int,
                ): Pair<Int, Int> {
                    if (gateResize) {
                        resizeEntered.countDown()
                        check(releaseResize.await(10, TimeUnit.SECONDS))
                    }
                    return core.resize(newWidth, newHeight, oldScrollbackOffset)
                }
            }
        val calls = mutableListOf<Pair<Int, Int>>()
        val connector =
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) {}

                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    check(connectorClosed.count != 0L)
                    calls += columns to rows
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = error("unexpected output")

                override fun close() {
                    connectorClosed.countDown()
                }
            }
        val session =
            TerminalSession(
                terminal,
                TerminalRenderPublisher(10, 3),
                core,
                core,
                connector,
                TerminalParsers.create(HostCommandAdapter(terminal)),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        session.start(10, 3)
        gateResize = true
        val resize = FutureTask { session.tryResizeViewport(8, 2) }
        val close = FutureTask { session.close() }
        thread { resize.run() }
        try {
            assertTrue(resizeEntered.await(10, TimeUnit.SECONDS))
            thread { close.run() }
            assertTrue(connectorClosed.await(10, TimeUnit.SECONDS))
            assertNull(session.tryResizeViewport(20, 6))
        } finally {
            releaseResize.countDown()
            assertNotNull(resize.get(10, TimeUnit.SECONDS))
            close.get(10, TimeUnit.SECONDS)
            dispatcher.scheduler.runCurrent()
        }
        assertEquals(listOf(10 to 3), calls)
        session.readRenderFrame {
            assertEquals(8, it.columns)
            assertEquals(2, it.rows)
        }
    }

    @Test
    fun `resize admission preserves validation and collaborator failures`() {
        val failure = IllegalStateException("connector resize failed")
        val connector =
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) {}

                override fun resize(
                    columns: Int,
                    rows: Int,
                ): Unit = throw failure

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {}

                override fun close() {}
            }
        val session =
            TerminalSession.create(
                TerminalBuffers.create(10, 3),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        session.use {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { session.tryResizeViewport(8, 2) })
        }
        assertNull(session.tryResizeViewport(8, 2))
        for ((columns, rows) in listOf(0 to 1, 1 to 0, Int.MIN_VALUE to 1, 1 to Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { session.tryResizeViewport(columns, rows) }
        }
        assertThrows(IllegalStateException::class.java) { session.resizeViewport(8, 2) }
        dispatcher.scheduler.runCurrent()
    }
}
