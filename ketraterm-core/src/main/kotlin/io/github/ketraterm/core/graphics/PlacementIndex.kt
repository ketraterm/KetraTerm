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
package io.github.ketraterm.core.graphics

/**
 * Primitive index from image-slot/placement-ID pairs to movable placement records.
 * The upper 32 key bits hold the image slot, the lower 32 hold the unsigned nonzero
 * placement ID. Zero keys mark empty buckets; anonymous placements are not indexed.
 * Capacity stays a power of two and grows before exceeding one-half occupancy, so
 * linear probing always finds an empty bucket without allocating lookup wrappers.
 */
internal class PlacementIndex {
    private var keys = LongArray(0)
    private var values = IntArray(0)
    private var size = 0

    /** Returns the current record index, or -1 for an absent nonzero key. */
    fun get(key: Long): Int {
        if (keys.isEmpty()) return -1
        val bucket = bucket(key)
        return if (keys[bucket] == key) values[bucket] else -1
    }

    fun put(
        key: Long,
        value: Int,
    ) {
        require(key != 0L)
        if (keys.isNotEmpty()) {
            val existing = bucket(key)
            if (keys[existing] == key) {
                values[existing] = value
                return
            }
        }
        if ((size + 1) * 2 > keys.size) grow()
        val bucket = bucket(key)
        if (keys[bucket] == 0L) size++
        keys[bucket] = key
        values[bucket] = value
    }

    fun remove(key: Long) {
        if (keys.isEmpty()) return
        var bucket = bucket(key)
        if (keys[bucket] == 0L) return
        keys[bucket] = 0
        size--
        // Reinsert the following cluster; deletion cannot break another key's probe chain.
        bucket = (bucket + 1) and (keys.size - 1)
        while (keys[bucket] != 0L) {
            val shiftedKey = keys[bucket]
            val shiftedValue = values[bucket]
            keys[bucket] = 0
            val destination = bucket(shiftedKey)
            keys[destination] = shiftedKey
            values[destination] = shiftedValue
            bucket = (bucket + 1) and (keys.size - 1)
        }
    }

    private fun bucket(key: Long): Int {
        var hash = key xor (key ushr 33)
        hash *= -49064778989728563L
        hash = hash xor (hash ushr 33)
        var bucket = hash.toInt() and (keys.size - 1)
        while (keys[bucket] != 0L && keys[bucket] != key) bucket = (bucket + 1) and (keys.size - 1)
        return bucket
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(keys.size.coerceAtLeast(8) * 2)
        values = IntArray(keys.size)
        for (i in oldKeys.indices) {
            val key = oldKeys[i]
            if (key != 0L) {
                val bucket = bucket(key)
                keys[bucket] = key
                values[bucket] = oldValues[i]
            }
        }
    }
}
