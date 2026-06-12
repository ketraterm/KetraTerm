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
package io.github.jvterm.session

import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import com.gagik.terminal.transport.checkBounds
import io.github.jvterm.core.api.TerminalBufferApi
import io.github.jvterm.core.api.TerminalHostResponseReader
import io.github.jvterm.host.CoreTerminalCommandSink
import io.github.jvterm.host.TerminalHostEventSink
import io.github.jvterm.host.TerminalHostPolicy
import io.github.jvterm.input.api.TerminalInputEncoder
import io.github.jvterm.input.event.TerminalFocusEvent
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalPasteEvent
import io.github.jvterm.input.impl.DefaultTerminalInputEncoder
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.parser.api.TerminalOutputParser
import io.github.jvterm.parser.api.TerminalParsers
import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.api.TerminalRenderCursorShape
import io.github.jvterm.render.api.TerminalRenderFrameConsumer
import io.github.jvterm.render.api.TerminalRenderFrameReader
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.render.cache.TerminalRenderPublisher
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime terminal session that binds core, parser, input encoding, and a
 * transport connector.
 *
 * The connector owns transport threads. This session owns parser/core mutation
 * serialization and all host-bound write ordering.
 *
 * @property terminal public terminal buffer mutated by host output.
 */
class TerminalSession(
    val terminal: TerminalBufferApi,
    val publisher: TerminalRenderPublisher,
    private val renderReader: TerminalRenderFrameReader,
    private val responseReader: TerminalHostResponseReader,
    private val connector: TerminalConnector,
    private val parser: TerminalOutputParser,
    private val inputEncoder: TerminalInputEncoder,
    private val hyperlinkResolver: TerminalHyperlinkResolver = TerminalHyperlinkResolver.NONE,
    private val outboundWriteLock: Any = Any(),
) : TerminalConnectorListener,
    TerminalInputEncoder,
    TerminalRenderFrameReader,
    AutoCloseable {
    private val localCloseRequested = AtomicBoolean(false)
    private val remoteClosed = AtomicBoolean(false)
    private val parserClosed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val renderScheduled = AtomicBoolean(false)
    private val pendingRenderRequest = AtomicLong(packRenderRequest(scrollbackOffset = 0, viewportRows = 0))
    private val pendingRenderGeneration = AtomicLong(0)

    private val inboundLock = Any()
    private val mutationLock = Any()
    private val responseScratch = ByteArray(RESPONSE_BUFFER_SIZE)

    private val timeoutLock = Any()
    private var synchronizedTimeoutFuture: ScheduledFuture<*>? = null

    private val renderWorker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "terminal-render-worker-${SESSION_COUNTER.getAndIncrement()}").apply { isDaemon = true }
        }

    private val dirtyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var legacyOnDirty: (() -> Unit)? = null

    /**
     * Optional callback invoked after a render frame is published.
     *
     * UI components should use this to trigger a repaint. The callback is
     * invoked from the [renderWorker] thread.
     */
    var onDirty: (() -> Unit)?
        get() = legacyOnDirty ?: dirtyListeners.firstOrNull()
        set(value) {
            legacyOnDirty = value
        }

    /** Registers a listener to be notified when the session needs a render repaint. */
    fun addDirtyListener(listener: () -> Unit) {
        dirtyListeners.add(listener)
    }

    /** Unregisters a previously registered render repaint listener. */
    fun removeDirtyListener(listener: () -> Unit) {
        dirtyListeners.remove(listener)
    }

    /**
     * Remote process exit code after [onClosed] receives one.
     */
    @Volatile
    var exitCode: Int? = null
        private set

    /**
     * Transport failure reported by [onError], or `null` when the remote closed
     * normally or the session was locally closed.
     */
    @Volatile
    var failure: Throwable? = null
        private set

    /**
     * Resolves a primitive render-frame hyperlink id to a target URI.
     *
     * UI components call this from explicit user activation paths after reading
     * [TerminalRenderCache.hyperlinkIds]. The lookup is outside the paint loop
     * and returns `null` when metadata is unavailable or was evicted by policy.
     *
     * @param hyperlinkId cell hyperlink id; `0` means no hyperlink.
     * @return target URI, or `null`.
     */
    fun hyperlinkUri(hyperlinkId: Int): String? = hyperlinkResolver.uriForHyperlinkId(hyperlinkId)

    /**
     * Starts the connector after resizing core and transport to [columns] x
     * [rows].
     */
    fun start(
        columns: Int,
        rows: Int,
    ) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        check(started.compareAndSet(false, true)) { "session already started" }

        synchronized(mutationLock) {
            terminal.resize(columns, rows)
        }
        connector.resize(columns, rows)
        connector.start(this)
    }

    /**
     * Resizes core, publisher, and the active connector.
     *
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
        notifyRenderDirty()
        return result
    }

    /**
     * Applies the host's East Asian Ambiguous width policy for future writes.
     *
     * Existing stored content keeps its current cell shape; changing this
     * setting does not reinterpret already-written rows.
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
    }

    /**
     * Updates the current and default cursor shape for the session.
     */
    fun setCursorShape(shape: TerminalRenderCursorShape) {
        synchronized(mutationLock) {
            terminal.setDefaultCursorShape(shape)
            terminal.setCursorShape(shape)
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

        synchronized(inboundLock) {
            if (isClosed()) return

            synchronized(mutationLock) {
                parser.accept(bytes, offset, length)
            }

            drainResponses()
            notifyRenderDirty()
        }
    }

    /**
     * Submits a render task to the worker thread.
     */
    fun notifyRenderDirty() {
        requestRender(scrollbackOffset = 0)
    }

    /**
     * Requests a render-cache publication for a caller-owned scrollback viewport.
     *
     * The offset is transient render request state, not terminal state. UI
     * layers own scrollback policy and pass the desired offset for each
     * publication they need.
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
     */
    fun requestRender(
        scrollbackOffset: Int,
        viewportRows: Int,
    ) {
        if (isClosed()) return
        pendingRenderRequest.set(
            packRenderRequest(
                scrollbackOffset = scrollbackOffset.coerceAtLeast(0),
                viewportRows = viewportRows.coerceAtLeast(0),
            ),
        )
        pendingRenderGeneration.incrementAndGet()
        scheduleRenderDrain()
    }

    private fun scheduleRenderDrain() {
        if (renderScheduled.compareAndSet(false, true)) {
            renderWorker.execute {
                drainRenderRequests()
            }
        }
    }

    private fun scheduleSynchronizedOutputTimeout() {
        synchronized(timeoutLock) {
            if (synchronizedTimeoutFuture == null) {
                synchronizedTimeoutFuture =
                    renderWorker.schedule({
                        synchronized(mutationLock) {
                            if (terminal.getModeSnapshot().isSynchronizedOutput) {
                                terminal.setSynchronizedOutput(false)
                                notifyRenderDirty()
                            }
                        }
                        synchronized(timeoutLock) {
                            synchronizedTimeoutFuture = null
                        }
                    }, SYNCHRONIZED_OUTPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun cancelSynchronizedOutputTimeout() {
        synchronized(timeoutLock) {
            synchronizedTimeoutFuture?.let {
                it.cancel(false)
                synchronizedTimeoutFuture = null
            }
        }
    }

    private fun drainRenderRequests() {
        var publishedGeneration = -1L
        var failedGeneration = NO_RENDER_GENERATION
        var reschedule = true
        try {
            while (!isClosed()) {
                val generation = pendingRenderGeneration.get()
                if (generation == publishedGeneration) return

                val request = pendingRenderRequest.get()
                val offset = unpackScrollbackOffset(request)
                val rows = unpackViewportRows(request)

                val modeSnapshot = terminal.getModeSnapshot()
                if (modeSnapshot.isSynchronizedOutput) {
                    scheduleSynchronizedOutputTimeout()
                    publishedGeneration = generation
                    return
                }

                cancelSynchronizedOutputTimeout()

                try {
                    publisher.updateAndPublish(this, offset, rows)
                    publishedGeneration = generation
                } catch (e: Exception) {
                    if (e is InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    failedGeneration = generation
                    return
                }

                try {
                    legacyOnDirty?.invoke()
                    for (listener in dirtyListeners) {
                        listener.invoke()
                    }
                } catch (e: Exception) {
                    // UI notification failure must not invalidate an already-published frame.
                }
            }
        } catch (e: Error) {
            reschedule = false
            throw e
        } finally {
            renderScheduled.set(false)
            val pendingGeneration = pendingRenderGeneration.get()
            if (reschedule &&
                !isClosed() &&
                pendingGeneration != publishedGeneration &&
                pendingGeneration != failedGeneration
            ) {
                scheduleRenderDrain()
            }
        }
    }

    /**
     * Reads a short-lived render frame while holding the terminal mutation lock.
     *
     * UI callers should use this session-level reader rather than reading the
     * core buffer directly, so parser output and resize cannot mutate the grid
     * while a renderer copies primitive row data.
     */
    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
        readRenderFrame(scrollbackOffset = 0, consumer = consumer)
    }

    override fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        synchronized(mutationLock) {
            renderReader.readRenderFrame(scrollbackOffset, consumer)
        }
    }

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
     * Records remote closure and closes the parser exactly once.
     */
    override fun onClosed(exitCode: Int?) {
        if (!remoteClosed.compareAndSet(false, true)) return
        this.exitCode = exitCode
        cleanupParser()
    }

    /**
     * Records transport failure and treats it as remote closure without a
     * process exit code.
     */
    override fun onError(error: Throwable) {
        if (!remoteClosed.compareAndSet(false, true)) return
        failure = error
        cleanupParser()
    }

    /**
     * Requests local connector shutdown and closes parser input exactly once.
     */
    override fun close() {
        if (!localCloseRequested.compareAndSet(false, true)) return
        if (!remoteClosed.get()) {
            connector.close()
        }
        cleanupParser()
        renderWorker.awaitTermination(500, TimeUnit.MILLISECONDS)
    }

    private fun drainResponses() {
        while (!isClosed()) {
            val count =
                synchronized(mutationLock) {
                    responseReader.readResponseBytes(responseScratch, 0, responseScratch.size)
                }

            if (count <= 0) return

            synchronized(outboundWriteLock) {
                if (!isClosed()) {
                    connector.write(responseScratch, 0, count)
                }
            }
        }
    }

    private fun cleanupParser() {
        if (!parserClosed.compareAndSet(false, true)) return
        cancelSynchronizedOutputTimeout()
        synchronized(mutationLock) {
            parser.endOfInput()
        }
        renderWorker.shutdown()
    }

    private fun isClosed(): Boolean = localCloseRequested.get() || remoteClosed.get()

    private fun isAcceptingInput(): Boolean = started.get() && !isClosed()

    companion object {
        private val SESSION_COUNTER =
            AtomicInteger(1)
        private const val RESPONSE_BUFFER_SIZE: Int = 1024
        private const val NO_RENDER_GENERATION: Long = -1L
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
         */
        @JvmStatic
        fun create(
            terminal: TerminalBufferApi,
            connector: TerminalConnector,
            hostEvents: TerminalHostEventSink = TerminalHostEventSink.NONE,
            hostPolicy: TerminalHostPolicy = TerminalHostPolicy(),
            inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
        ): TerminalSession {
            val outboundWriteLock = Any()
            val hostOutput = ConnectorTerminalHostOutput(connector, outboundWriteLock)
            val sink = CoreTerminalCommandSink(terminal, hostEvents, hostPolicy)
            val parser = TerminalParsers.create(sink)
            val inputEncoder = DefaultTerminalInputEncoder(terminal, hostOutput, inputPolicy)
            val renderReader =
                terminal as? TerminalRenderFrameReader
                    ?: error("terminal must implement TerminalRenderFrameReader")

            // Create a publisher with initial dimensions
            val publisher = TerminalRenderPublisher(terminal.width, terminal.height)

            return TerminalSession(
                terminal = terminal,
                publisher = publisher,
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoder = inputEncoder,
                hyperlinkResolver = TerminalHyperlinkResolver(sink::hyperlinkUri),
                outboundWriteLock = outboundWriteLock,
            )
        }
    }
}
