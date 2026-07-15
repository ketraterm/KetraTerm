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

import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalHostResponseReader
import io.github.ketraterm.host.*
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.transport.checkBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime terminal session that binds core, parser, input encoding, and a
 * transport connector.
 *
 * The connector owns transport threads. This session owns parser/core mutation
 * serialization and all host-bound write ordering.
 *
 * A session publishes one active render viewport. Two independently scrolling
 * renderers must use separate sessions so their viewport requests do not race.
 *
 * @property terminal public terminal buffer mutated by host output.
 * @property renderPublisher the render publisher responsible for frame updates.
 * @property shellIntegrationState shared host-side prompt and command marker state.
 * @property workerDispatcher non-owned dispatcher used for session background work.
 */
class TerminalSession(
    val terminal: TerminalBuffer,
    val renderPublisher: TerminalRenderPublisher,
    private val renderReader: TerminalRenderFrameReader,
    private val responseReader: TerminalHostResponseReader,
    private val connector: TerminalConnector,
    private val parser: TerminalOutputParser,
    private val inputEncoder: TerminalInputEncoder,
    private val hyperlinkResolver: TerminalHyperlinkResolver = TerminalHyperlinkResolver.NONE,
    private val outboundWriteLock: Any = Any(),
    val shellIntegrationState: TerminalShellIntegrationState = TerminalShellIntegrationState(),
    private val hostCommandAdapter: HostCommandAdapter? = null,
    private var inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TerminalConnectorListener,
    TerminalInputEncoder,
    TerminalRenderFrameReader,
    AutoCloseable {
    private val pendingRenderRequest = AtomicLong(packRenderRequest(scrollbackOffset = 0, viewportRows = 0))
    private val pendingRenderGeneration = AtomicLong(0)

    private val mutationLock = Any()
    private val responseScratch = ByteArray(RESPONSE_BUFFER_SIZE)
    private val synchronizedTimeoutJob = AtomicReference<Job?>(null)
    private val renderRequests = Channel<Unit>(Channel.CONFLATED)
    private val immediateRenderRequests = Channel<Unit>(Channel.CONFLATED)
    private val sessionJob = SupervisorJob()
    private val sessionScope =
        CoroutineScope(
            sessionJob +
                workerDispatcher +
                CoroutineName("terminal-session-${SESSION_COUNTER.getAndIncrement()}"),
        )
    private val mutableState = MutableStateFlow<TerminalSessionState>(TerminalSessionState.Created)
    private val mutableRenderGeneration = MutableStateFlow(NO_RENDER_GENERATION)

    /** Lifecycle state retained for current and future collectors. */
    val state: StateFlow<TerminalSessionState> = mutableState.asStateFlow()

    /**
     * Latest successfully published render generation, or `-1` before the
     * first frame is available.
     */
    val renderGeneration: StateFlow<Long> = mutableRenderGeneration.asStateFlow()

    internal val isCoroutineScopeActive: Boolean
        get() = sessionJob.isActive

    init {
        sessionScope.launch {
            for (signal in renderRequests) {
                drainRenderRequests()
            }
        }
    }

    /**
     * Returns true after either local shutdown, remote closure, or transport
     * failure has made the session unable to accept more input.
     */
    val isClosed: Boolean
        get() = isSessionClosed()

    /**
     * Remote process exit code after [onClosed] receives one.
     */
    val exitCode: Int?
        get() = (state.value as? TerminalSessionState.Closed)?.event?.exitCode

    /**
     * Transport failure reported by [onError], or `null` when the remote closed
     * normally or the session was locally closed.
     */
    val failure: Throwable?
        get() = (state.value as? TerminalSessionState.Closed)?.event?.failure

    /**
     * Resolves a primitive render-frame hyperlink id to a target URI.
     *
     * UI components call this from explicit user activation paths after reading
     * [TerminalRenderCache.hyperlinkIds]. The lookup is outside the paint loop
     * and returns `null` when metadata is unavailable or was evicted by policy.
     *
     * @param hyperlinkId cell hyperlink id; `0` means no hyperlink.
     * @return target URI, or `null` if none.
     */
    fun hyperlinkUri(hyperlinkId: Int): String? = hyperlinkResolver.uriForHyperlinkId(hyperlinkId)

    /**
     * Returns the latest valid OSC 7 current-working-directory URI.
     *
     * @return absolute `file://` URI reported by the shell, or `null` before
     *   the shell reports one.
     */
    fun currentWorkingDirectoryUri(): String? = shellIntegrationState.currentWorkingDirectoryUri()

    /**
     * Starts the connector after resizing core and transport to [columns] x
     * [rows].
     *
     * @param columns initial terminal width count.
     * @param rows initial terminal height count.
     */
    fun start(
        columns: Int,
        rows: Int,
    ) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        check(mutableState.compareAndSet(TerminalSessionState.Created, TerminalSessionState.Running)) {
            "session already started or closed"
        }

        synchronized(mutationLock) {
            terminal.resize(columns, rows)
        }
        connector.resize(columns, rows)
        connector.start(this)
    }

    /**
     * Resizes core and the active connector, then requests a new published frame.
     *
     * @param columns target terminal column width.
     * @param rows target terminal row height.
     * @param oldScrollbackOffset The scrollback offset that was active in the UI before this resize.
     *   Pass 0 if the viewport was at the live screen (no scrollback).
     * @return A [Pair] of (newScrollbackOffset, newHistorySize) that the UI should apply to
     *   re-anchor the viewport to the same logical content after reflow.
     */
    fun resize(
        columns: Int,
        rows: Int,
        oldScrollbackOffset: Int = 0,
    ): Pair<Int, Int> {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }

        val result: Pair<Int, Int>
        synchronized(mutationLock) {
            result = terminal.resize(columns, rows, oldScrollbackOffset)
        }
        connector.resize(columns, rows)
        invalidateRender()
        return result
    }

    /**
     * Applies the host's East Asian Ambiguous width policy for future writes.
     *
     * Existing stored content keeps its current cell shape; changing this
     * setting does not reinterpret already-written rows.
     *
     * @param enabled whether ambiguous codepoints occupy two cells.
     */
    fun setTreatAmbiguousAsWide(enabled: Boolean) {
        synchronized(mutationLock) {
            terminal.setTreatAmbiguousAsWide(enabled)
        }
    }

    /**
     * Sets the theme-configured color palette for the session.
     *
     * This method propagates the theme changes down to the core under the
     * mutation lock.
     *
     * @param palette the theme color palette configuration.
     */
    fun setThemePalette(palette: TerminalColorPalette) {
        synchronized(mutationLock) {
            terminal.setThemePalette(palette)
        }
        invalidateRender()
    }

    /**
     * Updates the current and default cursor shape for the session.
     */
    fun setCursorShape(shape: TerminalRenderCursorShape) {
        synchronized(mutationLock) {
            terminal.setDefaultCursorShape(shape)
            terminal.setCursorShape(shape)
        }
        invalidateRender()
    }

    /**
     * Updates the active host security policy dynamically.
     *
     * @param policy new security policy.
     */
    fun setHostPolicy(policy: HostPolicy) {
        synchronized(mutationLock) {
            hostCommandAdapter?.setHostPolicy(policy)
        }
    }

    /**
     * Updates the active terminal input policy dynamically.
     *
     * @param policy new input policy.
     */
    override fun setInputPolicy(policy: TerminalInputPolicy) {
        synchronized(outboundWriteLock) {
            inputPolicy = policy
            inputEncoder.setInputPolicy(policy)
        }
    }

    /**
     * Updates only the active paste sanitization policy.
     *
     * Other host-bound input behavior, including PTY-specific Return handling,
     * is preserved. The update is serialized with input encoding because the
     * default encoder owns reusable scratch buffers.
     *
     * @param policy new paste sanitization policy.
     */
    fun setPasteSanitizationPolicy(policy: PasteSanitizationPolicy) {
        synchronized(outboundWriteLock) {
            val next = inputPolicy.copy(pasteSanitizationPolicy = policy)
            inputPolicy = next
            inputEncoder.setInputPolicy(next)
        }
    }

    /**
     * Encodes a key event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    private inline fun withInputLock(block: TerminalInputEncoder.() -> Unit) {
        synchronized(outboundWriteLock) {
            if (isAcceptingInput()) {
                inputEncoder.block()
            }
        }
    }

    override fun encodeKey(event: TerminalKeyEvent) {
        withInputLock { encodeKey(event) }
    }

    /**
     * Encodes a paste event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodePaste(event: TerminalPasteEvent) {
        withInputLock { encodePaste(event) }
    }

    /**
     * Encodes a focus event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodeFocus(event: TerminalFocusEvent) {
        withInputLock { encodeFocus(event) }
    }

    /**
     * Encodes a mouse event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodeMouse(event: TerminalMouseEvent) {
        withInputLock { encodeMouse(event) }
    }

    /**
     * Consumes host bytes synchronously, mutating parser/core before returning.
     */
    override fun onBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        bytes.checkBounds(offset, length)
        if (isSessionClosed()) return

        synchronized(mutationLock) {
            parser.accept(bytes, offset, length)
        }

        drainResponses()
        invalidateRender(immediate = false)
    }

    /**
     * Invalidates the current requested viewport without changing its offset or
     * row count.
     */
    private fun invalidateRender(immediate: Boolean = true) {
        if (isSessionClosed()) return
        pendingRenderGeneration.incrementAndGet()
        renderRequests.trySend(Unit)
        if (immediate) immediateRenderRequests.trySend(Unit)
    }

    /**
     * Requests a render-cache publication for a caller-owned scrollback viewport.
     *
     * The offset is transient render request state, not terminal state. UI
     * layers own scrollback policy and pass the desired offset for each
     * publication they need. A newer request replaces the active render
     * viewport for this session.
     *
     * @param scrollbackOffset logical whole-row offset from live viewport.
     */
    fun requestRender(scrollbackOffset: Int) {
        requestRender(scrollbackOffset, viewportRows = 0)
    }

    /**
     * Requests a render-cache publication with optional render-only viewport
     * overscan rows.
     *
     * [viewportRows] greater than zero asks the render reader to expose that
     * many rows when possible. This is UI composition state and must not resize
     * the terminal or connector.
     *
     * @param scrollbackOffset logical whole-row offset from live viewport.
     * @param viewportRows row count requested from the render cache.
     */
    fun requestRender(
        scrollbackOffset: Int,
        viewportRows: Int,
    ) {
        if (isSessionClosed()) return
        pendingRenderRequest.set(
            packRenderRequest(
                scrollbackOffset = scrollbackOffset.coerceAtLeast(0),
                viewportRows = viewportRows.coerceAtLeast(0),
            ),
        )
        invalidateRender()
    }

    private fun scheduleSynchronizedOutputTimeout() {
        if (synchronizedTimeoutJob.get() != null || isSessionClosed()) return

        val timeoutJob =
            sessionScope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(SYNCHRONIZED_OUTPUT_TIMEOUT_MS.milliseconds)
                    var changed = false
                    synchronized(mutationLock) {
                        if (terminal.getModeSnapshot().isSynchronizedOutput) {
                            terminal.setSynchronizedOutput(false)
                            changed = true
                        }
                    }
                    if (changed) invalidateRender()
                } finally {
                    synchronizedTimeoutJob.compareAndSet(currentCoroutineContext().job, null)
                }
            }

        if (synchronizedTimeoutJob.compareAndSet(null, timeoutJob)) {
            timeoutJob.start()
        } else {
            timeoutJob.cancel()
        }
    }

    private fun cancelSynchronizedOutputTimeout() {
        synchronizedTimeoutJob.getAndSet(null)?.cancel()
    }

    private suspend fun drainRenderRequests() {
        var publishedGeneration = mutableRenderGeneration.value
        var failedGeneration = NO_RENDER_GENERATION
        while (!isSessionClosed()) {
            currentCoroutineContext().ensureActive()
            immediateRenderRequests.tryReceive()
            val generation = pendingRenderGeneration.get()
            if (generation == publishedGeneration || generation == failedGeneration) return

            val request = pendingRenderRequest.get()
            val offset = unpackScrollbackOffset(request)
            val rows = unpackViewportRows(request)

            val modeSnapshot = terminal.getModeSnapshot()
            if (modeSnapshot.isSynchronizedOutput) {
                scheduleSynchronizedOutputTimeout()
                return
            }

            cancelSynchronizedOutputTimeout()

            try {
                renderPublisher.updateAndPublish(this, offset, rows)
                publishedGeneration = generation
                currentCoroutineContext().ensureActive()
                mutableRenderGeneration.value = generation
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }

            // The first publication after an idle period is immediate. While
            // host output remains active, wait one display interval before
            // sampling the packed request and generation again. Explicit UI
            // requests interrupt the wait so scrolling and resizing stay
            // responsive. The last host invalidation becomes a trailing frame.
            withTimeoutOrNull(RENDER_PUBLICATION_INTERVAL_MS.milliseconds) {
                immediateRenderRequests.receiveCatching()
            }
        }
    }

    /**
     * Reads a short-lived render frame while holding the terminal mutation lock.
     *
     * UI callers should use this session-level reader rather than reading the
     * core buffer directly, so parser output and resize cannot mutate the grid
     * while a renderer copies primitive row data.
     *
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
        readRenderFrame(scrollbackOffset = 0, consumer = consumer)
    }

    /**
     * Reads a render frame with a specified scrollback offset.
     *
     * @param scrollbackOffset whole-row offset from the live viewport.
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrame(scrollbackOffset, consumer)
        }
    }

    /**
     * Reads a render frame with a specified scrollback offset and viewport rows limit.
     *
     * @param scrollbackOffset whole-row offset from the live viewport.
     * @param viewportRows row count requested.
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrame(
        scrollbackOffset: Int,
        viewportRows: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrame(scrollbackOffset, viewportRows, consumer)
        }
    }

    /**
     * Reads an absolute terminal row range while holding the mutation lock.
     *
     * Resolving absolute rows and copying their frame are one atomic operation,
     * so incoming output cannot shift a selection onto unrelated scrollback
     * rows between coordinate conversion and frame access.
     *
     * @param startAbsoluteRow inclusive first requested absolute row.
     * @param endAbsoluteRow inclusive last requested absolute row.
     * @param consumer the frame consumer to invoke.
     */
    override fun readRenderFrameForAbsoluteRange(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
        }
    }

    /**
     * Records remote closure and closes the parser exactly once.
     */
    override fun onClosed(exitCode: Int?) {
        transitionToClosed(TerminalSessionCloseEvent(exitCode = exitCode, failure = null, locallyRequested = false))
    }

    /**
     * Records transport failure and treats it as remote closure without a
     * process exit code.
     *
     * @param error the transport failure exception.
     */
    override fun onError(error: Throwable) {
        transitionToClosed(TerminalSessionCloseEvent(exitCode = null, failure = error, locallyRequested = false))
    }

    /**
     * Requests local connector shutdown and closes parser input exactly once.
     */
    override fun close() {
        transitionToClosed(
            event = TerminalSessionCloseEvent(exitCode = null, failure = null, locallyRequested = true),
            closeConnector = true,
        )
    }

    private fun drainResponses() {
        while (!isSessionClosed()) {
            val count =
                synchronized(mutationLock) {
                    responseReader.readResponseBytes(responseScratch, 0, responseScratch.size)
                }

            if (count <= 0) return

            synchronized(outboundWriteLock) {
                if (!isSessionClosed()) {
                    connector.write(responseScratch, 0, count)
                }
            }
        }
    }

    private fun transitionToClosed(
        event: TerminalSessionCloseEvent,
        closeConnector: Boolean = false,
    ) {
        while (true) {
            val current = mutableState.value
            if (current is TerminalSessionState.Closed) return
            if (mutableState.compareAndSet(current, TerminalSessionState.Closed(event))) break
        }

        try {
            if (closeConnector) connector.close()
        } finally {
            cleanupParser()
        }
    }

    private fun cleanupParser() {
        cancelSynchronizedOutputTimeout()
        renderRequests.close()
        immediateRenderRequests.close()
        sessionScope.cancel()
        synchronized(mutationLock) {
            parser.endOfInput()
        }
    }

    private fun isSessionClosed(): Boolean = state.value is TerminalSessionState.Closed

    private fun isAcceptingInput(): Boolean = state.value === TerminalSessionState.Running

    companion object {
        private val SESSION_COUNTER =
            AtomicInteger(1)
        private const val RESPONSE_BUFFER_SIZE: Int = 1024
        private const val NO_RENDER_GENERATION: Long = -1L
        internal const val RENDER_PUBLICATION_INTERVAL_MS: Long = 16L
        private const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS: Long = 100L

        private fun packRenderRequest(
            scrollbackOffset: Int,
            viewportRows: Int,
        ): Long = (scrollbackOffset.toLong() shl 32) or (viewportRows.toLong() and 0xffff_ffffL)

        private fun unpackScrollbackOffset(request: Long): Int = (request ushr 32).toInt()

        private fun unpackViewportRows(request: Long): Int = request.toInt()

        /**
         * Creates a production session with the standard parser, host
         * sink, and default input encoder.
         *
         * @param terminal core buffer to mutate.
         * @param connector transport connector.
         * @param hostEvents metadata events target.
         * @param hostPolicy safety policy.
         * @param inputPolicy key-encoding policy.
         * @param kittyKeyboardSupportedFlags progressive Kitty keyboard flags
         * the active input host can provide truthfully. Defaults to the
         * conservative portable-host profile.
         * @param workerDispatcher non-owned dispatcher used for render publication and timeouts.
         * @return standard production terminal session.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            terminal: TerminalBuffer,
            connector: TerminalConnector,
            hostEvents: HostEventSink = HostEventSink.NONE,
            hostPolicy: HostPolicy = HostPolicy(),
            inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
            kittyKeyboardSupportedFlags: Int = KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK,
            workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): TerminalSession {
            val outboundWriteLock = Any()
            val hostOutput = ConnectorTerminalHostOutput(connector, outboundWriteLock)
            val renderReader =
                terminal as? TerminalRenderFrameReader
                    ?: error("terminal must implement TerminalRenderFrameReader")
            val shellIntegrationState = TerminalShellIntegrationState()
            val recordingHostEvents =
                ShellIntegrationRecordingHostEventSink(
                    delegate = hostEvents,
                    renderReader = renderReader,
                    state = shellIntegrationState,
                )
            val sink = HostCommandAdapter(terminal, recordingHostEvents, hostPolicy, kittyKeyboardSupportedFlags)
            val parser = TerminalParsers.create(sink)
            val inputEncoder = TerminalInputEncoders.create(terminal, hostOutput, inputPolicy)

            val renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height)

            return TerminalSession(
                terminal = terminal,
                renderPublisher = renderPublisher,
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = inputEncoder,
                hyperlinkResolver = TerminalHyperlinkResolver(sink::hyperlinkUri),
                outboundWriteLock = outboundWriteLock,
                shellIntegrationState = shellIntegrationState,
                hostCommandAdapter = sink,
                inputPolicy = inputPolicy,
                workerDispatcher = workerDispatcher,
            )
        }
    }
}

private class ShellIntegrationRecordingHostEventSink(
    private val delegate: HostEventSink,
    private val renderReader: TerminalRenderFrameReader,
    private val state: TerminalShellIntegrationState,
) : HostEventSink {
    private val commandTextExtractor = ShellIntegrationCommandTextExtractor()
    private var promptEndLineId = NO_LINE_ID
    private var promptEndColumn = 0
    private var promptStartedForCommandText = false
    private var promptStartLineId = NO_LINE_ID
    private var promptStartColumn = 0
    private var promptScanCodeWords = IntArray(0)
    private var promptScanAttrWords = LongArray(0)
    private var promptScanFlags = IntArray(0)

    override fun bell() {
        delegate.bell()
    }

    override fun iconTitleChanged(title: String) {
        delegate.iconTitleChanged(title)
    }

    override fun windowTitleChanged(title: String) {
        delegate.windowTitleChanged(title)
    }

    override fun currentWorkingDirectoryChanged(uri: String) {
        state.recordCurrentWorkingDirectory(uri)
        delegate.currentWorkingDirectoryChanged(uri)
    }

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) {
        delegate.resizeWindow(rows, columns)
    }

    override fun moveWindow(
        x: Int,
        y: Int,
    ) {
        delegate.moveWindow(x, y)
    }

    override fun minimizeWindow() {
        delegate.minimizeWindow()
    }

    override fun deminimizeWindow() {
        delegate.deminimizeWindow()
    }

    override fun raiseWindow() {
        delegate.raiseWindow()
    }

    override fun lowerWindow() {
        delegate.lowerWindow()
    }

    override fun setMaximized(maximize: Boolean) {
        delegate.setMaximized(maximize)
    }

    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
        var cursorLineId = NO_LINE_ID
        var previousLineId = NO_LINE_ID
        var cursorColumn = 0
        var bottomAbsoluteRow = 0L
        var commandText: String? = null
        var visiblePromptStartLineId = NO_LINE_ID
        var historySize = 0
        var liveRows = 0
        renderReader.readRenderFrame(scrollbackOffset = 0) { frame ->
            historySize = frame.historySize
            liveRows = frame.rows
            val firstVisibleRow = frame.discardedCount + frame.historySize
            val cursor = frame.cursor
            cursorColumn = cursor.column
            if (cursor.row in 0 until frame.rows) {
                cursorLineId = frame.lineId(cursor.row)
            }
            if (cursor.row > 0 && cursor.row - 1 < frame.rows) {
                previousLineId = frame.lineId(cursor.row - 1)
            }
            bottomAbsoluteRow = firstVisibleRow + frame.rows - 1
            if (event.marker == ShellIntegrationMarker.COMMAND_START) {
                commandText =
                    commandTextExtractor.extract(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursor.row,
                        cursorColumn = cursor.column,
                    )
            }
            if (event.marker == ShellIntegrationMarker.PROMPT_END && promptStartedForCommandText) {
                visiblePromptStartLineId =
                    firstRenderedPromptLineId(
                        frame = frame,
                        startLineId = promptStartLineId,
                        startColumn = promptStartColumn,
                        endRow = cursor.row,
                        endColumn = cursor.column,
                    )
            }
        }

        if (event.marker == ShellIntegrationMarker.COMMAND_START && commandText == null && historySize > 0) {
            val historyRows = minOf(historySize, MAX_SHELL_INTEGRATION_COMMAND_ROWS)
            renderReader.readRenderFrame(
                scrollbackOffset = historyRows,
                viewportRows = liveRows + historyRows,
            ) { frame ->
                val cursor = frame.cursor
                commandText =
                    commandTextExtractor.extract(
                        frame = frame,
                        promptEndLineId = promptEndLineId,
                        promptEndColumn = promptEndColumn,
                        cursorRow = cursor.row,
                        cursorColumn = cursor.column,
                    )
            }
        }

        state.observeLiveBottomRow(bottomAbsoluteRow)
        when (event.marker) {
            ShellIntegrationMarker.PROMPT_START -> {
                promptEndLineId = NO_LINE_ID
                promptEndColumn = 0
                promptStartedForCommandText = true
                promptStartLineId = cursorLineId
                promptStartColumn = cursorColumn
                recordIfAssigned(cursorLineId, state::recordPromptStart)
            }
            ShellIntegrationMarker.PROMPT_END -> {
                if (cursorLineId != NO_LINE_ID && promptStartedForCommandText) {
                    if (visiblePromptStartLineId != NO_LINE_ID && visiblePromptStartLineId != promptStartLineId) {
                        state.reanchorActivePromptStart(visiblePromptStartLineId)
                    }
                    promptEndLineId = cursorLineId
                    promptEndColumn = cursorColumn
                    state.recordPromptEnd(cursorLineId)
                }
            }
            ShellIntegrationMarker.COMMAND_START -> {
                if (cursorLineId != NO_LINE_ID) {
                    state.recordCommandStart(
                        lineId = cursorLineId,
                        includeLine = cursorColumn == 0,
                        commandText = commandText,
                        workingDirectoryUri = state.currentWorkingDirectoryUri(),
                    )
                }
                promptStartedForCommandText = false
            }
            ShellIntegrationMarker.COMMAND_FINISHED -> {
                val finishedLineId =
                    if (cursorColumn == 0 && previousLineId != NO_LINE_ID) {
                        previousLineId
                    } else {
                        cursorLineId
                    }
                if (finishedLineId != NO_LINE_ID) {
                    state.recordCommandFinished(finishedLineId, event.exitCode)
                }
            }
        }

        delegate.shellIntegrationMarker(event)
    }

    /**
     * Returns the first line containing a rendered prompt cell between OSC 133
     * A and B. Leading structurally blank rows are layout, not useful gutter
     * anchors; when the bounded live frame cannot prove a better anchor, the
     * caller preserves the original marker line.
     */
    private fun firstRenderedPromptLineId(
        frame: TerminalRenderFrame,
        startLineId: Long,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    ): Long {
        if (startLineId == NO_LINE_ID || endRow !in 0 until frame.rows || frame.columns <= 0) return NO_LINE_ID

        var startRow = 0
        while (startRow < frame.rows && frame.lineId(startRow) != startLineId) {
            startRow++
        }
        if (startRow >= frame.rows || startRow > endRow) return NO_LINE_ID

        ensurePromptScanCapacity(frame.columns)
        var row = startRow
        while (row <= endRow) {
            frame.copyLine(
                row = row,
                codeWords = promptScanCodeWords,
                attrWords = promptScanAttrWords,
                flags = promptScanFlags,
            )
            val firstColumn = if (row == startRow) startColumn.coerceIn(0, frame.columns) else 0
            val lastColumn = if (row == endRow) endColumn.coerceIn(0, frame.columns) else frame.columns
            var column = firstColumn
            while (column < lastColumn) {
                val flags = promptScanFlags[column]
                if (flags and PROMPT_CONTENT_FLAGS != 0) return frame.lineId(row)
                column++
            }
            row++
        }
        return NO_LINE_ID
    }

    private fun ensurePromptScanCapacity(columns: Int) {
        if (promptScanCodeWords.size >= columns) return
        promptScanCodeWords = IntArray(columns)
        promptScanAttrWords = LongArray(columns)
        promptScanFlags = IntArray(columns)
    }

    private inline fun recordIfAssigned(
        lineId: Long,
        record: (Long) -> Unit,
    ) {
        if (lineId != NO_LINE_ID) {
            record(lineId)
        }
    }

    private companion object {
        private const val NO_LINE_ID = 0L
        private const val PROMPT_CONTENT_FLAGS = TerminalRenderCellFlags.CODEPOINT + TerminalRenderCellFlags.CLUSTER
    }

    override fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    ) {
        delegate.showNotification(title, body, level)
    }

    override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
        delegate.terminalClipboardRequest(event)
    }

    override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
        delegate.terminalClipboardWrite(event)
    }

    override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
        delegate.terminalClipboardPrompt(event)
    }
}
