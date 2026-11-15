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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingTerminalNativeActionsTest {
    @Test
    fun `queued browser activation is cancelled when its binding ends`() {
        val worker = StandardTestDispatcher()
        val handler = SystemTerminalHyperlinkHandler(browse = { error("Obsolete activation reached the browser") }, ioDispatcher = worker)
        SwingTerminalHyperlinkLifecycleFixture(hyperlinkHandler = handler).use { fixture ->
            fixture.replaceOutput("\u001b]8;;https://example.test/\u0007link\u001b]8;;\u0007")
            fixture.settle()
            val link = fixture.captureHyperlink()
            assertTrue(fixture.openCaptured(link))
            fixture.unbind()
            assertFalse(fixture.openCaptured(link))
            worker.scheduler.runCurrent()
            fixture.settle()
        }
    }

    @Test
    fun `blocked browser activation leaves EDT responsive without queuing repeated clicks`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        val handler =
            SystemTerminalHyperlinkHandler(browse = {
                assertFalse(SwingUtilities.isEventDispatchThread())
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                true
            }, ioDispatcher = worker)
        try {
            SwingTerminalHyperlinkLifecycleFixture(hyperlinkHandler = handler).use { fixture ->
                fixture.replaceOutput("\u001b]8;;https://example.test/\u0007link\u001b]8;;\u0007")
                fixture.settle()
                val link = fixture.captureHyperlink()
                assertTrue(fixture.openCaptured(link))
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFalse(fixture.openCaptured(link))
                fixture.unbind()
                assertFalse(fixture.openCaptured(link))
                release.countDown()
                executor.submit {}.get(10, TimeUnit.SECONDS)
                fixture.settle()
                assertEquals(1, calls.get())
            }
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @ParameterizedTest
    @CsvSource("clipboard,deliver", "primary,deliver", "clipboard,unbind", "clipboard,close", "primary,dispose", "clipboard,rebind")
    fun `blocked paste remains responsive and never reaches an obsolete binding`(
        source: String,
        transition: String,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val scheduler = StandardTestDispatcher()
        val output = ByteArrayOutputStream()
        val clipboard =
            object : Clipboard("test") {
                override fun getContents(requestor: Any?): Transferable {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return StringSelection("a\u0001b\nλ 🚀")
                }
            }
        val connector =
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) = Unit

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = output.write(bytes, offset, length)

                override fun resize(
                    columns: Int,
                    rows: Int,
                ) = Unit

                override fun close() = Unit
            }
        val session =
            TerminalSession.create(
                TerminalBuffers.create(20, 4),
                connector,
                workerDispatcher = scheduler,
                ioDispatcher = scheduler,
                cleanupDispatcher = scheduler,
            )
        val terminal =
            onEdt {
                session.start()
                scheduler.scheduler.runCurrent()
                SwingTerminal(
                    {
                        SwingSettings.create {
                            it.columns = 20
                            it.rows = 4
                            it.middleClickPaste = true
                            it.pasteControlPolicy = io.github.ketraterm.input.policy.PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF
                            it.middleClickPasteSource = SwingPasteSource.PRIMARY_SELECTION
                            it.promptDecoration = SwingPromptDecoration.NONE
                            it.stickyCommandHeadersEnabled = false
                        }
                    },
                    SwingHostServices.create {
                        it.uiDispatcher = CONTROLLED_SWING_UI_DISPATCHER
                        it.clipboardHandler = SystemTerminalClipboardHandler({ clipboard }, { clipboard }, worker)
                    },
                    searchDispatcher = scheduler,
                    mutationDispatcher = scheduler,
                ).also {
                    it.bind(session)
                    scheduler.scheduler.runCurrent()
                }
            }
        try {
            onEdt {
                if (source == "clipboard") {
                    assertTrue(terminal.pasteClipboardText())
                } else {
                    terminal.dispatchEvent(MouseEvent(terminal, MouseEvent.MOUSE_PRESSED, 0, 0, 4, 4, 1, false, MouseEvent.BUTTON2))
                    terminal.dispatchEvent(MouseEvent(terminal, MouseEvent.MOUSE_RELEASED, 0, 0, 4, 4, 1, false, MouseEvent.BUTTON2))
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            onEdt {
                assertFalse(terminal.pasteClipboardText(), "A pending native read must bound repeated paste gestures")
                when (transition) {
                    "unbind" -> terminal.unbind()
                    "close" -> session.close()
                    "dispose" -> terminal.dispose()
                    "rebind" -> {
                        terminal.unbind()
                        terminal.bind(session)
                    }
                }
                scheduler.scheduler.runCurrent()
                assertEquals(0, output.size())
            }
            release.countDown()
            executor.submit {}.get(10, TimeUnit.SECONDS)
            onEdt {
                scheduler.scheduler.runCurrent()
                assertEquals(if (transition == "deliver") "ab\nλ 🚀" else "", output.toString(Charsets.UTF_8))
            }
        } finally {
            release.countDown()
            onEdt {
                terminal.dispose()
                session.close()
                scheduler.scheduler.runCurrent()
            }
            worker.close()
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
