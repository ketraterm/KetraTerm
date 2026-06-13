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
package io.github.jvterm.pty

import com.pty4j.PtyProcess
import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.transport.TerminalConnectorListener
import io.github.jvterm.transport.checkBounds
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TerminalConnector] backed by a local PTY process.
 *
 * This connector owns PTY reader and watcher threads. It only moves bytes and
 * lifecycle events between the PTY process and a connector listener; parser,
 * core, cursor, attribute, and input-encoder behavior are owned by
 * `terminal-session` and lower layers.
 */
class PtyConnector internal constructor(
    private val process: TerminalProcess,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
    private val readerThreadName: String = DEFAULT_READER_THREAD_NAME,
    private val watcherThreadName: String = DEFAULT_WATCHER_THREAD_NAME,
) : TerminalConnector {
    private val started = AtomicBoolean(false)
    private val localCloseRequested = AtomicBoolean(false)
    private val closedNotified = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile
    private var listener: TerminalConnectorListener? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var watcherThread: Thread? = null

    /**
     * Captured transport read failure, or `null` when no reader failure
     * occurred.
     */
    @Volatile
    var failure: IOException? = null
        private set

    /**
     * Process exit code after the watcher observes process termination.
     */
    @Volatile
    var exitCode: Int? = null
        private set

    /**
     * Creates a connector for a raw PTY4J process.
     */
    constructor(
        process: PtyProcess,
        readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
        readerThreadName: String = DEFAULT_READER_THREAD_NAME,
        watcherThreadName: String = DEFAULT_WATCHER_THREAD_NAME,
    ) : this(Pty4jTerminalProcess(process), readBufferSize, readerThreadName, watcherThreadName)

    init {
        require(readBufferSize > 0) { "readBufferSize must be positive, got $readBufferSize" }
        require(readerThreadName.isNotBlank()) { "readerThreadName must not be blank" }
        require(watcherThreadName.isNotBlank()) { "watcherThreadName must not be blank" }
    }

    /**
     * Returns true while the underlying PTY process reports it is alive.
     */
    val isAlive: Boolean
        get() = process.isAlive()

    override fun start(listener: TerminalConnectorListener) {
        check(started.compareAndSet(false, true)) { "connector already started" }
        this.listener = listener

        val watcher =
            Thread(this::watchProcessExit, watcherThreadName).apply {
                isDaemon = true
            }
        watcherThread = watcher
        watcher.start()

        val reader =
            Thread(this::pumpOutput, readerThreadName).apply {
                isDaemon = true
            }
        readerThread = reader
        reader.start()
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        bytes.checkBounds(offset, length)

        if (isClosed()) return

        synchronized(writeLock) {
            if (isClosed()) return
            process.output.write(bytes, offset, length)
            process.output.flush()
        }
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }

        if (!isClosed()) {
            process.resize(columns, rows)
        }
    }

    override fun close() {
        if (!localCloseRequested.compareAndSet(false, true)) return

        try {
            process.destroy()
        } finally {
            synchronized(writeLock) {
                try {
                    process.output.close()
                } catch (_: IOException) {
                    // Local close should remain best-effort and idempotent.
                }
            }
            joinThreads()
        }
    }

    /**
     * Waits for the child process to exit and returns its exit code.
     */
    @Throws(InterruptedException::class)
    fun waitFor(): Int = process.waitFor()

    internal fun joinReader(timeoutMillis: Long): Boolean = joinThread(readerThread, timeoutMillis)

    internal fun joinWatcher(timeoutMillis: Long): Boolean = joinThread(watcherThread, timeoutMillis)

    private fun pumpOutput() {
        val buffer = ByteArray(readBufferSize)

        try {
            while (!localCloseRequested.get()) {
                val read = process.input.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                listenerOrThrow().onBytes(buffer, 0, read)
            }
        } catch (exception: IOException) {
            if (!localCloseRequested.get()) {
                failure = exception
                listenerOrThrow().onError(exception)
                notifyClosed(null)
            }
        }
    }

    private fun watchProcessExit() {
        try {
            val code = process.waitFor()
            if (!localCloseRequested.get()) {
                exitCode = code
                notifyClosed(code)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun notifyClosed(code: Int?) {
        if (closedNotified.compareAndSet(false, true)) {
            listenerOrThrow().onClosed(code)
        }
    }

    private fun joinThreads() {
        joinThread(readerThread, CLOSE_JOIN_MILLIS)
        joinThread(watcherThread, CLOSE_JOIN_MILLIS)
    }

    private fun joinThread(
        thread: Thread?,
        timeoutMillis: Long,
    ): Boolean {
        if (thread == null || Thread.currentThread() === thread) return true

        return try {
            thread.join(timeoutMillis)
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun isClosed(): Boolean = localCloseRequested.get() || closedNotified.get()

    private fun listenerOrThrow(): TerminalConnectorListener = checkNotNull(listener) { "connector has not been started" }

    private companion object {
        const val DEFAULT_READ_BUFFER_SIZE: Int = 8192
        const val DEFAULT_READER_THREAD_NAME: String = "terminal-pty-reader"
        const val DEFAULT_WATCHER_THREAD_NAME: String = "terminal-pty-watcher"
        const val CLOSE_JOIN_MILLIS: Long = 250
    }
}
