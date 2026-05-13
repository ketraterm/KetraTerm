package com.gagik.terminal.render.api

/**
 * Provides a short-lived render frame view.
 *
 * The frame passed to [consumer] is valid only during the callback.
 * Implementations may hold a terminal mutation lock while invoking [consumer].
 * Consumers must copy anything they need before returning.
 */
interface TerminalRenderFrameReader {
    /**
     * Invokes [consumer] with a render frame view whose lifetime is limited to
     * this call.
     *
     * Implementations may reuse the same frame object across calls, so consumers
     * must not retain the frame after [consumer] returns.
     *
     * @param consumer receiver that copies any render data it needs.
     */
    fun readRenderFrame(consumer: TerminalRenderFrameConsumer)

    /**
     * Invokes [consumer] with a render frame for a caller-owned scrollback
     * viewport.
     *
     * [scrollbackOffset] is measured in lines from the live bottom viewport.
     * Implementations clamp it to available history and expose the resolved
     * value through [TerminalRenderFrame.scrollbackOffset]. This method does not
     * mutate terminal state; it only changes how rows are mapped for this read.
     *
     * Implementations that do not support scrollback views may fall back to the
     * bottom-pinned frame by implementing only [readRenderFrame].
     *
     * @param scrollbackOffset requested lines above the live bottom viewport.
     * @param consumer receiver that copies any render data it needs.
     */
    fun readRenderFrame(scrollbackOffset: Int, consumer: TerminalRenderFrameConsumer) {
        readRenderFrame(consumer)
    }
}
