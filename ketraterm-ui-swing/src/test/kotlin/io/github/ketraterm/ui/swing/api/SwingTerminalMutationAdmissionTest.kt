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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.ComponentEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalMutationAdmissionTest {
    @Test
    fun `scrolling while resize awaits admission preserves the later viewport intent`() {
        val dispatcher = StandardTestDispatcher()
        val session = session(workerDispatcher = dispatcher)
        val view = edt { SwingTerminal({ settings() }) }
        val initialized = CompletableFuture<Throwable?>()
        val completed = CompletableFuture<Throwable?>()
        val bytes = (0 until 16).joinToString("\r\n") { "row-$it abc" }.encodeToByteArray()
        session.onBytes(bytes, 0, bytes.size)
        try {
            edt {
                view.size = view.preferredGridSize(10, 2)
                view.bind(session) { initialized.complete(it) }
            }
            assertNull(initialized.get(10, TimeUnit.SECONDS))
            edt { dispatcher.scheduler.runCurrent() }
            val gate = holdAdmission(session)
            try {
                var expectedLineId = 0L
                edt {
                    view.scrollFromScrollbar(3, true)
                    dispatcher.scheduler.runCurrent()
                    view.size = view.preferredGridSize(7, 2)
                    view.resizeSessionToVisibleGrid()
                    view.scrollFromScrollbar(8, true)
                    session.readRenderFrame(view.viewportState().renderOffset, 1) { expectedLineId = it.lineId(0) }
                    view.setHostPolicy(HostPolicy()) { completed.complete(it) }
                }
                assertTrue(edt { SwingUtilities.isEventDispatchThread() })
                assertFalse(completed.isDone)
                gate.release()
                assertNull(completed.get(10, TimeUnit.SECONDS))
                edt {
                    dispatcher.scheduler.runCurrent()
                    session.readRenderFrame(view.viewportState().renderOffset, 1) {
                        assertEquals(expectedLineId, it.lineId(0))
                        assertEquals(7, it.columns)
                    }
                }
            } finally {
                gate.release()
            }
        } finally {
            edt { view.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `returning to the installed size supersedes an unapplied resize`() {
        val dispatcher = StandardTestDispatcher()
        val session = session()
        try {
            edt {
                val view =
                    SwingTerminal({ settings() }, SwingHostServices(), searchDispatcher = dispatcher, mutationDispatcher = dispatcher)
                try {
                    view.size = view.preferredGridSize(10, 2)
                    view.bind(session)
                    dispatcher.scheduler.runCurrent()
                    for (columns in listOf(12, 10)) {
                        view.size = view.preferredGridSize(columns, 2)
                        val event = ComponentEvent(view, ComponentEvent.COMPONENT_RESIZED)
                        view.componentListeners.forEach { it.componentResized(event) }
                    }
                    dispatcher.scheduler.runCurrent()
                    session.readRenderFrame {
                        assertEquals(10, it.columns)
                        assertEquals(2, it.rows)
                    }
                } finally {
                    view.dispose()
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `binding settings geometry and window state leave EDT available while admission is held`() {
        val reply = CompletableFuture<String>()
        val session = session { reply.complete(it) }
        var settings = settings()
        val view = edt { SwingTerminal({ settings }) }
        val gate = holdAdmission(session)
        val completed = CompletableFuture<Throwable?>()
        try {
            edt {
                view.size = view.preferredGridSize(10, 2)
                view.bind(session)
                settings =
                    settings.copy {
                        it.treatAmbiguousAsWide = true
                        it.columnSpacing += 1
                    }
                view.reloadSettings()
                view.updateWindowMinimizedOnEdt(true)
                view.size = view.preferredGridSize(12, 3)
                view.resizeSessionToVisibleGrid()
                view.setHostPolicy(HostPolicy()) { completed.complete(it) }
            }
            assertTrue(edt { SwingUtilities.isEventDispatchThread() }, "A subsequent EDT marker must run before gate release")
            assertFalse(completed.isDone, "Policy application cannot pass the held admission gate")
            gate.release()
            assertNull(completed.get(10, TimeUnit.SECONDS))
            assertTrue(session.modeSnapshot.treatAmbiguousAsWide)
            session.readRenderFrame {
                assertEquals(12, it.columns)
                assertEquals(3, it.rows)
            }
            val query = "\u001B[11t".encodeToByteArray()
            session.onBytes(query, 0, query.size)
            assertEquals("\u001B[2t", reply.get(10, TimeUnit.SECONDS))
        } finally {
            gate.release()
            edt { view.dispose() }
            session.close()
        }
    }

    @Test
    fun `rebinding rejects old settings at actual gate admission`() {
        val first = session()
        val second = session()
        val view = edt { SwingTerminal({ settings().copy { it.treatAmbiguousAsWide = true } }) }
        val gate = holdAdmission(first)
        val completed = CompletableFuture<Throwable?>()
        try {
            edt {
                view.size = view.preferredGridSize(12, 3)
                view.bind(first)
                view.bind(second)
                view.setHostPolicy(HostPolicy()) { completed.complete(it) }
            }
            assertTrue(edt { SwingUtilities.isEventDispatchThread() })
            gate.release()
            assertNull(completed.get(10, TimeUnit.SECONDS))
            assertFalse(first.modeSnapshot.treatAmbiguousAsWide)
            first.readRenderFrame {
                assertEquals(10, it.columns)
                assertEquals(2, it.rows)
            }
            assertTrue(second.modeSnapshot.treatAmbiguousAsWide)
            second.readRenderFrame {
                assertEquals(12, it.columns)
                assertEquals(3, it.rows)
            }
        } finally {
            gate.release()
            edt { view.dispose() }
            first.close()
            second.close()
        }
    }

    @Test
    fun `disposal cancels accepted work without waiting for admission or closing the session`() {
        val session = session()
        val view = edt { SwingTerminal({ settings().copy { it.treatAmbiguousAsWide = true } }) }
        val gate = holdAdmission(session)
        val completed = CompletableFuture<Throwable?>()
        try {
            edt {
                view.bind(session)
                view.setHostPolicy(HostPolicy()) { completed.complete(it) }
                view.dispose()
            }
            assertTrue(edt { SwingUtilities.isEventDispatchThread() })
            assertInstanceOf(CancellationException::class.java, completed.get(10, TimeUnit.SECONDS))
            assertFalse(session.isClosed)
            gate.release()
            assertFalse(session.modeSnapshot.treatAmbiguousAsWide)
        } finally {
            gate.release()
            edt { view.dispose() }
            session.close()
        }
    }

    private fun settings() =
        SwingSettings.create {
            it.columns = 10
            it.rows = 2
            it.padding = SwingPadding()
            it.alternateScreenPadding = SwingPadding()
            it.shellIntegrationDecorationGutterWidth = 0
            it.cursorBlinkMillis = 0
        }

    private fun session(
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        onWrite: (String) -> Unit = {},
    ) = TerminalSession
        .create(
            TerminalBuffers.create(10, 2),
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) = Unit

                override fun resize(
                    columns: Int,
                    rows: Int,
                ) = Unit

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    onWrite(bytes.decodeToString(offset, offset + length))
                }

                override fun close() = Unit
            },
            workerDispatcher = workerDispatcher,
        ).also { it.start(10, 2) }

    private fun holdAdmission(session: TerminalSession): Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker =
            FutureTask {
                session.withMutationAdmission({ true }) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release session admission" }
                }
            }
        thread(name = "test-graphics-admission") { worker.run() }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "Session admission was not entered")
        return Gate(release, worker)
    }

    private class Gate(
        private val release: CountDownLatch,
        private val worker: FutureTask<*>,
    ) {
        fun release() {
            release.countDown()
            worker.get(10, TimeUnit.SECONDS)
        }
    }

    private fun <T> edt(action: () -> T): T {
        val result = FutureTask(action)
        SwingUtilities.invokeLater(result)
        return result.get(10, TimeUnit.SECONDS)
    }
}
