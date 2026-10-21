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
package io.github.ketraterm.protocol

/**
 * Reusable, ordered OSC 21 operations with borrowed original key bytes.
 *
 * A producer fills this batch before synchronous delivery. Consumers must not
 * retain or mutate it or its source bytes. Queries observe preceding operations
 * and contribute to one reply in request order. Numeric targets are indexed
 * colors in `0..255`; named targets and operations use the constants below.
 *
 * Storage is allocated once. [capacity] bounds the number of nonempty fields;
 * it does not impose a wire payload limit, which belongs to the parser.
 */
public class TerminalColorRequest(
    capacity: Int,
) {
    init {
        require(capacity >= 0) { "Color request capacity must be nonnegative" }
    }

    private val targets = IntArray(capacity)
    private val operations = IntArray(capacity)
    private val colors = IntArray(capacity)
    private val keyStarts = IntArray(capacity)
    private val keyEnds = IntArray(capacity)
    private var payload = ByteArray(0)

    /** Number of initialized operations. */
    public var count: Int = 0
        private set

    /** Starts a new batch borrowing [payload] until its synchronous consumption finishes. */
    public fun clear(payload: ByteArray) {
        this.payload = payload
        count = 0
    }

    /**
     * Appends one operation. [color] is packed ARGB for [SET], otherwise ignored.
     * [keyStart] inclusive and [keyEnd] exclusive select the original nonempty key.
     */
    public fun add(
        target: Int,
        operation: Int,
        color: Int,
        keyStart: Int,
        keyEnd: Int,
    ) {
        require(count < targets.size) { "Color request capacity exceeded" }
        require(target in UNKNOWN..CURSOR_TEXT) { "Invalid color target" }
        require(operation in SET..QUERY) { "Invalid color operation" }
        require(keyStart in 0..<keyEnd && keyEnd <= payload.size) { "Invalid color key range" }
        targets[count] = target
        operations[count] = operation
        colors[count] = color
        keyStarts[count] = keyStart
        keyEnds[count] = keyEnd
        count++
    }

    /** Semantic target at [index]. */
    public fun target(index: Int): Int {
        checkIndex(index)
        return targets[index]
    }

    /** Operation at [index]. */
    public fun operation(index: Int): Int {
        checkIndex(index)
        return operations[index]
    }

    /** Packed ARGB argument at [index]. */
    public fun color(index: Int): Int {
        checkIndex(index)
        return colors[index]
    }

    /** Length of the original key in bytes. */
    public fun keyLength(index: Int): Int {
        checkIndex(index)
        return keyEnds[index] - keyStarts[index]
    }

    /** Unsigned original key byte. Unknown keys must be encoded before inclusion in replies. */
    public fun keyByte(
        index: Int,
        offset: Int,
    ): Int {
        require(offset >= 0 && offset < keyLength(index)) { "Color key offset out of range" }
        return payload[keyStarts[index] + offset].toInt() and 0xff
    }

    private fun checkIndex(index: Int) {
        require(index in 0..<count) { "Color operation index out of range" }
    }

    public companion object {
        public const val UNKNOWN: Int = -1
        public const val FOREGROUND: Int = 256
        public const val BACKGROUND: Int = 257
        public const val SELECTION_FOREGROUND: Int = 258
        public const val SELECTION_BACKGROUND: Int = 259
        public const val CURSOR: Int = 260
        public const val CURSOR_TEXT: Int = 261
        public const val SET: Int = 0
        public const val RESET: Int = 1
        public const val DYNAMIC: Int = 2
        public const val QUERY: Int = 3
    }
}
