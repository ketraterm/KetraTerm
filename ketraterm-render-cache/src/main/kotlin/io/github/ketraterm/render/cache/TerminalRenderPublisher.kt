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
 * lease acquisition and release. Only cache-reference acquisition/release is part of
 * the reader ABI; buffer layout, indices, locks and counters remain private.
 *
 * @param columns initial cache width in cells.
 * @param rows initial cache height in rows.
 */
public class TerminalRenderPublisher(
    columns: Int,
    rows: Int,
) {
    private val buffers: Array<TerminalRenderCache> = Array(3) { TerminalRenderCache(columns, rows) }

    private val readerCounts: IntArray = IntArray(BUFFER_COUNT)
    private val writerOwned = BooleanArray(BUFFER_COUNT)

    // Buffer indices, reader counts, and writer leases are mutated under publishLock.
    private var frontIndex: Int = NO_FRONT
    private var nextWriteIndex = 0

    private val publishLock: ReentrantLock = ReentrantLock()
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
        val cache = acquireFrontLease() ?: return null

        try {
            return block(cache)
        } finally {
            releaseFrontLease(cache)
        }
    }

    /**
     * Pins the current cache, or returns null before the first publication.
     *
     * Bridge for inline readers in other modules; prefer [readCurrent]. Each non-null
     * result must be passed exactly once to [releaseFrontLease] on this publisher in
     * a finally block, including on failure. The cache and its arrays are borrowed,
     * read-only, and must not escape that lease. Acquisition does not allocate a lease.
     * Do not reenter publication while holding one. Safe from any thread.
     */
    public fun acquireFrontLease(): TerminalRenderCache? =
        publishLock.withLock {
            val index = frontIndex
            if (index == NO_FRONT) return null
            check(readerCounts[index] < Int.MAX_VALUE) { "Too many render readers" }
            readerCounts[index]++
            buffers[index]
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

    /**
     * Releases one successful [acquireFrontLease] using the identical cache reference.
     *
     * Only the matching acquisition owner may release it, once, after all reads end.
     * No storage may be used afterward. A foreign cache or a cache with no outstanding
     * readers is rejected without changing counts. This cannot detect duplicate releases
     * while another reader holds the same cache; correct pairing is the caller's duty.
     */
    public fun releaseFrontLease(cache: TerminalRenderCache) {
        publishLock.withLock {
            val index = buffers.indexOfFirst { it === cache }
            require(index >= 0) { "Cache does not belong to this publisher" }
            check(readerCounts[index] > 0) { "Render cache has no reader lease" }
            readerCounts[index]--
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

    private companion object {
        const val BUFFER_COUNT: Int = 3

        const val NO_FRONT: Int = -1
    }
}
