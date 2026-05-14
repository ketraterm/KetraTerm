package com.gagik.terminal.render.cache

import com.gagik.terminal.render.api.TerminalRenderFrame
import com.gagik.terminal.render.api.TerminalRenderFrameReader
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
 */
class TerminalRenderPublisher(
    columns: Int,
    rows: Int,
) {
    private val buffers = Array(3) { TerminalRenderCache(columns, rows) }
    private val readerCounts = IntArray(BUFFER_COUNT)
    private val writerOwned = BooleanArray(BUFFER_COUNT)

    // Buffer indices, reader counts, and writer leases are mutated under publishLock.
    private var frontIndex = NO_FRONT
    private var nextWriteIndex = 0
    private val publishLock = ReentrantLock()
    private val bufferAvailable = publishLock.newCondition()

    // AtomicReference for lock-free front reads.
    private val frontRef = AtomicReference<TerminalRenderCache?>(null)

    /**
     * Called from render worker thread only.
     * Reads from [reader], updates back buffer, publishes as new front.
     */
    fun updateAndPublish(reader: TerminalRenderFrameReader) {
        updateAndPublish(reader, scrollbackOffset = 0)
    }

    /**
     * Called from render worker thread only.
     *
     * [scrollbackOffset] is caller-owned viewport state in lines above the live
     * bottom viewport. The source reader clamps it before rows are copied.
     */
    fun updateAndPublish(reader: TerminalRenderFrameReader, scrollbackOffset: Int) {
        updateAndPublish(reader, scrollbackOffset, viewportRows = 0)
    }

    /**
     * Called from render worker thread only.
     *
     * [viewportRows] requests render-only overscan rows for UI composition. It
     * does not resize terminal state; the source reader clamps the resolved
     * frame height before rows are copied.
     */
    fun updateAndPublish(reader: TerminalRenderFrameReader, scrollbackOffset: Int, viewportRows: Int) {
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
     * This is intended for short polling and tests that do not retain the
     * returned cache or require multi-field snapshot stability. Paint and
     * repaint-planning code should use [readCurrent].
     */
    fun current(): TerminalRenderCache? = frontRef.get()

    /**
     * Reads the latest published front buffer while preventing it from being
     * recycled as a writer-owned back buffer.
     *
     * The callback should only copy or paint from the cache and must not call
     * back into this publisher. Returning `null` means no frame has been
     * published yet.
     *
     * @param block reader invoked with the current front buffer.
     * @return [block]'s result, or `null` when no frame is available.
     */
    fun <T> readCurrent(block: (TerminalRenderCache) -> T): T? {
        val lease = acquireFrontLease() ?: return null
        try {
            return block(lease.cache)
        } finally {
            releaseFrontLease(lease.index)
        }
    }

    /**
     * Records a host resize boundary without mutating published cache buffers.
     *
     * Cache storage is resized only by the writer-owned [updateAndPublish]
     * path, using the resolved [TerminalRenderFrame] shape. This keeps every
     * published or in-flight cache snapshot stable while UI readers paint and
     * while a render callback is copying overscan rows.
     */
    fun resize(columns: Int, rows: Int) {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(rows > 0) { "rows must be > 0, was $rows" }
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

    private fun acquireFrontLease(): FrontLease? {
        publishLock.withLock {
            val index = frontIndex
            if (index == NO_FRONT) return null

            readerCounts[index]++
            return FrontLease(index, buffers[index])
        }
    }

    private fun releaseFrontLease(index: Int) {
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

    private data class FrontLease(
        val index: Int,
        val cache: TerminalRenderCache,
    )

    private companion object {
        private const val BUFFER_COUNT = 3
        private const val NO_FRONT = -1
    }
}
