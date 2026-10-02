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
package io.github.ketraterm.render.cache

import io.github.ketraterm.render.api.TerminalRenderFrameReader
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Triple-buffered render cache publisher.
 *
 * One buffer is writer-owned (back).
 * One buffer is UI-readable (front).
 * One buffer is spare (recycled after front is replaced).
 *
 * Writer and UI never touch the same buffer simultaneously when UI consumers
 * access the front buffer through [readCurrent].
 *
 * The public inline reader avoids a callback allocation; non-inline operations own
 * lease acquisition and release. [PublishedApi] declarations are binary compatibility
 * commitments, not host extension points. Earlier compiled readers acquire leases
 * directly through the retained lock, indices, arrays and counts; writers and new
 * readers must continue to cooperate with that algorithm.
 *
 * @param columns initial cache width in cells.
 * @param rows initial cache height in rows.
 */
public class TerminalRenderPublisher(
    columns: Int,
    rows: Int,
) {
    @PublishedApi internal val buffers: Array<TerminalRenderCache> = Array(3) { TerminalRenderCache(columns, rows) }

    @PublishedApi internal val readerCounts: IntArray = IntArray(BUFFER_COUNT)
    private val writerOwned = BooleanArray(BUFFER_COUNT)

    // Buffer indices, reader counts, and writer leases are mutated under publishLock.
    @PublishedApi internal var frontIndex: Int = NO_FRONT
        private set
    private var nextWriteIndex = 0

    @PublishedApi internal val publishLock: ReentrantLock = ReentrantLock()
    private val bufferAvailable = publishLock.newCondition()

    // AtomicReference for lock-free front reads.
    private val frontRef = AtomicReference<TerminalRenderCache?>(null)

    /**
     * Called from render worker thread only.
     * Reads from [reader], updates back buffer, publishes as new front.
     *
     * @param reader source of the short-lived render frame.
     */
    public fun updateAndPublish(reader: TerminalRenderFrameReader) {
        updateAndPublish(reader, scrollbackOffset = 0)
    }

    /**
     * Called from render worker thread only.
     *
     * [scrollbackOffset] is caller-owned viewport state in lines above the live
     * bottom viewport. The source reader clamps it before rows are copied.
     *
     * @param reader source of the short-lived render frame.
     * @param scrollbackOffset requested lines above the live bottom viewport.
     */
    public fun updateAndPublish(
        reader: TerminalRenderFrameReader,
        scrollbackOffset: Int,
    ) {
        updateAndPublish(reader, scrollbackOffset, viewportRows = 0)
    }

    /**
     * Called from render worker thread only.
     *
     * [viewportRows] requests render-only overscan rows for UI composition. It
     * does not resize terminal state; the source reader clamps the resolved
     * frame height before rows are copied.
     *
     * @param reader source of the short-lived render frame.
     * @param scrollbackOffset requested lines above the live bottom viewport.
     * @param viewportRows requested render rows, or zero for the reader default.
     */
    public fun updateAndPublish(
        reader: TerminalRenderFrameReader,
        scrollbackOffset: Int,
        viewportRows: Int,
    ) {
        val writeIndex = acquireWritableIndex()
        val back = buffers[writeIndex]
        var published = false

        try {
            // The selected back buffer is writer-exclusive until publish or
            // release. Resize and UI readers are blocked from mutating or
            // leasing this specific buffer through publisher state.
            back.updateFrom(reader, scrollbackOffset, viewportRows)

            publishLock.withLock {
                writerOwned[writeIndex] = false
                frontIndex = writeIndex
                frontRef.set(buffers[frontIndex])
                published = true
                bufferAvailable.signalAll()
            }
        } finally {
            if (!published) {
                releaseWritableIndex(writeIndex)
            }
        }
    }

    /**
     * Returns the latest published snapshot without acquiring a reader lease.
     *
     * Only tests in this module use this while publication is quiescent. External
     * consumers must inspect or copy cache state through [readCurrent].
     *
     * @return the latest published [TerminalRenderCache] snapshot, or null if no frame has been published yet.
     */
    internal fun current(): TerminalRenderCache? = frontRef.get()

    /**
     * Reads the latest published front buffer while preventing it from being
     * recycled as a writer-owned back buffer.
     *
     * The callback should only copy or paint from the cache and must not call
     * back into this publisher. Returning `null` means no frame has been
     * published yet, or the callback itself returns `null`. Callback failures and
     * non-local returns release the lease before propagating to the caller.
     *
     * @param block reader invoked with the current front buffer.
     * @return [block]'s result, or `null` when no frame is available.
     */
    public inline fun <T> readCurrent(block: (TerminalRenderCache) -> T): T? {
        val index = acquireFrontLease()

        if (index == NO_FRONT) return null

        try {
            return block(buffers[index])
        } finally {
            releaseFrontLease(index)
        }
    }

    /** Shares acquisition bookkeeping between current calls while honoring older inline readers. */
    @PublishedApi
    internal fun acquireFrontLease(): Int =
        publishLock.withLock {
            val index = frontIndex
            if (index != NO_FRONT) readerCounts[index]++
            index
        }

    private fun acquireWritableIndex(): Int {
        publishLock.withLock {
            while (true) {
                var offset = 0
                while (offset < BUFFER_COUNT) {
                    val index = (nextWriteIndex + offset) % BUFFER_COUNT
                    if (index != frontIndex && readerCounts[index] == 0 && !writerOwned[index]) {
                        writerOwned[index] = true
                        nextWriteIndex = (index + 1) % BUFFER_COUNT
                        return index
                    }
                    offset++
                }
                bufferAvailable.await()
            }
        }
    }

    @PublishedApi
    internal fun releaseFrontLease(index: Int) {
        publishLock.withLock {
            readerCounts[index]--
            check(readerCounts[index] >= 0) {
                "TerminalRenderPublisher reader count underflow for buffer $index"
            }
            bufferAvailable.signalAll()
        }
    }

    private fun releaseWritableIndex(index: Int) {
        publishLock.withLock {
            check(writerOwned[index]) {
                "TerminalRenderPublisher writer lease underflow for buffer $index"
            }
            writerOwned[index] = false
            bufferAvailable.signalAll()
        }
    }

    public companion object {
        @PublishedApi internal const val BUFFER_COUNT: Int = 3

        @PublishedApi internal const val NO_FRONT: Int = -1
    }
}
