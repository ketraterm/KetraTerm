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

import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.render.api.TerminalRenderImageSink

/**
 * Copied placement metadata in stacking order. Owned by a [TerminalRenderCache];
 * access has the same confinement/lease lifetime as that cache. Resources alone
 * are immutable and retainable. Destination geometry uses fractional viewport
 * cells; source crops use image pixels, as defined by [TerminalRenderImageSink].
 * Indexed reads require `index in 0 until size` and throw [IllegalArgumentException]
 * otherwise. An update replaces this borrowed set, even when resources are reused.
 */
public class TerminalRenderImages internal constructor() {
    /** Number of current placements, possibly sharing resources; zero for an empty or invalidated cache. */
    public var size: Int = 0
        private set
    private var resources = arrayOfNulls<TerminalRenderImage>(0)
    private var geometry = DoubleArray(0)
    private var source = IntArray(0)
    private val sink =
        TerminalRenderImageSink { image, column, row, width, height, sx, sy, sw, sh, top, bottom, z ->
            reserve(size + 1)
            resources[size] = image
            val g = size * 6
            geometry[g] = column
            geometry[g + 1] = row
            geometry[g + 2] = width
            geometry[g + 3] = height
            geometry[g + 4] = top
            geometry[g + 5] = bottom
            val s = size * 5
            source[s] = sx
            source[s + 1] = sy
            source[s + 2] = sw
            source[s + 3] = sh
            source[s + 4] = z
            size++
        }

    /**
     * Retrieves the retainable pixel version, independently of client image IDs.
     *
     * @param index placement index in `0 until size`.
     * @return the immutable resource referenced by that placement, without copying pixels.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun image(index: Int): TerminalRenderImage {
        checkIndex(index)
        return checkNotNull(resources[index])
    }

    /**
     * Reads the destination's left edge.
     *
     * @param index placement index in `0 until size`.
     * @return the left destination edge in fractional viewport cells, including pixel offset.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun column(index: Int): Double = coordinate(index, 0)

    /**
     * Reads the destination's top edge.
     *
     * @param index placement index in `0 until size`.
     * @return the top destination edge in fractional viewport cells; clipped images may start above zero.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun row(index: Int): Double = coordinate(index, 1)

    /**
     * Reads the scaled destination width.
     *
     * @param index placement index in `0 until size`.
     * @return the scaled destination width in cells, before viewport clipping.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun width(index: Int): Double = coordinate(index, 2)

    /**
     * Reads the scaled destination height.
     *
     * @param index placement index in `0 until size`.
     * @return the scaled destination height in cells, before viewport clipping.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun height(index: Int): Double = coordinate(index, 3)

    /**
     * Reads the upper vertical clipping boundary.
     *
     * @param index placement index in `0 until size`.
     * @return the inclusive top clip edge in viewport cells, to intersect with the viewport.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun clipTop(index: Int): Double = coordinate(index, 4)

    /**
     * Reads the lower vertical clipping boundary.
     *
     * @param index placement index in `0 until size`.
     * @return the exclusive bottom clip edge in viewport cells, to intersect with the viewport.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun clipBottom(index: Int): Double = coordinate(index, 5)

    /**
     * Reads the source crop's left edge.
     *
     * @param index placement index in `0 until size`.
     * @return the nonnegative left source-crop edge in image pixels.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun sourceX(index: Int): Int = field(index, 0)

    /**
     * Reads the source crop's top edge.
     *
     * @param index placement index in `0 until size`.
     * @return the nonnegative top source-crop edge in image pixels.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun sourceY(index: Int): Int = field(index, 1)

    /**
     * Reads the source crop's width.
     *
     * @param index placement index in `0 until size`.
     * @return the positive source-crop width in image pixels.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun sourceWidth(index: Int): Int = field(index, 2)

    /**
     * Reads the source crop's height.
     *
     * @param index placement index in `0 until size`.
     * @return the positive source-crop height in image pixels.
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun sourceHeight(index: Int): Int = field(index, 3)

    /**
     * Reads the placement's stacking layer.
     *
     * @param index placement index in `0 until size`.
     * @return the signed layer in the background/text bands defined by [TerminalRenderImageSink].
     * @throws IllegalArgumentException if [index] is outside `0 until size`.
     */
    public fun zIndex(index: Int): Int = field(index, 4)

    private fun checkIndex(index: Int) {
        require(index in 0 until size)
    }

    private fun coordinate(
        index: Int,
        field: Int,
    ): Double {
        checkIndex(index)
        return geometry[index * 6 + field]
    }

    private fun field(
        index: Int,
        field: Int,
    ): Int {
        checkIndex(index)
        return source[index * 5 + field]
    }

    private fun reserve(required: Int) {
        if (resources.size >= required) return
        require(required <= Int.MAX_VALUE / 6)
        val capacity = maxOf(required, (resources.size.toLong() * 2).coerceAtMost(Int.MAX_VALUE / 6L).toInt(), 16)
        resources = resources.copyOf(capacity)
        geometry = geometry.copyOf(capacity * 6)
        source = source.copyOf(capacity * 5)
    }

    internal fun clear() {
        resources.fill(null, 0, size)
        size = 0
    }

    internal fun copyFrom(frame: TerminalRenderFrame) {
        clear()
        try {
            frame.copyImages(sink)
        } catch (failure: Throwable) {
            clear()
            throw failure
        }
    }

    internal fun copyFrom(other: TerminalRenderImages) {
        clear()
        reserve(other.size)
        other.resources.copyInto(resources, 0, 0, other.size)
        other.geometry.copyInto(geometry, 0, 0, other.size * 6)
        other.source.copyInto(source, 0, 0, other.size * 5)
        size = other.size
    }

    /**
     * Delivers this set synchronously in stacking order, sharing immutable pixels.
     * [rowOffset] translates destination rows and vertical clip bounds in cells;
     * it does not change source crops, scaling or the stored placement set.
     *
     * @param sink receiver invoked once per placement before this method returns.
     * @param rowOffset vertical translation in cells, defaulting to zero.
     */
    public fun copyTo(
        sink: TerminalRenderImageSink,
        rowOffset: Double = 0.0,
    ) {
        for (i in 0 until size) {
            sink.accept(
                image(i),
                column(i),
                row(i) + rowOffset,
                width(i),
                height(i),
                sourceX(i),
                sourceY(i),
                sourceWidth(i),
                sourceHeight(i),
                clipTop(i) + rowOffset,
                clipBottom(i) + rowOffset,
                zIndex(i),
            )
        }
    }
}
