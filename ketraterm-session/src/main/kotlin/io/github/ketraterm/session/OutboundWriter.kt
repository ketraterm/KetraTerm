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
import kotlin.coroutines.CoroutineContext

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
    private var producedBytes = 0L
    private var consumedBytes = 0L
    private val bulkWrites = ArrayDeque<BulkWrite>()
    private var pendingBulkUnits = 0L
    private var pendingBulkOperations = 0

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
                producedBytes += size - previousSize
                previousSize == 0 && size != 0
            }
        if (wake) ready.trySend(Unit)
    }

    /**
     * Reserves a position between byte transactions. The worker streams this
     * operation directly to the connector before consuming later queued bytes.
     * [workUnits] counts retained UTF-16 units and requested editor deletions;
     * reservations include the active operation until its write returns.
     */
    fun submitBulk(
        workUnits: Long,
        write: () -> Unit,
    ) {
        require(workUnits >= 0)
        synchronized(lock) {
            if (closed) return
            if (workUnits > MAX_BULK_UNITS - pendingBulkUnits || pendingBulkOperations == MAX_BULK_OPERATIONS) {
                throw OutboundCapacityException("Terminal output exceeds the bulk input budget")
            }
            bulkWrites.addLast(BulkWrite(producedBytes, workUnits, write))
            pendingBulkUnits += workUnits
            pendingBulkOperations++
        }
        ready.trySend(Unit)
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
        val context = currentCoroutineContext()
        try {
            for (signal in ready) {
                drain(scratch, context)
            }
        } finally {
            scratch.fill(0)
        }
    }

    // Keep payload references on this stack, rather than in a suspended continuation.
    private fun drain(
        scratch: ByteArray,
        context: CoroutineContext,
    ) {
        while (true) {
            context.ensureActive()
            var bulk: BulkWrite? = null
            val count =
                synchronized(lock) {
                    if (closed) return
                    val next = bulkWrites.firstOrNull()
                    if (next != null && next.position == consumedBytes) {
                        bulk = bulkWrites.removeFirst()
                        0
                    } else {
                        // Differences stay within the byte budget even when these sequence counters wrap.
                        val beforeBulk = if (next == null) size else (next.position - consumedBytes).toInt()
                        val count = minOf(size, scratch.size, beforeBulk)
                        copyTo(scratch, count)
                        clear(0, count)
                        head = (head + count) % bytes.size
                        size -= count
                        consumedBytes += count
                        count
                    }
                }
            val operation = bulk
            if (operation != null) {
                try {
                    operation.write()
                } finally {
                    synchronized(lock) {
                        if (!closed) {
                            pendingBulkUnits -= operation.workUnits
                            pendingBulkOperations--
                        }
                    }
                }
            } else {
                if (count == 0) return
                connector.write(scratch, 0, count)
                scratch.fill(0, 0, count)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            bytes.fill(0)
            bytes = EMPTY_BYTES
            size = 0
            head = 0
            bulkWrites.clear()
            pendingBulkUnits = 0
            pendingBulkOperations = 0
        }
        ready.close()
    }

    private class BulkWrite(
        val position: Long,
        val workUnits: Long,
        val write: () -> Unit,
    )

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
        const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
        const val MAX_BULK_UNITS = 16 * 1024 * 1024
        const val MAX_BULK_OPERATIONS = 16
        private val EMPTY_BYTES = ByteArray(0)
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val WRITE_BUFFER_SIZE = 16 * 1024
    }
}

internal class OutboundCapacityException(
    message: String = "Terminal output exceeds the 8 MiB queue limit",
) : IOException(message)
