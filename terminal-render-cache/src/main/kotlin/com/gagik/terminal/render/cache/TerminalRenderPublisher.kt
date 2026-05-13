package com.gagik.terminal.render.cache

import com.gagik.terminal.render.api.TerminalRenderFrameReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Triple-buffered render cache publisher.
 *
 * One buffer is writer-owned (back).
 * One buffer is UI-readable (front).
 * One buffer is spare (recycled after front is replaced).
 *
 * Writer and UI never touch the same buffer simultaneously.
 * No locks on the paint path.
 */
class TerminalRenderPublisher(
    columns: Int,
    rows: Int,
) {
    private val buffers = Array(3) { TerminalRenderCache(columns, rows) }

    // Indices - only mutated under publishLock.
    private var frontIndex = 0
    private var backIndex = 1
    private var spareIndex = 2

    private val publishLock = Any()

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
        val back = synchronized(publishLock) { buffers[backIndex] }

        // The selected back buffer is render-worker-exclusive here because the
        // session coalesces dirty notifications to at most one queued render
        // task, and session resize shares the same terminal mutation lock used
        // by the render frame reader.
        back.updateFrom(reader, scrollbackOffset)

        synchronized(publishLock) {
            val oldFront = frontIndex
            frontIndex = backIndex
            backIndex = spareIndex
            spareIndex = oldFront
            frontRef.set(buffers[frontIndex])
        }
    }

    /**
     * Called from EDT only.
     * Returns the latest published snapshot. Never null after first publish.
     * The returned cache must not be retained across paint calls.
     */
    fun current(): TerminalRenderCache? = frontRef.get()

    /**
     * Resizes all buffers atomically.
     *
     * Callers must ensure that neither the render worker nor the UI thread are
     * actively using a specific buffer during this call, or that they can
     * handle the dimension change. Typically called from the EDT or a control
     * thread.
     */
    fun resize(columns: Int, rows: Int) {
        synchronized(publishLock) {
            buffers.forEach {
                it.resize(columns, rows)
                it.resetOwnership()
            }
        }
    }
}
