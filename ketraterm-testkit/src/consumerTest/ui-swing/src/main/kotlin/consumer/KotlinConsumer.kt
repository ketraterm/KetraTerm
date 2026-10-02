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
package consumer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardReadAuditEvent
import io.github.ketraterm.host.TerminalClipboardReadOutcome
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.protocol.TerminalClipboardSelection
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.SwingUtilities

private const val URL = "https://example.test/consumer"
private const val COLUMNS = 80
private const val ROWS = 3

fun main() =
    runBlocking {
        JavaConsumer.verify()
        val selection = checkNotNull(TerminalClipboardSelection.parse("cp"))
        JavaConsumer.verifyClipboardCallbacks(
            TerminalClipboardReadRequest(selection, TerminalClipboardPermission.ALLOW, 16),
            TerminalClipboardReadAuditEvent(selection, TerminalClipboardReadOutcome.SENT),
        )
        val connector = ConsumerConnector()
        val detector = ConsumerHyperlinkDetector()
        val commandLine = MutableStateFlow<TerminalShellCommandLineSnapshot?>(TerminalShellCommandLineSnapshot("help", 4, 4, 0))
        val shell = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), commandLine)
        TerminalSession.create(TerminalBuffers.create(COLUMNS, ROWS), connector, shellIntegration = shell).use { session ->
            check(session.activeShellCommandLine() == commandLine.value)
            commandLine.value = null
            check(session.activeShellCommandLine() == null)
            session.start(COLUMNS, ROWS)
            withTimeout(20_000) { session.renderGeneration.first { it >= 0L } }
            val terminal =
                onEdt {
                    SwingTerminal(
                        SwingSettingsProvider {
                            SwingSettings(
                                columns = COLUMNS,
                                rows = ROWS,
                                padding = SwingPadding(),
                                shellIntegrationDecorationGutterWidth = 0,
                                osc8HyperlinkActivation = SwingHyperlinkActivation.DIRECT,
                                osc8HyperlinkPresentation =
                                    SwingHyperlinkPresentation(
                                        normal = SwingHyperlinkStyle(underlineStyle = TerminalRenderUnderline.DOTTED),
                                    ),
                            )
                        },
                        SwingHostServices(hyperlinkDetector = detector, scrollbarOverlayEnabled = false),
                    ).apply { size = preferredSize }
                }
            try {
                onEdt {
                    val original = SwingSettings()
                    val updated = original.copy(cursorBlinkMillis = 0)
                    val (font, fallbackFonts) = updated
                    check(font == original.font && fallbackFonts == original.fallbackFonts)
                    check(updated.cursorBlinkMillis == 0 && updated.padding == original.padding)
                    terminal.bind(session)
                    terminal.dispatchPointer(MouseEvent.MOUSE_MOVED)
                }
                // Entry acknowledges installation in the view, not merely completion of detection.
                withTimeout(20_000) { detector.initialHover.await() }
                checkNotNull(
                    session.renderPublisher.readCurrent { frame ->
                        check(frame.hasFrame && frame.columns == COLUMNS && frame.rows >= ROWS)
                        for (column in URL.indices) check(frame.codeWords[column] == URL[column].code)
                    },
                )
                onEdt {
                    check(terminal.cursor.type == Cursor.HAND_CURSOR)
                    terminal.dispatchPointer(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1)
                    terminal.dispatchPointer(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1)
                    check(detector.openedGeneration.get() == 0L)
                }
                withTimeout(20_000) { detector.subscribed.await() }
                detector.updateConfiguration()
                withTimeout(20_000) { detector.refreshedHover.await() }
                onEdt {
                    terminal.dispatchPointer(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1)
                    terminal.dispatchPointer(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1)
                    check(detector.openedGeneration.get() == 1L)
                    check(detector.openCount.get() == 2)
                    terminal.unbind()
                }
                withTimeout(20_000) { detector.unsubscribed.await() }
                check(!session.isClosed && !connector.closed.get())
            } finally {
                onEdt { terminal.dispose() }
            }
            check(!session.isClosed && !connector.closed.get())
        }
        check(connector.closed.get())
    }

private class ConsumerHyperlinkDetector : SwingHyperlinkDetector {
    private val generation = AtomicLong()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val subscribed = CompletableDeferred<Unit>()
    val unsubscribed = CompletableDeferred<Unit>()
    val initialHover = CompletableDeferred<Unit>()
    val refreshedHover = CompletableDeferred<Unit>()
    val openedGeneration = AtomicLong(-1)
    val openCount = AtomicInteger()
    override val configurationGeneration: Long get() = generation.get()
    override val configurationChanges =
        changes
            .onSubscription { subscribed.complete(Unit) }
            .onCompletion { unsubscribed.complete(Unit) }

    suspend fun updateConfiguration() {
        generation.incrementAndGet()
        changes.emit(Unit)
    }

    override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
        check(!SwingUtilities.isEventDispatchThread())
        yield()
        val detectedGeneration = configurationGeneration
        val results = ArrayList<SwingHyperlink>()
        for (line in 0 until request.lineCount) {
            val start = request.lineText(line).indexOf(URL)
            if (start < 0) continue
            val action =
                object : SwingHyperlinkAction {
                    override fun open(): Boolean {
                        check(SwingUtilities.isEventDispatchThread())
                        openedGeneration.set(detectedGeneration)
                        openCount.incrementAndGet()
                        return true
                    }

                    override fun mouseEntered(
                        component: JComponent,
                        x: Int,
                        y: Int,
                        width: Int,
                        height: Int,
                    ) {
                        check(SwingUtilities.isEventDispatchThread())
                        check(width > 0 && height > 0)
                        if (detectedGeneration == 0L) initialHover.complete(Unit) else refreshedHover.complete(Unit)
                    }
                }
            results.add(
                request.hyperlink(
                    line,
                    start,
                    start + URL.length,
                    action,
                    uri = URL,
                    presentation =
                        SwingHyperlinkPresentation(
                            normal = SwingHyperlinkStyle(underlineStyle = TerminalRenderUnderline.SINGLE),
                            hovered = SwingHyperlinkStyle(foregroundArgb = 0xff3366cc.toInt()),
                            isVisible = true,
                        ),
                    activation = SwingHyperlinkActivation.DIRECT,
                ),
            )
        }
        return results
    }
}

private class ConsumerConnector : TerminalConnector {
    val closed = AtomicBoolean()
    private val input = ByteArrayOutputStream()

    override fun start(listener: TerminalConnectorListener) {
        val bytes = "$URL\r\n".toByteArray()
        listener.onBytes(bytes, 0, bytes.size)
    }

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        input.write(bytes, offset, length)
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        check(columns > 0 && rows > 0)
    }

    override fun close() {
        closed.set(true)
    }
}

private fun SwingTerminal.dispatchPointer(
    eventId: Int,
    button: Int = MouseEvent.NOBUTTON,
) {
    val clicks = if (button == MouseEvent.NOBUTTON) 0 else 1
    dispatchEvent(MouseEvent(this, eventId, 0L, 0, 1, 1, 1, 1, clicks, false, button))
}

private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(Callable(action))
    SwingUtilities.invokeAndWait(task)
    return task.get()
}
