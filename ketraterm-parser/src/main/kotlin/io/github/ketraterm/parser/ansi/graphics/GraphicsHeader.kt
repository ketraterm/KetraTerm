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
package io.github.ketraterm.parser.ansi.graphics

import io.github.ketraterm.protocol.TerminalGraphicsCommand

/** Reusable primitive controls; only the read-only protocol view crosses the sink boundary. */
internal class GraphicsHeader : TerminalGraphicsCommand {
    private val values = LongArray(128) { TerminalGraphicsCommand.OMITTED }

    override fun value(key: Char): Long = if (key.code < values.size) values[key.code] else TerminalGraphicsCommand.OMITTED

    fun clear() = values.fill(TerminalGraphicsCommand.OMITTED)

    fun copyFrom(source: GraphicsHeader) = source.values.copyInto(values)

    fun set(
        key: Char,
        value: Long,
    ) {
        values[key.code] = value
    }

    fun parse(
        bytes: ByteArray,
        length: Int,
    ): Boolean {
        clear()
        var offset = 0
        while (offset < length) {
            val key = bytes[offset++].toInt().toChar()
            if (key !in KEYS || offset >= length || bytes[offset++].toInt() != '='.code || offset >= length) return false
            if (value(key) != TerminalGraphicsCommand.OMITTED) return false
            val value: Long
            if (key in CHARACTER_KEYS) {
                val char = bytes[offset++].toInt()
                if (char !in 32..126 || char == ','.code) return false
                value = char.toLong()
            } else {
                var negative = false
                if (bytes[offset].toInt() == '-'.code) {
                    if (key !in SIGNED_KEYS) return false
                    negative = true
                    offset++
                }
                val start = offset
                var number = 0L
                val maximum =
                    if (key in SIGNED_KEYS) {
                        if (negative) 2147483648L else 2147483647L
                    } else {
                        4294967295L
                    }
                while (offset < length && bytes[offset].toInt() in '0'.code..'9'.code) {
                    val digit = bytes[offset++].toInt() - '0'.code
                    if (number > (maximum - digit) / 10) return false
                    number = number * 10 + digit
                }
                if (offset == start) return false
                value = if (negative) -number else number
            }
            set(key, value)
            if (offset < length && (bytes[offset++].toInt() != ','.code || offset == length)) return false
        }
        return true
    }

    companion object {
        // Includes known, currently unavailable animation/virtual/relative controls so
        // the receiver can return ENOTSUP instead of reinterpreting them.
        const val KEYS = "afstovSOiIpqmxywhXYcrCzdUPQHVN"
        const val CHARACTER_KEYS = "atod"
        const val SIGNED_KEYS = "zHV"
    }
}
