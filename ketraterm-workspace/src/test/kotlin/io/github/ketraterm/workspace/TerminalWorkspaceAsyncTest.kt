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
package io.github.ketraterm.workspace

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.*
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.session.TerminalClipboardReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalWorkspaceAsyncTest {
    @Test
    fun `startup publication precedes first clipboard write and read without blocking EDT`() {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val publicationEntered = CountDownLatch(1)
            val publicationReady = CountDownLatch(1)
            val starts = AtomicInteger()
            val clipboard = AtomicReference("old")
            val expectedResponse = "\u001b]52;c;bmV3\u001b\\"
            val responseBytes = ByteArrayOutputStream()
            val response = CompletableFuture<String>()
            val connector =
                object : TerminalConnector {
                    override fun start(listener: TerminalConnectorListener) {
                        check(!SwingUtilities.isEventDispatchThread())
                        starts.incrementAndGet()
                        val bytes = "\u001b]52;c;bmV3\u0007\u001b]52;c;?\u0007".toByteArray()
                        listener.onBytes(bytes, 0, bytes.size)
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        responseBytes.write(bytes, offset, length)
                        if (responseBytes.size() >= expectedResponse.length) {
                            response.complete(responseBytes.toString(Charsets.UTF_8))
                        }
                    }

                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) = Unit

                    override fun close() = Unit
                }
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(80, 24),
                    connector = connector,
                    hostPolicy =
                        HostPolicy(
                            clipboardPolicy =
                                TerminalClipboardPolicy(
                                    writePermission = TerminalClipboardPermission.ALLOW,
                                    readPermission = TerminalClipboardPermission.ALLOW,
                                ),
                        ),
                    hostEvents =
                        object : HostEventSink by HostEventSink.NONE {
                            override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
                                clipboard.set(event.text)
                            }
                        },
                    clipboardReader = TerminalClipboardReader { TerminalClipboardReadResult.Text(clipboard.get()) },
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                )
            val workspace =
                TerminalWorkspace(
                    object : TerminalWorkspaceListener {
                        override fun tabOpened(tab: TerminalWorkspaceTab) {
                            publicationEntered.countDown()
                            publicationReady.await()
                        }
                    },
                    { _, _, _ -> session },
                    dispatcher,
                )
            val opened = CompletableFuture<TerminalWorkspaceTab>()
            try {
                val admission =
                    FutureTask {
                        workspace.openTabAsync({ TerminalProfile("test", "Test", listOf("unused")) }, TerminalWorkspaceOpenOptions()) {
                            it.fold(opened::complete, opened::completeExceptionally)
                        }
                    }
                SwingUtilities.invokeLater(admission)
                assertTrue(admission.get(10, TimeUnit.SECONDS))
                assertTrue(publicationEntered.await(10, TimeUnit.SECONDS), "Startup did not reach publication")
                val marker =
                    FutureTask {
                        assertEquals(0, starts.get())
                        assertEquals("old", clipboard.get())
                    }
                SwingUtilities.invokeLater(marker)
                marker.get(10, TimeUnit.SECONDS)
                publicationReady.countDown()
                opened.get(10, TimeUnit.SECONDS)
                assertEquals(expectedResponse, response.get(10, TimeUnit.SECONDS))
                assertEquals(1, starts.get())
            } finally {
                publicationReady.countDown()
                val closed = CompletableFuture<Unit>()
                workspace.closeAsync { it.fold(closed::complete, closed::completeExceptionally) }
                closed.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `failed startup completes only after separately dispatched session cleanup`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cleanupScheduler = TestCoroutineScheduler()
            val expected = IllegalStateException("startup failed")
            val backingConnector = MockConnector()
            val connector =
                object : TerminalConnector by backingConnector {
                    override fun start(listener: TerminalConnectorListener) {
                        (listener as TerminalSession).requestClose()
                        throw expected
                    }
                }
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(80, 24),
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = StandardTestDispatcher(cleanupScheduler),
                )
            val workspace = TerminalWorkspace(TerminalWorkspaceListener.NONE, { _, _, _ -> session }, dispatcher)
            var completions = 0
            try {
                assertTrue(
                    workspace.openTabAsync({ TerminalProfile("test", "Test", listOf("unused")) }, TerminalWorkspaceOpenOptions()) {
                        assertSame(expected, it.exceptionOrNull())
                        completions++
                    },
                )
                runCurrent()
                assertTrue(workspace.tabSnapshot().isEmpty())
                assertTrue(session.isClosed)
                assertEquals(0, backingConnector.closeCount)
                assertEquals(0, completions)
                cleanupScheduler.runCurrent()
                runCurrent()
                assertEquals(1, backingConnector.closeCount)
                assertEquals(1, completions)
            } finally {
                cleanupScheduler.runCurrent()
                workspace.close()
                runCurrent()
            }
        }

    @Test
    fun `tab completion waits for independently requested session cleanup`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cleanupScheduler = TestCoroutineScheduler()
            val connector = MockConnector()
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(80, 24),
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = StandardTestDispatcher(cleanupScheduler),
                )
            val workspace = TerminalWorkspace(TerminalWorkspaceListener.NONE, { _, _, _ -> session }, dispatcher)
            val tab = workspace.openTab(TerminalProfile("test", "Test", listOf("unused")), TerminalWorkspaceOpenOptions())
            var completed = false
            try {
                session.requestClose()
                assertTrue(
                    workspace.closeTabAsync(tab.id) {
                        it.getOrThrow()
                        completed = true
                    },
                )
                runCurrent()
                assertTrue(workspace.tabSnapshot().isEmpty())
                assertEquals(0, connector.closeCount)
                assertFalse(completed)
                cleanupScheduler.runCurrent()
                runCurrent()
                assertEquals(1, connector.closeCount)
                assertTrue(completed)
            } finally {
                cleanupScheduler.runCurrent()
                workspace.close()
                runCurrent()
            }
        }

    @Test
    fun `startup settings and shutdown complete in accepted order`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val events = mutableListOf<String>()
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(80, 24),
                    connector = MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            val workspace =
                TerminalWorkspace(
                    listener =
                        object : TerminalWorkspaceListener {
                            override fun tabOpened(tab: TerminalWorkspaceTab) {
                                events += "published"
                            }

                            override fun tabClosed(tabId: String) {
                                events += "removed"
                            }
                        },
                    sessionFactory = { _, _, _ ->
                        events += "created"
                        session
                    },
                    workerDispatcher = dispatcher,
                )
            assertTrue(
                workspace.openTabAsync(
                    profile = {
                        events += "prepared"
                        TerminalProfile("test", "Test", listOf("unused"))
                    },
                    options = TerminalWorkspaceOpenOptions(),
                ) {
                    it.getOrThrow()
                    events += "started"
                },
            )
            assertTrue(
                workspace.applySettingsAsync(TerminalColorPalette(isDark = false), true) {
                    it.getOrThrow()
                    events += "settings"
                },
            )
            assertTrue(
                workspace.closeAsync {
                    it.getOrThrow()
                    events += "closed"
                },
            )
            assertFalse(workspace.openTabAsync({ error("rejected preparation ran") }, TerminalWorkspaceOpenOptions()) { fail() })
            assertTrue(events.isEmpty())
            runCurrent()
            assertEquals(listOf("prepared", "created", "published", "started", "settings", "removed", "closed"), events)
            assertTrue(session.isClosed)
            assertTrue(workspace.tabSnapshot().isEmpty())
        }

    @Test
    fun `failed preparation completes once and later accepted work still runs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val workspace =
                TerminalWorkspace(
                    listener = TerminalWorkspaceListener.NONE,
                    sessionFactory = { _, _, _ -> error("Preparation must fail before creation") },
                    workerDispatcher = dispatcher,
                )
            val expected = IllegalArgumentException("invalid launch")
            val events = mutableListOf<String>()
            assertTrue(
                workspace.openTabAsync({ throw expected }, TerminalWorkspaceOpenOptions()) {
                    assertSame(expected, it.exceptionOrNull())
                    events += "failed"
                },
            )
            assertTrue(
                workspace.closeTabAsync("unknown") {
                    it.getOrThrow()
                    events += "unknown closed"
                },
            )
            assertTrue(
                workspace.closeAsync {
                    it.getOrThrow()
                    events += "workspace closed"
                },
            )
            runCurrent()
            assertEquals(listOf("failed", "unknown closed", "workspace closed"), events)
        }
}
