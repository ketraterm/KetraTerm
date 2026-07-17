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
package io.github.ketraterm.completion.internal

/**
 * Bounded keyed row container shared by completion stats indexes.
 *
 * Domain-specific indexes own row creation and update semantics. This helper
 * owns the mechanical invariants: key-based lookup, newest duplicate
 * replacement, least-relevant eviction, and stable sorted snapshots.
 */
internal class BoundedStatsRowIndex<Row : Any, Key : Any>(
    private val capacity: Int,
    private val order: Comparator<Row>,
    private val keySelector: (Row) -> Key,
    private val shouldReplace: (current: Row, candidate: Row) -> Boolean,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entries = ArrayList<Row>(capacity)
    private var publishedSnapshot: List<Row> = emptyList()

    fun replaceAll(records: List<Row>) {
        val compacted = ArrayList<Row>(minOf(records.size, capacity))
        for (record in records) {
            val index = compacted.indexOfKey(keySelector(record), keySelector)
            if (index >= 0) {
                if (shouldReplace(compacted[index], record)) {
                    compacted[index] = record
                }
            } else {
                compacted += record
            }
        }
        compacted.sortWith(order)
        entries.clear()
        entries.addAll(compacted.take(capacity))
        publishSnapshot()
    }

    fun snapshot(): List<Row> = publishedSnapshot

    fun rawRows(): List<Row> = entries

    fun mutate(
        key: Key,
        initialRow: () -> Row,
        update: (Row) -> Row,
    ) {
        val existingIndex = entries.indexOfKey(key, keySelector)
        if (existingIndex >= 0) {
            entries[existingIndex] = update(entries[existingIndex])
        } else {
            if (entries.size == capacity) entries.removeAt(entries.size - 1)
            entries += update(initialRow())
        }
        entries.sortWith(order)
        publishSnapshot()
    }

    private fun publishSnapshot() {
        publishedSnapshot = entries.toList()
    }

    private fun List<Row>.indexOfKey(
        key: Key,
        keySelector: (Row) -> Key,
    ): Int {
        var index = 0
        while (index < size) {
            if (keySelector(this[index]) == key) return index
            index++
        }
        return -1
    }
}
