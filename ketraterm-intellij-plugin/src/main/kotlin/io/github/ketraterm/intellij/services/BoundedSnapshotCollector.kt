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
package io.github.ketraterm.intellij.services

import java.util.*

/** Retains the first [capacity] values under [order] without retaining an unbounded intermediate list. */
internal class BoundedSnapshotCollector<T>(
    private val capacity: Int,
    private val order: Comparator<in T>,
) {
    private val retained: PriorityQueue<T>

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        retained = PriorityQueue(capacity, order.reversed())
    }

    /** Offers one value, discarding the current worst value when the bounded set improves. */
    fun add(value: T) {
        if (retained.size < capacity) {
            retained += value
        } else if (order.compare(value, retained.peek()) < 0) {
            retained.remove()
            retained += value
        }
    }

    /** Returns a detached deterministic list ordered by [order]. */
    fun toSortedList(): List<T> = ArrayList(retained).apply { sortWith(order) }
}
