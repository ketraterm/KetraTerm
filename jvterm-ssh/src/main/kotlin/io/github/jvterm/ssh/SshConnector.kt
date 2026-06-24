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
package io.github.jvterm.ssh

import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.transport.TerminalConnectorListener
import io.github.jvterm.transport.checkBounds
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TerminalConnector] backed by a remote SSH PTY shell channel.
 *
 * The connector owns SSH connection, authentication, shell-channel lifecycle,
 * host-key verification, and transport byte movement. Parser, terminal state,
 * input encoding, and rendering remain owned by the shared session pipeline.
 *
 * @param options SSH connection and PTY options.
 * @param shellClient SSH shell client implementation.
 * @param watcherThreadName name for the daemon shell-close watcher thread.
 */
class SshConnector internal constructor(
    private val options: SshOptions,
    private val shellClient: SshShellClient = MinaSshShellClient,
    private val watcherThreadName: String = DEFAULT_WATCHER_THREAD_NAME,
) : TerminalConnector {
    private val started = AtomicBoolean(false)
    private val localCloseRequested = AtomicBoolean(false)
    private val closedNotified = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile
    private var listener: TerminalConnectorListener? = null

    @Volatile
    private var shell: SshShell? = null

    @Volatile
    private var watcherThread: Thread? = null

    @Volatile
    private var pendingColumns: Int = options.columns

    @Volatile
    private var pendingRows: Int = options.rows

    /**
     * Captured SSH transport failure, or `null` when no failure occurred.
     */
    @Volatile
    var failure: IOException? = null
        private set

    init {
        require(watcherThreadName.isNotBlank()) { "watcherThreadName must not be blank" }
    }

    override fun start(listener: TerminalConnectorListener) {
        check(started.compareAndSet(false, true)) { "connector already started" }
        this.listener = listener

        try {
            val openedShell =
                shellClient.open(
                    options.copy(
                        columns = pendingColumns,
                        rows = pendingRows,
                    ),
                    ListenerOutput(listener),
                )
            if (localCloseRequested.get()) {
                openedShell.close()
                notifyClosed(null)
                return
            }
            shell = openedShell
            val watcher =
                Thread(this::watchShellClose, watcherThreadName).apply {
                    isDaemon = true
                }
            watcherThread = watcher
            watcher.start()
        } catch (exception: IOException) {
            failure = exception
            listener.onError(exception)
            notifyClosed(null)
        }
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
            shell?.write(bytes, offset, length)
        }
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }

        if (!isClosed()) {
            val openedShell = shell
            if (openedShell == null) {
                pendingColumns = columns
                pendingRows = rows
            } else {
                openedShell.resize(columns, rows)
            }
        }
    }

    override fun close() {
        if (!localCloseRequested.compareAndSet(false, true)) return

        try {
            shell?.close()
        } finally {
            synchronized(writeLock) {
                shell = null
            }
            joinWatcher(CLOSE_JOIN_MILLIS)
        }
    }

    internal fun joinWatcher(timeoutMillis: Long): Boolean = joinThread(watcherThread, timeoutMillis)

    private fun watchShellClose() {
        try {
            val code = shell?.waitForClosed()
            if (!localCloseRequested.get()) {
                notifyClosed(code)
            }
        } catch (exception: IOException) {
            if (!localCloseRequested.get()) {
                failure = exception
                listenerOrThrow().onError(exception)
                notifyClosed(null)
            }
        }
    }

    private fun notifyClosed(code: Int?) {
        if (closedNotified.compareAndSet(false, true)) {
            listenerOrThrow().onClosed(code)
        }
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

    private inner class ListenerOutput(
        private val target: TerminalConnectorListener,
    ) : SshShellOutput {
        private val deliveryLock = Any()

        override fun emit(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            bytes.checkBounds(offset, length)
            if (isClosed()) return

            synchronized(deliveryLock) {
                if (!isClosed()) {
                    target.onBytes(bytes, offset, length)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_WATCHER_THREAD_NAME: String = "terminal-ssh-watcher"
        const val CLOSE_JOIN_MILLIS: Long = 250L
    }
}
