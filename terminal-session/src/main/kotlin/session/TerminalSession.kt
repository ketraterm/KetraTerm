package com.gagik.terminal.session

import com.gagik.core.api.TerminalBufferApi
import com.gagik.core.api.TerminalHostResponseReader
import com.gagik.integration.CoreTerminalCommandSink
import com.gagik.integration.TerminalHostEventSink
import com.gagik.integration.TerminalHostPolicy
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.parser.api.TerminalParsers
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.input.impl.DefaultTerminalInputEncoder
import com.gagik.terminal.input.policy.TerminalInputPolicy
import com.gagik.terminal.render.api.TerminalRenderFrameConsumer
import com.gagik.terminal.render.api.TerminalRenderFrameReader
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val outboundWriteLock: Any = Any(),
) : TerminalConnectorListener, TerminalInputEncoder, TerminalRenderFrameReader, AutoCloseable {
    private val localCloseRequested = AtomicBoolean(false)
    private val remoteClosed = AtomicBoolean(false)
    private val parserClosed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val renderPending = AtomicBoolean(false)

    private val inboundLock = Any()
    private val mutationLock = Any()
    private val responseScratch = ByteArray(RESPONSE_BUFFER_SIZE)

    private val renderWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terminal-render-worker").apply { isDaemon = true }
    }

    /**
     * Optional callback invoked after a render frame is published.
     *
     * UI components should use this to trigger a repaint. The callback is
     * invoked from the [renderWorker] thread.
     */
    @Volatile
    var onDirty: (() -> Unit)? = null

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
     * Starts the connector after resizing core and transport to [columns] x
     * [rows].
     */
    fun start(columns: Int, rows: Int) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        check(started.compareAndSet(false, true)) { "session already started" }

        synchronized(mutationLock) {
            terminal.resize(columns, rows)
            publisher.resize(columns, rows)
        }
        connector.resize(columns, rows)
        connector.start(this)
    }

    /**
     * Resizes core, publisher, and the active connector.
     */
    fun resize(columns: Int, rows: Int) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }

        synchronized(mutationLock) {
            terminal.resize(columns, rows)
            publisher.resize(columns, rows)
        }
        connector.resize(columns, rows)
    }

    /**
     * Encodes a key event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodeKey(event: TerminalKeyEvent) {
        synchronized(outboundWriteLock) {
            if (isAcceptingInput()) {
                inputEncoder.encodeKey(event)
            }
        }
    }

    /**
     * Encodes a paste event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodePaste(event: TerminalPasteEvent) {
        synchronized(outboundWriteLock) {
            if (isAcceptingInput()) {
                inputEncoder.encodePaste(event)
            }
        }
    }

    /**
     * Encodes a focus event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodeFocus(event: TerminalFocusEvent) {
        synchronized(outboundWriteLock) {
            if (isAcceptingInput()) {
                inputEncoder.encodeFocus(event)
            }
        }
    }

    /**
     * Encodes a mouse event and writes it to the connector unless closed.
     *
     * Input before [start] is ignored.
     */
    override fun encodeMouse(event: TerminalMouseEvent) {
        synchronized(outboundWriteLock) {
            if (isAcceptingInput()) {
                inputEncoder.encodeMouse(event)
            }
        }
    }

    /**
     * Consumes host bytes synchronously, mutating parser/core before returning.
     */
    override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0) { "offset must be non-negative, got $offset" }
        require(length >= 0) { "length must be non-negative, got $length" }
        require(offset <= bytes.size) { "offset $offset exceeds size ${bytes.size}" }
        require(length <= bytes.size - offset) {
            "offset + length exceeds size: offset=$offset length=$length size=${bytes.size}"
        }

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
        if (isClosed()) return
        if (renderPending.compareAndSet(false, true)) {
            renderWorker.execute {
                renderPending.set(false)
                publisher.updateAndPublish(this)
                onDirty?.invoke()
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

    override fun readRenderFrame(scrollbackOffset: Int, consumer: TerminalRenderFrameConsumer) {
        synchronized(mutationLock) {
            renderReader.readRenderFrame(scrollbackOffset, consumer)
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
        renderWorker.shutdown()
        renderWorker.awaitTermination(500, TimeUnit.MILLISECONDS)
    }

    private fun drainResponses() {
        while (!isClosed()) {
            val count = synchronized(mutationLock) {
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
        synchronized(mutationLock) {
            parser.endOfInput()
        }
    }

    private fun isClosed(): Boolean {
        return localCloseRequested.get() || remoteClosed.get()
    }

    private fun isAcceptingInput(): Boolean {
        return started.get() && !isClosed()
    }

    companion object {
        private const val RESPONSE_BUFFER_SIZE: Int = 1024

        /**
         * Creates a production session with the standard parser, integration
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
            val renderReader = terminal as? TerminalRenderFrameReader
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
                outboundWriteLock = outboundWriteLock,
            )
        }
    }
}
