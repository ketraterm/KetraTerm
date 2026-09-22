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
package io.github.ketraterm.parser.ansi

/**
 * Ordinary resource ceilings for collected OSC/DCS bytes, including family prefixes
 * and separators, excluding introducers, terminators, and controls ignored by the FSM.
 * These are parser compatibility rules, independent of host permission/decoded-size limits.
 * Eligible OSC 52 writes may use [clipboardLimit] with a host-supplied decoded-byte budget.
 */
internal object ControlStringPolicy {
    const val MAX_PAYLOAD_BYTES: Int = 4096
    const val MAX_DYNAMIC_COLOR_BYTES: Int = 256
    const val MAX_STATUS_REQUEST_BYTES: Int = 64

    /** Includes actual envelope overhead; Long arithmetic prevents a host budget from wrapping. */
    fun clipboardLimit(
        dataStart: Int,
        decodedBytes: Int,
    ): Int {
        require(decodedBytes >= 0) { "clipboardWriteLimitBytes must be nonnegative, got $decodedBytes" }
        val encodedBytes = ((decodedBytes.toLong() + 2) / 3) * 4
        return (dataStart + encodedBytes).coerceAtMost(Int.MAX_VALUE - 8L).toInt()
    }

    /** Zero means unsupported: stop collecting without decoding or dispatching the body. */
    fun oscLimit(command: Int): Int =
        when (command) {
            0, 1, 2, 4, 7, 8, 9, 52, 133, 777 -> MAX_PAYLOAD_BYTES
            // Three implemented dynamic-color targets, with room for color syntax and whitespace.
            10, 11, 12 -> MAX_DYNAMIC_COLOR_BYTES
            else -> 0
        }

    fun dcsLimit(
        first: Int,
        second: Int,
    ): Int =
        when {
            // Short status selectors; retain headroom for unsupported selectors and extensions.
            first == '$'.code && second == 'q'.code -> MAX_STATUS_REQUEST_BYTES
            // Capability requests are batched; do not bound them by one allowlisted name.
            first == '+'.code && second == 'q'.code -> MAX_PAYLOAD_BYTES
            else -> 0
        }

    /** Parses only the decimal prefix before the first semicolon; -1 means malformed. */
    fun oscCommand(
        payload: ByteArray,
        endExclusive: Int,
    ): Int {
        if (endExclusive <= 0) return -1
        var value = 0
        for (index in 0 until endExclusive) {
            val digit = (payload[index].toInt() and 0xff) - '0'.code
            if (digit !in 0..9 || value > (Int.MAX_VALUE - digit) / 10) return -1
            value = value * 10 + digit
        }
        return value
    }
}
