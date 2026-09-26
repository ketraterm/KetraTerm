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

import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.checkBounds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Bounded byte storage shared by input and replies. A transaction publishes all
 * its bytes together; the sole consumer performs transport I/O outside [lock].
 * Ordinary input reuses the ring and the consumer's scratch buffer.
 */
internal class OutboundWriter(
    private val connector: TerminalConnector,
    private val lock: Any,
) {
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var head = 0
    private var size = 0
    private var closed = false

    /** Returns after copying a complete operation, never after waiting for transport I/O. */
    inline fun submit(crossinline block: () -> Unit) {
        val wake =
            synchronized(lock) {
                if (closed) return
                val previousSize = size
                try {
                    block()
                } catch (failure: Throwable) {
                    clear(previousSize, size - previousSize)
                    size = previousSize
                    throw failure
                }
                previousSize == 0 && size != 0
            }
        if (wake) ready.trySend(Unit)
    }

    /** Called only inside [submit], while holding the session's outbound lock. */
    fun append(
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        source.checkBounds(offset, length)
        if (length > MAX_QUEUED_BYTES - size) throw OutboundCapacityException()
        val required = size + length
        if (required > bytes.size) {
            val grown = ByteArray(maxOf(required, bytes.size * 2).coerceAtMost(MAX_QUEUED_BYTES))
            copyTo(grown, size)
            bytes.fill(0)
            bytes = grown
            head = 0
        }
        val tail = (head + size) % bytes.size
        val first = minOf(length, bytes.size - tail)
        source.copyInto(bytes, tail, offset, offset + first)
        source.copyInto(bytes, 0, offset + first, offset + length)
        size = required
    }

    /** Runs once, on the session I/O dispatcher. Cancellation never waits for a native write. */
    suspend fun run() {
        val scratch = ByteArray(WRITE_BUFFER_SIZE)
        try {
            for (signal in ready) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count =
                        synchronized(lock) {
                            if (closed) return
                            val count = minOf(size, scratch.size)
                            copyTo(scratch, count)
                            clear(0, count)
                            head = (head + count) % bytes.size
                            size -= count
                            count
                        }
                    if (count == 0) break
                    connector.write(scratch, 0, count)
                    scratch.fill(0, 0, count)
                }
            }
        } finally {
            scratch.fill(0)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            bytes.fill(0)
            bytes = EMPTY_BYTES
            size = 0
            head = 0
        }
        ready.close()
    }

    private fun copyTo(
        destination: ByteArray,
        count: Int,
    ) {
        val first = minOf(count, bytes.size - head)
        bytes.copyInto(destination, 0, head, head + first)
        bytes.copyInto(destination, first, 0, count - first)
    }

    private fun clear(
        offset: Int,
        count: Int,
    ) {
        val start = (head + offset) % bytes.size
        val first = minOf(count, bytes.size - start)
        bytes.fill(0, start, start + first)
        bytes.fill(0, 0, count - first)
    }

    companion object {
        // Includes a complete large paste; the consumer additionally retains at most 16 KiB.
        const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
        private val EMPTY_BYTES = ByteArray(0)
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val WRITE_BUFFER_SIZE = 16 * 1024
    }
}

internal class OutboundCapacityException : IOException("Terminal output exceeds the 8 MiB queue limit")
