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
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import java.awt.Point
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Real component lifecycle with queued UI handoffs; detector work never needs a timing assumption. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SwingTerminalHyperlinkLifecycleFixture(
    private val gutterWidth: Int = 0,
    presentation: SwingHyperlinkPresentation = SwingHyperlinkPresentation(isVisible = true),
    activation: SwingHyperlinkActivation = SwingHyperlinkActivation.DIRECT,
    private var osc8Presentation: SwingHyperlinkPresentation? = null,
) : AutoCloseable {
    private val worker = StandardTestDispatcher()
    private val uiTasks = LinkedBlockingQueue<Runnable>()
    private val detectorCalls = AtomicInteger()
    private val opened = ArrayList<String>()
    private var menuRequest: SwingTerminalContextMenuRequest? = null
    private var copiedText: String? = null
    private var pointer: Point? = Point(gutterWidth + 1, 1)
    private var mouseReportingEnabled = true
    private var osc8Activation = SwingHyperlinkActivation.MODIFIER
    private val shellState = TerminalShellIntegrationState()
    private val session =
        TerminalSession.create(
            terminal = TerminalBuffers.create(width = 40, height = 2, maxHistory = 10),
            connector = NoOpConnector,
            workerDispatcher = worker,
            ioDispatcher = worker,
            shellIntegration = if (gutterWidth == 0) null else TerminalShellIntegrationFactory.host(shellState),
        )
    private val container = onEdt { JPanel() }
    private val terminal =
        onEdt {
            SwingTerminal(
                settingsProvider = {
                    SwingSettings.create { draft ->
                        draft.mouseReportingEnabled = mouseReportingEnabled
                        draft.columns = 40
                        draft.rows = 2
                        draft.padding = SwingPadding()
                        draft.cursorBlinkMillis = 0
                        draft.shellIntegrationDecorationGutterWidth = gutterWidth
                        draft.smartSuggestionsEnabled = false
                        draft.osc8HyperlinkPresentation = this.osc8Presentation
                        draft.osc8HyperlinkActivation = osc8Activation
                    }
                },
                hostServices =
                    SwingHostServices.create { draft ->
                        draft.uiDispatcher = { uiTasks.add(it) }
                        draft.hyperlinkHandler = TerminalHyperlinkHandler { opened.add(it) }
                        draft.hyperlinkDetector = { request ->
                            check(!SwingUtilities.isEventDispatchThread())
                            detectorCalls.incrementAndGet()
                            val results = ArrayList<SwingHyperlink>()
                            for (line in 0 until request.lineCount) {
                                val start = request.lineText(line).indexOf(URL)
                                if (start >= 0) {
                                    results.add(
                                        request.hyperlink(
                                            line,
                                            start,
                                            start + URL.length,
                                            {
                                                check(SwingUtilities.isEventDispatchThread())
                                                opened.add(URL)
                                            },
                                            uri = URL,
                                            presentation = presentation,
                                            activation = activation,
                                        ),
                                    )
                                }
                            }
                            results
                        }
                        draft.contextMenuHandler = {
                            menuRequest = it
                            true
                        }
                        draft.clipboardHandler =
                            object : TerminalClipboardHandler {
                                override fun copyText(text: String) {
                                    copiedText = text
                                }

                                override fun readText(): String? = copiedText
                            }
                    },
                searchDispatcher = worker,
                hyperlinkDispatcher = worker,
                pointerPosition = { pointer },
            ).apply {
                size = preferredGridSize(40, 2)
                container.add(this)
                container.addNotify()
                session.onBytes(URL.toByteArray(), 0, URL.length)
                session.requestRender(0)
                worker.scheduler.runCurrent()
                bind(session)
            }
        }

    fun awaitHyperlink() {
        settle()
        check(observation().hasHyperlink) { "Discovery did not restore the hyperlink" }
    }

    fun settle() {
        var turns = 0
        while (true) {
            check(++turns < 1000) { "Discovery did not become idle" }
            worker.scheduler.runCurrent()
            val dispatched =
                onEdt {
                    var count = 0
                    while (true) {
                        val next = uiTasks.poll() ?: break
                        count++
                        next.run()
                    }
                    count
                }
            if (dispatched == 0) return
        }
    }

    fun pointerOutside() = onEdt { pointer = null }

    fun cursorType(): Int = onEdt { terminal.cursor.type }

    fun reloadOsc8Presentation(presentation: SwingHyperlinkPresentation?) =
        onEdt {
            osc8Presentation = presentation
            terminal.reloadSettings()
        }

    fun movePointer(
        x: Int,
        y: Int = 1,
        modifiers: Int = 0,
    ) = onEdt {
        pointer = Point(x, y)
        terminal.dispatchEvent(MouseEvent(terminal, MouseEvent.MOUSE_MOVED, 0L, modifiers, x, y, x, y, 0, false, MouseEvent.NOBUTTON))
    }

    fun reloadOsc8Activation(activation: SwingHyperlinkActivation) =
        onEdt {
            osc8Activation = activation
            terminal.reloadSettings()
        }

    fun clickLink(modifiers: Int = 0) =
        onEdt {
            for (eventId in intArrayOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                terminal.dispatchEvent(MouseEvent(terminal, eventId, 0L, modifiers, 1, 1, 1, 1, 1, false, MouseEvent.BUTTON1))
            }
        }

    fun keyModifier(
        keyCode: Int,
        pressed: Boolean,
        modifiers: Int,
    ) = onEdt {
        val event =
            KeyEvent(
                terminal,
                if (pressed) KeyEvent.KEY_PRESSED else KeyEvent.KEY_RELEASED,
                0L,
                modifiers,
                keyCode,
                KeyEvent.CHAR_UNDEFINED,
            )
        for (listener in terminal.keyListeners) {
            if (pressed) listener.keyPressed(event) else listener.keyReleased(event)
        }
    }

    fun firstRowUnderlinePixels(): IntArray =
        onEdt {
            val image = BufferedImage(terminal.width, terminal.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                terminal.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val metrics = SwingMetrics.from(terminal.getFontMetrics(terminal.font))
            image.getRGB(gutterWidth, metrics.underlineY, URL.length * metrics.cellWidth, 1, null, 0, URL.length * metrics.cellWidth)
        }

    fun allowMouseReporting(enabled: Boolean) =
        onEdt {
            mouseReportingEnabled = enabled
            terminal.reloadSettings()
        }

    fun setMouseReporting(enabled: Boolean) = writeOutput("\u001b[?1003" + if (enabled) "h" else "l")

    fun markFirstRowAsPrompt() {
        onEdt {
            session.readRenderFrame { frame -> shellState.recordPromptStart(frame.lineId(0)) }
        }
        settle()
    }

    fun show(visible: Boolean) = onEdt { container.isVisible = visible }

    fun unbind() = onEdt { terminal.unbind() }

    fun rebind() = onEdt { terminal.bind(session) }

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

    fun captureHyperlink(): SwingTerminalContextHyperlink = onEdt { checkNotNull(contextHyperlink()) }

    fun openCaptured(link: SwingTerminalContextHyperlink): Boolean = onEdt { link.open() }

    fun copyCaptured(link: SwingTerminalContextHyperlink): String? = onEdt { if (link.copyUri()) copiedText else null }

    fun replaceOutput(text: String) = writeOutput("\u001b[H\u001b[2J" + text)

    private fun writeOutput(text: String) {
        val bytes = text.encodeToByteArray()
        session.onBytes(bytes, 0, bytes.size)
        requestFrame()
        settle()
    }

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
        val event =
            MouseEvent(terminal, MouseEvent.MOUSE_PRESSED, 0L, 0, gutterWidth + 1, 1, gutterWidth + 1, 1, 1, true, MouseEvent.BUTTON3)
        for (listener in terminal.mouseListeners) listener.mousePressed(event)
        val released =
            MouseEvent(terminal, MouseEvent.MOUSE_RELEASED, 0L, 0, gutterWidth + 1, 1, gutterWidth + 1, 1, 1, false, MouseEvent.BUTTON3)
        for (listener in terminal.mouseListeners) listener.mouseReleased(released)
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
