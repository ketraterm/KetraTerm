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
package io.github.jvterm.testkit

import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.transport.TerminalConnectorListener
import io.github.jvterm.transport.checkBounds

/**
 * In-memory connector for session and transport-adapter tests.
 *
 * Local [close] only records that the connector was closed. Remote lifecycle
 * events are triggered explicitly through [simulateClosed] or [simulateCrash].
 */
class MockConnector : TerminalConnector {
    private val capturedWrites = ArrayList<Byte>()
    private var listener: TerminalConnectorListener? = null

    /** Number of times [start] was called. */
    var startCount: Int = 0
        private set

    /** Number of times [close] was called locally. */
    var closeCount: Int = 0
        private set

    /** True after local [close] has been requested. */
    var isClosed: Boolean = false
        private set

    /** Resize calls in the order they were received. */
    val resizeCalls: MutableList<Pair<Int, Int>> = mutableListOf()

    /** Captured outbound bytes written by the session. */
    val writtenBytes: ByteArray
        get() = ByteArray(capturedWrites.size) { index -> capturedWrites[index] }

    override fun start(listener: TerminalConnectorListener) {
        check(this.listener == null) { "connector already started" }
        this.listener = listener
        startCount++
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        bytes.checkBounds(offset, length)

        if (isClosed) return

        var index = 0
        while (index < length) {
            capturedWrites += bytes[offset + index]
            index++
        }
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        if (isClosed) return
        resizeCalls += columns to rows
    }

    override fun close() {
        isClosed = true
        closeCount++
    }

    /**
     * Delivers host output to the session.
     */
    fun feedFromHost(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ) {
        check(!isClosed) { "connector is locally closed" }
        listenerOrThrow().onBytes(bytes, offset, length)
    }

    /**
     * Simulates remote transport closure.
     */
    fun simulateClosed(exitCode: Int? = null) {
        listenerOrThrow().onClosed(exitCode)
    }

    /**
     * Simulates remote transport failure.
     */
    fun simulateCrash(error: Throwable) {
        listenerOrThrow().onError(error)
    }

    private fun listenerOrThrow(): TerminalConnectorListener = checkNotNull(listener) { "connector has not been started" }
}
