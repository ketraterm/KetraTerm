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
package io.github.jvterm.core.model

internal class HostResponseQueue(
    initialCapacity: Int = 64,
) {
    private var bytes = ByteArray(initialCapacity)
    private var head = 0
    private var size = 0

    val pendingByteCount: Int
        get() = size

    fun clear() {
        head = 0
        size = 0
    }

    fun enqueueByte(value: Int) {
        ensureCapacity(size + 1)
        bytes[(head + size) % bytes.size] = value.toByte()
        size++
    }

    fun enqueuePositiveDecimal(value: Int) {
        if (value == 0) {
            enqueueByte('0'.code)
            return
        }

        var divisor = 1
        while (value / divisor >= 10) {
            divisor *= 10
        }

        var remaining = value
        while (divisor > 0) {
            enqueueByte('0'.code + (remaining / divisor))
            remaining %= divisor
            divisor /= 10
        }
    }

    fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0) { "offset must be non-negative: $offset" }
        require(length >= 0) { "length must be non-negative: $length" }
        require(offset <= destination.size) { "offset out of range: $offset" }
        require(offset + length <= destination.size) {
            "offset + length out of range: offset=$offset length=$length size=${destination.size}"
        }

        val count = minOf(length, size)
        if (count == 0) return 0

        readWithoutConsuming(destination, offset, count)

        head = (head + count) % bytes.size
        size -= count
        if (size == 0) head = 0

        return count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return

        var newCapacity = bytes.size
        while (newCapacity < required) {
            newCapacity *= 2
        }

        val replacement = ByteArray(newCapacity)
        readWithoutConsuming(replacement, 0, size)
        bytes = replacement
        head = 0
    }

    private fun readWithoutConsuming(
        destination: ByteArray,
        offset: Int,
        count: Int,
    ) {
        val first = minOf(count, bytes.size - head)
        bytes.copyInto(destination, offset, head, head + first)

        val second = count - first
        if (second > 0) {
            bytes.copyInto(destination, offset + first, 0, second)
        }
    }
}
