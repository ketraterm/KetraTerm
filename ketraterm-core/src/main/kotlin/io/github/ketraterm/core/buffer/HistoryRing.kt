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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.model.Line

/**
 * A bounded ring whose row storage grows synchronously in batches of 128.
 * The reference table has full [capacity], but only [initialRows] plus the first
 * batch of spare rows are constructed upfront. Existing rows are reused on clear
 * and on capacity wrapping. All access, including [lineFactory], is caller-serialized.
 * The factory must keep the same width and cluster store throughout the ring's lifetime.
 */
internal class HistoryRing(
    val capacity: Int,
    initialRows: Int = 0,
    private val lineFactory: () -> Line,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        require(initialRows in 0..capacity) { "initialRows must be in 0..capacity, was $initialRows" }
    }

    private val data: Array<Line?> = arrayOfNulls(capacity)
    private var allocatedRows: Int = 0

    init {
        allocateRows(initialRows + minOf(ROW_BATCH_SIZE, capacity - initialRows))
    }

    private fun allocateRows(target: Int) {
        while (allocatedRows < target) {
            data[allocatedRows] = lineFactory()
            allocatedRows++
        }
    }

    private var head: Int = 0 // physical index of the oldest element
    var size: Int = 0 // number of logical lines currently in the ring
        private set

    /**
     * Total number of history lines discarded due to capacity wrapping.
     */
    var discardedCount: Long = 0L
        private set

    /**
     * Gets the Line at the specified logical index (0 = oldest, size-1 = newest).
     */
    operator fun get(i: Int): Line {
        if (i !in 0 until size) throw IndexOutOfBoundsException("index $i out of bounds (size=$size)")
        return checkNotNull(data[(head + i) % capacity])
    }

    /**
     * Pushes a new line into the ring.
     * If full, the oldest line is recycled and returned for reuse.
     */
    fun push(): Line =
        if (size < capacity) {
            if (size == allocatedRows) {
                allocateRows(allocatedRows + minOf(ROW_BATCH_SIZE, capacity - allocatedRows))
            }
            val slot = (head + size) % capacity
            val line = checkNotNull(data[slot])
            size++
            line
        } else {
            val recycled = checkNotNull(data[head])
            head = (head + 1) % capacity
            discardedCount++
            recycled
        }

    /**
     * Rotates the inclusive logical range upward by [count] slots in O(range length)
     * time without allocating. [count] must be in 0..range length.
     * The first [count] rows move to the end in their original order; callers own clearing.
     */
    internal fun rotateUp(
        fromLogical: Int,
        toLogical: Int,
        count: Int = 1,
    ) {
        val length = toLogical - fromLogical + 1
        require(count in 0..length) { "count must be in 0..$length, was $count" }
        if (count == 0 || count == length) return
        if (count > 1) {
            reverseRange(fromLogical, fromLogical + count - 1)
            reverseRange(fromLogical + count, toLogical)
            reverseRange(fromLogical, toLogical)
            return
        }
        val evicted = data[(head + fromLogical) % capacity]
        for (i in fromLogical until toLogical) {
            data[(head + i) % capacity] = data[(head + i + 1) % capacity]
        }
        data[(head + toLogical) % capacity] = evicted
    }

    /**
     * Rotates the inclusive logical range downward by [count] slots in O(range length)
     * time without allocating. [count] must be in 0..range length.
     * The last [count] rows move to the start in their original order; callers own clearing.
     */
    internal fun rotateDown(
        fromLogical: Int,
        toLogical: Int,
        count: Int = 1,
    ) {
        val length = toLogical - fromLogical + 1
        require(count in 0..length) { "count must be in 0..$length, was $count" }
        if (count == 0 || count == length) return
        if (count > 1) {
            rotateUp(fromLogical, toLogical, length - count)
            return
        }
        val evicted = data[(head + toLogical) % capacity]
        for (i in toLogical downTo fromLogical + 1) {
            data[(head + i) % capacity] = data[(head + i - 1) % capacity]
        }
        data[(head + fromLogical) % capacity] = evicted
    }

    private fun reverseRange(
        fromLogical: Int,
        toLogical: Int,
    ) {
        var left = ((head.toLong() + fromLogical) % capacity).toInt()
        var right = ((head.toLong() + toLogical) % capacity).toInt()
        repeat((toLogical - fromLogical + 1) / 2) {
            val saved = data[left]
            data[left] = data[right]
            data[right] = saved
            if (++left == capacity) left = 0
            if (--right < 0) right = capacity - 1
        }
    }

    /**
     * Keeps the newest [count] logical rows and resets eviction accounting.
     * Removed rows remain allocated for reuse, in unspecified order. The caller
     * must clear their contents first to release any cluster handles.
     */
    fun retainLast(count: Int) {
        require(count in 0..size) { "count must be in 0..size, was $count" }
        val removed = size - count
        if (removed > 0) {
            var target = head
            var source = ((head.toLong() + removed) % capacity).toInt()
            repeat(count) {
                val reusable = data[target]
                data[target] = data[source]
                data[source] = reusable
                if (++target == capacity) target = 0
                if (++source == capacity) source = 0
            }
        }
        size = count
        discardedCount = 0L
    }

    /**
     * Clears the ring buffer by resetting head and size.
     * The Line objects themselves are not modified, but they will be overwritten by future pushes.
     */
    fun clear() {
        head = 0
        size = 0
        discardedCount = 0L
    }

    private companion object {
        const val ROW_BATCH_SIZE = 128
    }
}
