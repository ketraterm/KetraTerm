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
package io.github.ketraterm.session

import io.github.ketraterm.render.api.TerminalRenderFrame
import java.util.*

/** Retains spatial positions while replacing only the mutable suffix of the primary buffer. */
internal class TerminalShellLineOrder {
    private var rows = LongArray(32)
    private var head = 0
    private var size = 0
    private var start = 0L
    private var ids = LongArray(32)
    private var positions = LongArray(32)
    private var count = 0
    private var valid = false

    fun clear() {
        head = 0
        size = 0
        count = 0
        valid = false
    }

    fun position(id: Long): Long {
        if (!valid) return -1
        val index = Arrays.binarySearch(ids, 0, count, id)
        if (index < 0) return -1
        return positions[index].takeIf { it >= start && it < start + size } ?: -1
    }

    fun lineId(position: Long): Long = if (valid && position >= start && position < start + size) row((position - start).toInt()) else 0

    fun rebuild(frame: TerminalRenderFrame) {
        clear()
        start = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        ensureRows(frame.rows)
        ensureIndex(frame.rows)
        for (i in 0 until frame.rows) {
            val id = frame.lineId(i)
            if (id <= 0) return
            rows[i] = id
            ids[i] = id
        }
        size = frame.rows
        Arrays.sort(ids, 0, size)
        for (i in 0 until size) {
            if (count == 0 || ids[i] != ids[count - 1]) ids[count++] = ids[i]
        }
        Arrays.fill(positions, 0, count, -1L)
        for (i in 0 until size) {
            if (i > 0 && rows[i] == rows[i - 1]) continue
            val index = Arrays.binarySearch(ids, 0, count, rows[i])
            if (positions[index] >= 0) return
            positions[index] = start + i
        }
        valid = true
    }

    /** Returns false when a full read is needed, including reflow or an unknown older identity. */
    fun update(frame: TerminalRenderFrame): Boolean {
        if (!valid) return false
        val first = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        if (first < start || first > start + size || frame.discardedCount > start + size) return false
        val dropped = (frame.discardedCount - start).coerceAtLeast(0).toInt()
        head = (head + dropped) % rows.size
        start += dropped
        size -= dropped
        if (size > 0) {
            val firstIndex = Arrays.binarySearch(ids, 0, count, row(0))
            if (positions[firstIndex] < start) positions[firstIndex] = start
        }
        val retained = (first - start).toInt()
        for (i in size - 1 downTo retained) {
            val index = Arrays.binarySearch(ids, 0, count, row(i))
            if (positions[index] >= first) positions[index] = -1
        }
        size = retained
        ensureRows(size + frame.rows)
        for (i in 0 until frame.rows) {
            val id = frame.lineId(i)
            if (id <= 0) return false
            if (size == 0 || row(size - 1) != id) {
                var index = Arrays.binarySearch(ids, 0, count, id)
                if (index < 0) {
                    // New core identities increase. Reflow can restore older identities.
                    if (count > 0 && id < ids[count - 1]) return false
                    ensureIndex(count + 1)
                    index = count++
                    ids[index] = id
                } else if (positions[index] >= start && positions[index] < start + size) {
                    return false
                }
                positions[index] = start + size
            }
            rows[(head + size) % rows.size] = id
            size++
        }
        if (count.toLong() > size.toLong() * 2 + 256) compactIndex()
        return true
    }

    private fun row(index: Int): Long = rows[(head + index) % rows.size]

    private fun ensureRows(required: Int) {
        if (required <= rows.size) return
        val replacement = LongArray(maxOf(required, rows.size * 2))
        for (i in 0 until size) replacement[i] = row(i)
        rows = replacement
        head = 0
    }

    private fun ensureIndex(required: Int) {
        if (required <= ids.size) return
        val capacity = maxOf(required, ids.size * 2)
        ids = ids.copyOf(capacity)
        positions = positions.copyOf(capacity)
    }

    private fun compactIndex() {
        var destination = 0
        for (i in 0 until count) {
            val position = positions[i]
            if (position >= start && position < start + size) {
                ids[destination] = ids[i]
                positions[destination++] = position
            }
        }
        count = destination
    }
}
