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
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Real component lifecycle with queued UI handoffs; detector work never needs a timing assumption. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SwingTerminalHyperlinkLifecycleFixture : AutoCloseable {
    private val worker = StandardTestDispatcher()
    private val uiTasks = LinkedBlockingQueue<Runnable>()
    private val detectorCalls = AtomicInteger()
    private val opened = ArrayList<String>()
    private var menuRequest: SwingTerminalContextMenuRequest? = null
    private var copiedText: String? = null
    private val session =
        TerminalSession.create(
            terminal = TerminalBuffers.create(width = 40, height = 2, maxHistory = 10),
            connector = NoOpConnector,
            workerDispatcher = worker,
            ioDispatcher = worker,
        )
    private val container = onEdt { JPanel() }
    private val terminal =
        onEdt {
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(
                        columns = 40,
                        rows = 2,
                        padding = SwingPadding(),
                        cursorBlinkMillis = 0,
                        shellIntegrationDecorationGutterWidth = 0,
                        smartSuggestionsEnabled = false,
                    )
                },
                hostServices =
                    SwingHostServices(
                        uiDispatcher = { uiTasks.add(it) },
                        hyperlinkDetector = { request, sink ->
                            check(!SwingUtilities.isEventDispatchThread())
                            detectorCalls.incrementAndGet()
                            for (line in 0 until request.lineCount) {
                                val start = request.lineText(line).indexOf(URL)
                                if (start >= 0) {
                                    sink.addHyperlink(
                                        request.hyperlink(
                                            line,
                                            start,
                                            start + URL.length,
                                            {
                                                check(SwingUtilities.isEventDispatchThread())
                                                opened.add(URL)
                                            },
                                        ),
                                    )
                                }
                            }
                        },
                        contextMenuHandler = {
                            menuRequest = it
                            true
                        },
                        clipboardHandler =
                            object : TerminalClipboardHandler {
                                override fun copyText(text: String) {
                                    copiedText = text
                                }

                                override fun readText(): String? = copiedText
                            },
                    ),
            ).apply {
                size = preferredGridSize(40, 2)
                container.add(this)
                container.addNotify()
                session.onBytes(URL.toByteArray(), 0, URL.length)
                session.renderPublisher.updateAndPublish(session)
                bind(session)
            }
        }

    fun awaitHyperlink() {
        while (!observation().hasHyperlink) {
            val next = checkNotNull(uiTasks.poll(5, TimeUnit.SECONDS)) { "No UI handoff restored the hyperlink" }
            onEdt { next.run() }
        }
    }

    fun focus(focused: Boolean) =
        onEdt {
            val event = FocusEvent(terminal, if (focused) FocusEvent.FOCUS_GAINED else FocusEvent.FOCUS_LOST)
            for (listener in terminal.focusListeners) {
                if (focused) listener.focusGained(event) else listener.focusLost(event)
            }
        }

    fun detach() = onEdt { container.remove(terminal) }

    fun attach() = onEdt { container.add(terminal) }

    fun requestFrame() {
        session.requestRender(0)
        worker.scheduler.runCurrent()
    }

    fun openHyperlink(): Boolean = onEdt { contextHyperlink()?.open() == true }

    fun openedTargets(): List<String> = onEdt { opened.toList() }

    fun copyAllText(): String? =
        onEdt {
            terminal.selectAll()
            if (terminal.copySelectionToClipboard()) copiedText?.trimEnd() else null
        }

    fun observation(): Observation =
        onEdt {
            var contentGeneration = 0L
            session.readRenderFrame { contentGeneration = it.contentGeneration }
            Observation(
                displayable = terminal.isDisplayable,
                bound = terminal.hasActiveRenderBinding,
                hasHyperlink = contextHyperlink() != null,
                detectorCalls = detectorCalls.get(),
                contentGeneration = contentGeneration,
            )
        }

    override fun close() {
        onEdt {
            terminal.dispose()
            container.removeNotify()
            session.close()
        }
        worker.scheduler.runCurrent()
        onEdt {
            while (true) (uiTasks.poll() ?: break).run()
        }
    }

    private fun contextHyperlink(): SwingTerminalContextHyperlink? {
        menuRequest = null
        val event = MouseEvent(terminal, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, 1, 1, true, MouseEvent.BUTTON3)
        for (listener in terminal.mouseListeners) listener.mousePressed(event)
        return checkNotNull(menuRequest) { "The host did not receive the context-menu request" }.hyperlink
    }

    data class Observation(
        val displayable: Boolean,
        val bound: Boolean,
        val hasHyperlink: Boolean,
        val detectorCalls: Int,
        val contentGeneration: Long,
    )

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }

    companion object {
        const val URL = "https://example.com/lifecycle"

        private fun <T> onEdt(action: () -> T): T {
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }
    }
}
