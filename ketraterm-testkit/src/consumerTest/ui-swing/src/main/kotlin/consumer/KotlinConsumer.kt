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

import consumer.documentation.createTerminalView
import consumer.documentation.createUnboundTerminalView
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardReadAuditEvent
import io.github.ketraterm.host.TerminalClipboardReadOutcome
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.protocol.TerminalClipboardSelection
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.session.TerminalShellCommandLineState
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
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
        val interactionSettings =
            SwingSettings.create {
                it.mouseReportingEnabled = false
                it.copyOnSelection = true
                it.middleClickPaste = true
                it.columnSpacing = 3
            }
        check(interactionSettings == interactionSettings.copy {})
        check(interactionSettings.columnSpacing == 3 && interactionSettings.middleClickPaste && interactionSettings.copyOnSelection)
        check(!interactionSettings.mouseReportingEnabled)
        JavaConsumer.verify()
        JavaConsumer.verifySessionConstruction(ConsumerConnector())
        val selection = checkNotNull(TerminalClipboardSelection.parse("cp"))
        JavaConsumer.verifyClipboardCallbacks(
            TerminalClipboardReadRequest(selection, TerminalClipboardPermission.ALLOW, 16),
            TerminalClipboardReadAuditEvent(selection, TerminalClipboardReadOutcome.SENT),
        )
        val connector = ConsumerConnector()
        val rejectedListener =
            object : TerminalConnectorListener {
                override fun onBytes(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = error("rejected listener received bytes")

                override fun onClosed(exitCode: Int?) = error("rejected listener received closure")

                override fun onError(error: Throwable) = throw error
            }
        val closedConnector = ConsumerConnector().apply { close() }
        check(runCatching { closedConnector.start(rejectedListener) }.exceptionOrNull() is IllegalStateException)
        val detector = ConsumerHyperlinkDetector()
        val commandLine = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot("help", 4, 4, 0))
        val shellProducer = TerminalShellIntegrationState()
        val shell = TerminalShellIntegrationFactory.host(shellProducer, commandLine)
        val backing = TerminalBuffers.create(COLUMNS, ROWS)
        val renderBuffer: TerminalRenderBuffer = object : TerminalRenderBuffer by backing {}
        val coreOnly: TerminalBuffer = object : TerminalBuffer by renderBuffer {}
        val assembledSession =
            TerminalSession.create(
                coreOnly,
                renderBuffer,
                connector,
                shellIntegration = shell,
                inputEncoderFactory = JavaConsumer.inputEncoderFactory(),
                parserFactory = JavaConsumer.parserFactory(),
            )
        assembledSession.use { session ->
            val shellView: io.github.ketraterm.session.TerminalShellIntegrationView = session.shellIntegrationState
            check(shellView === shellProducer)
            shellProducer.recordCurrentWorkingDirectory("file:///consumer")
            check(shellView.currentWorkingDirectoryUri() == "file:///consumer")
            check(session.activeShellCommandLine() == commandLine.value)
            commandLine.value = null
            check(session.activeShellCommandLine() == null)
            session.start(COLUMNS, ROWS)
            check(session.submitBytes(byteArrayOf(), offset = 0) == io.github.ketraterm.session.TerminalInputAdmission.ACCEPTED)
            check(session.submitInput(emptyList()) == io.github.ketraterm.session.TerminalInputAdmission.ACCEPTED)
            commandLine.value = TerminalShellCommandLineSnapshot("help", 4, 4, 0)
            val edit = checkNotNull(session.captureCommandEdit())
            JavaConsumer.verifyConditionalAdmission(session, edit)
            check(session.submitInput(edit, emptyList()) == TerminalInputAdmission.ACCEPTED)
            commandLine.value = commandLine.value
            check(session.submitInput(edit, emptyList()) == TerminalInputAdmission.STALE_CONTEXT)
            val cancelled = checkNotNull(session.captureCommandEdit())
            cancelled.cancel()
            check(cancelled.isCancelled)
            check(session.submitInput(cancelled, emptyList()) == TerminalInputAdmission.CANCELLED)
            commandLine.value = null
            check(runCatching { connector.start(rejectedListener) }.exceptionOrNull() is IllegalStateException)
            withTimeout(20_000) { session.renderGeneration.first { it >= 0L } }
            check(runCatching { createTerminalView(session) }.exceptionOrNull() is IllegalStateException)
            onEdt {
                check(SwingSettings().useSystemFallbackFonts)
                val documentedView = createTerminalView(session)
                try {
                    check(documentedView.preferredSize.width > 0)
                } finally {
                    documentedView.dispose()
                }
                val provider = SwingShellSuggestionProvider { flowOf(emptyList()) }
                val componentFirst = createUnboundTerminalView(provider)
                try {
                    check(componentFirst.hasShellSuggestionProvider)
                    componentFirst.bind(session)
                    componentFirst.unbind()
                    check(componentFirst.hasShellSuggestionProvider)
                    componentFirst.setShellSuggestionProvider(null)
                    check(!componentFirst.hasShellSuggestionProvider)
                } finally {
                    componentFirst.dispose()
                }
            }
            check(!session.isClosed && !connector.closed.get())
            val terminal =
                onEdt {
                    SwingTerminal(
                        SwingSettingsProvider {
                            SwingSettings.create { draft ->
                                draft.columns = COLUMNS
                                draft.rows = ROWS
                                draft.padding = SwingPadding()
                                draft.shellIntegrationDecorationGutterWidth = 0
                                draft.osc8HyperlinkActivation = SwingHyperlinkActivation.DIRECT
                                draft.osc8HyperlinkPresentation =
                                    SwingHyperlinkPresentation(
                                        normal = SwingHyperlinkStyle(underlineStyle = TerminalRenderUnderline.DOTTED),
                                    )
                            }
                        },
                        SwingHostServices.create { draft ->
                            draft.hyperlinkDetector = detector
                            draft.scrollbarOverlayEnabled = false
                        },
                    ).apply { size = preferredSize }
                }
            try {
                onEdt {
                    val original = SwingSettings()
                    val updated = original.copy { it.cursorBlinkMillis = 0 }
                    check(updated.font == original.font && updated.fallbackFonts == original.fallbackFonts)
                    check(updated.cursorBlinkMillis == 0 && updated.padding == original.padding)
                    terminal.bind(session)
                    terminal.dispatchPointer(MouseEvent.MOUSE_MOVED)
                }
                // Entry acknowledges installation in the view, not merely completion of detection.
                withTimeout(20_000) { detector.initialHover.await() }
                checkNotNull(
                    session.readPublishedFrame { frame ->
                        check(frame.hasFrame && frame.columns == COLUMNS && frame.rows >= ROWS)
                        for (column in URL.indices) check(frame.codeWords[column] == URL[column].code)
                    },
                )

                fun firstCell(): Int {
                    session.readPublishedFrame { return it.codeWords[0] }
                    error("Published frame missing")
                }
                check(firstCell() == URL[0].code)
                onEdt {
                    JavaConsumer.verifySelection(terminal)
                    val range = checkNotNull(terminal.createSelectionRange(1, 0L, 4, 0L, isBlock = true))
                    check(terminal.setSelection(range))
                    check(terminal.currentSelectionRange()?.isBlock == true)
                    terminal.clearSelection()
                    check(terminal.currentSelectionRange() == null)
                    val bounds = Rectangle()
                    check(terminal.copyCellBounds(0, 0, bounds) && bounds.width > 0 && bounds.height > 0)
                    val cell = Point()
                    check(terminal.copyCellPositionAt(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, cell))
                    check(cell.x == 0 && cell.y == 0)
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
    private val started = AtomicBoolean()
    private val input = ByteArrayOutputStream()

    override fun start(listener: TerminalConnectorListener) {
        check(!closed.get()) { "connector is closed" }
        check(started.compareAndSet(false, true)) { "connector already started" }
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
