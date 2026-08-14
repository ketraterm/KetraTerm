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

    private val entriesByKey = HashMap<Key, IndexedRow<Row, Key>>(capacity)
    private val orderedEntries = ArrayList<IndexedRow<Row, Key>>(capacity)
    private var publishedSnapshot: List<Row> = emptyList()

    fun replaceAll(records: List<Row>) {
        val compacted = LinkedHashMap<Key, Row>(minOf(records.size, capacity))
        for (record in records) {
            val key = keySelector(record)
            val current = compacted[key]
            if (current != null) {
                if (shouldReplace(current, record)) {
                    compacted[key] = record
                }
            } else {
                compacted[key] = record
            }
        }
        rebuild(compacted.entries.map { (key, row) -> IndexedRow(key, row) })
    }

    fun mergeAll(
        records: List<Row>,
        merge: (current: Row, incoming: Row) -> Row,
    ) {
        for (record in records) {
            val key = keySelector(record)
            val current = entriesByKey[key]
            if (current != null) {
                current.row = merge(current.row, record)
            } else {
                entriesByKey[key] = IndexedRow(key, record)
            }
        }
        rebuild(entriesByKey.values)
    }

    fun snapshot(): List<Row> = publishedSnapshot

    fun copy(): BoundedStatsRowIndex<Row, Key> {
        val copy = BoundedStatsRowIndex(capacity, order, keySelector, shouldReplace)
        for (entry in orderedEntries) {
            val copiedEntry = IndexedRow(entry.key, entry.row)
            copy.orderedEntries += copiedEntry
            copy.entriesByKey[copiedEntry.key] = copiedEntry
        }
        copy.publishedSnapshot = publishedSnapshot
        return copy
    }

    fun mutate(
        key: Key,
        initialRow: () -> Row,
        update: (Row) -> Row,
    ) {
        val existing = entriesByKey[key]
        if (existing != null) {
            check(orderedEntries.remove(existing)) { "indexed completion statistics row is missing from sorted storage" }
            existing.row = update(existing.row)
            insertOrdered(existing)
        } else {
            val created = IndexedRow(key, update(initialRow()))
            entriesByKey[key] = created
            insertOrdered(created)
            if (orderedEntries.size > capacity) {
                val evicted = orderedEntries.removeAt(orderedEntries.lastIndex)
                entriesByKey.remove(evicted.key)
                if (evicted === created) return
            }
        }
        publishSnapshot()
    }

    private fun rebuild(rows: Collection<IndexedRow<Row, Key>>) {
        orderedEntries.clear()
        orderedEntries.addAll(rows)
        orderedEntries.sortWith { left, right -> order.compare(left.row, right.row) }
        if (orderedEntries.size > capacity) {
            orderedEntries.subList(capacity, orderedEntries.size).clear()
        }
        entriesByKey.clear()
        for (entry in orderedEntries) entriesByKey[entry.key] = entry
        publishSnapshot()
    }

    private fun insertOrdered(entry: IndexedRow<Row, Key>) {
        var low = 0
        var high = orderedEntries.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (order.compare(orderedEntries[middle].row, entry.row) <= 0) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        orderedEntries.add(low, entry)
    }

    private fun publishSnapshot() {
        publishedSnapshot = List(orderedEntries.size) { index -> orderedEntries[index].row }
    }

    private class IndexedRow<Row : Any, Key : Any>(
        val key: Key,
        var row: Row,
    )
}
