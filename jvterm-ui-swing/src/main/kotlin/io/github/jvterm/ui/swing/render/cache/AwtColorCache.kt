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
package io.github.jvterm.ui.swing.render.cache

import java.awt.Color

/**
 * Bounded primitive-keyed LRU cache for packed ARGB AWT colors.
 *
 * Rendering resolves colors as packed integers in hot paths. This cache avoids
 * per-cell boxing for recently used colors while bounding retained [Color]
 * instances for truecolor streams such as gradients, images, and animations.
 *
 * **Thread Safety:** Not thread-safe. This cache must only be accessed
 * from the Swing Event Dispatch Thread (EDT).
 */
internal class AwtColorCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entryKeys = IntArray(capacity)
    private val entryColors = arrayOfNulls<Color>(capacity)
    private val previous = IntArray(capacity) { EMPTY }
    private val next = IntArray(capacity) { EMPTY }
    private val hashKeys = IntArray(hashCapacity(capacity))
    private val hashEntries = IntArray(hashKeys.size) { EMPTY }
    private val hashMask = hashKeys.size - 1
    private var size = 0
    private var head = EMPTY
    private var tail = EMPTY

    /**
     * Returns a cached AWT color for [argb].
     *
     * @param argb packed ARGB color.
     * @return reusable AWT color instance.
     */
    fun color(argb: Int): Color {
        val existing = findEntry(argb)
        if (existing != EMPTY) {
            moveToHead(existing)
            return entryColors[existing]!!
        }

        val color = Color(argb, true)
        put(argb, color)
        return color
    }

    private fun put(
        argb: Int,
        color: Color,
    ) {
        val entry =
            if (size < entryKeys.size) {
                size++
            } else {
                val evicted = tail
                removeHashEntry(entryKeys[evicted], evicted)
                unlink(evicted)
                evicted
            }

        entryKeys[entry] = argb
        entryColors[entry] = color
        linkHead(entry)
        insertHashEntry(argb, entry)
    }

    private fun findEntry(argb: Int): Int {
        var slot = hashSlot(argb)
        while (true) {
            val entry = hashEntries[slot]
            if (entry == EMPTY) return EMPTY
            if (hashKeys[slot] == argb) return entry
            slot = (slot + 1) and hashMask
        }
    }

    private fun insertHashEntry(
        argb: Int,
        entry: Int,
    ) {
        var slot = hashSlot(argb)
        while (hashEntries[slot] != EMPTY) {
            slot = (slot + 1) and hashMask
        }
        hashKeys[slot] = argb
        hashEntries[slot] = entry
    }

    private fun removeHashEntry(
        argb: Int,
        entry: Int,
    ) {
        var slot = hashSlot(argb)
        while (true) {
            if (hashEntries[slot] == entry && hashKeys[slot] == argb) {
                hashEntries[slot] = EMPTY
                reinsertHashCluster((slot + 1) and hashMask)
                return
            }
            slot = (slot + 1) and hashMask
        }
    }

    private fun reinsertHashCluster(startSlot: Int) {
        var slot = startSlot
        while (hashEntries[slot] != EMPTY) {
            val argb = hashKeys[slot]
            val entry = hashEntries[slot]
            hashEntries[slot] = EMPTY
            insertHashEntry(argb, entry)
            slot = (slot + 1) and hashMask
        }
    }

    private fun moveToHead(entry: Int) {
        if (entry == head) return
        unlink(entry)
        linkHead(entry)
    }

    private fun unlink(entry: Int) {
        val previousEntry = previous[entry]
        val nextEntry = next[entry]
        if (previousEntry != EMPTY) {
            next[previousEntry] = nextEntry
        } else {
            head = nextEntry
        }
        if (nextEntry != EMPTY) {
            previous[nextEntry] = previousEntry
        } else {
            tail = previousEntry
        }
        previous[entry] = EMPTY
        next[entry] = EMPTY
    }

    private fun linkHead(entry: Int) {
        previous[entry] = EMPTY
        next[entry] = head
        if (head != EMPTY) {
            previous[head] = entry
        } else {
            tail = entry
        }
        head = entry
    }

    private fun hashSlot(argb: Int): Int {
        var hash = argb
        hash = hash xor (hash ushr 16)
        hash *= -2048144789
        hash = hash xor (hash ushr 13)
        return hash and hashMask
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 4096
        private const val EMPTY = -1

        private fun hashCapacity(entryCapacity: Int): Int {
            var capacity = 1
            while (capacity < entryCapacity * 2) {
                capacity = capacity shl 1
            }
            return capacity
        }
    }
}
