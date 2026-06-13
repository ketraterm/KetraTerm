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
package io.github.jvterm.core.buffer

import io.github.jvterm.core.model.Line

/**
 * A fixed-capacity ring buffer of [Line]s.
 */
internal class HistoryRing(
    val capacity: Int,
    private val lineFactory: () -> Line,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val data: Array<Line> = Array(capacity) { lineFactory() }

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
        return data[(head + i) % capacity]
    }

    /**
     * Pushes a new line into the ring.
     * If full, the oldest line is recycled and returned for reuse.
     */
    fun push(): Line =
        if (size < capacity) {
            val slot = (head + size) % capacity
            size++
            data[slot]
        } else {
            val recycled = data[head]
            head = (head + 1) % capacity
            discardedCount++
            recycled
        }

    /**
     * Rotates lines in the logical range [fromLogical, toLogical] upward by one slot.
     * The line at [fromLogical] is moved to [toLogical]; everything else shifts toward [fromLogical] by one.
     * After this call, the line now at [toLogical] is the one that was at [fromLogical] —
     * caller is responsible for clearing it to create a blank scroll-in line.
     */
    internal fun rotateUp(
        fromLogical: Int,
        toLogical: Int,
    ) {
        val evicted = data[(head + fromLogical) % capacity]
        for (i in fromLogical until toLogical) {
            data[(head + i) % capacity] = data[(head + i + 1) % capacity]
        }
        data[(head + toLogical) % capacity] = evicted
    }

    /**
     * Rotates lines in the logical range [fromLogical, toLogical] downward by one slot.
     * The line at [toLogical] is moved to [fromLogical]; everything else shifts toward [toLogical] by one.
     * After this call, the line now at [fromLogical] is the one that was at [toLogical] —
     * caller is responsible for clearing it to create a blank scroll-in line.
     */
    internal fun rotateDown(
        fromLogical: Int,
        toLogical: Int,
    ) {
        val evicted = data[(head + toLogical) % capacity]
        for (i in toLogical downTo fromLogical + 1) {
            data[(head + i) % capacity] = data[(head + i - 1) % capacity]
        }
        data[(head + fromLogical) % capacity] = evicted
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
}
