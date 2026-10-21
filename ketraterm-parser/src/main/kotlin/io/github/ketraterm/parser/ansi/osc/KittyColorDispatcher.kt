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
package io.github.ketraterm.parser.ansi.osc

import io.github.ketraterm.parser.ansi.ControlStringPolicy
import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.protocol.TerminalColorRequest

internal class KittyColorDispatcher {
    // Every nonempty field requires at least one key byte and a separator.
    private val request = TerminalColorRequest((ControlStringPolicy.MAX_PAYLOAD_BYTES + 1) / 2)

    fun dispatch(
        sink: TerminalCommandSink,
        payload: ByteArray,
        start: Int,
        end: Int,
    ) {
        request.clear(payload)
        var fieldStart = start
        while (fieldStart < end) {
            var fieldEnd = fieldStart
            var equals = -1
            while (fieldEnd < end && payload[fieldEnd].toInt() != ';'.code) {
                if (equals < 0 && payload[fieldEnd].toInt() == '='.code) equals = fieldEnd
                fieldEnd++
            }
            val keyEnd = if (equals < 0) fieldEnd else equals
            if (keyEnd > fieldStart) {
                val target = target(payload, fieldStart, keyEnd)
                var color = 0
                val operation =
                    when {
                        equals < 0 -> TerminalColorRequest.RESET
                        equals + 1 == fieldEnd -> TerminalColorRequest.DYNAMIC
                        equals + 2 == fieldEnd && payload[equals + 1].toInt() == '?'.code -> TerminalColorRequest.QUERY
                        target == TerminalColorRequest.UNKNOWN -> TerminalColorRequest.SET
                        else -> {
                            val parsed = KittyColorParser.parse(payload, equals + 1, fieldEnd)
                            if (parsed < 0) {
                                fieldStart = fieldEnd + 1
                                continue
                            }
                            // Ordinary OSC 21 roles have no alpha channel; opacity is a
                            // separate extension with its own named targets.
                            color = parsed.toInt() or 0xff000000.toInt()
                            TerminalColorRequest.SET
                        }
                    }
                request.add(target, operation, color, fieldStart, keyEnd)
            }
            fieldStart = fieldEnd + 1
        }
        if (request.count > 0) sink.applyColorRequest(request)
    }

    private fun target(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        if (payload[start].toInt() in '0'.code..'9'.code) {
            var value = 0
            for (i in start until end) {
                val digit = payload[i].toInt() - '0'.code
                if (digit !in 0..9 || value > (255 - digit) / 10) return TerminalColorRequest.UNKNOWN
                value = value * 10 + digit
            }
            return value
        }
        return when {
            matches(payload, start, end, "foreground") -> TerminalColorRequest.FOREGROUND
            matches(payload, start, end, "background") -> TerminalColorRequest.BACKGROUND
            matches(payload, start, end, "selection_foreground") -> TerminalColorRequest.SELECTION_FOREGROUND
            matches(payload, start, end, "selection_background") -> TerminalColorRequest.SELECTION_BACKGROUND
            matches(payload, start, end, "cursor") -> TerminalColorRequest.CURSOR
            matches(payload, start, end, "cursor_text") -> TerminalColorRequest.CURSOR_TEXT
            else -> TerminalColorRequest.UNKNOWN
        }
    }

    private fun matches(
        payload: ByteArray,
        start: Int,
        end: Int,
        name: String,
    ): Boolean {
        if (end - start != name.length) return false
        for (i in name.indices) if (payload[start + i].toInt() != name[i].code) return false
        return true
    }
}
