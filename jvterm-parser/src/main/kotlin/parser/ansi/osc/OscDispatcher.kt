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
package com.gagik.parser.ansi.osc

import com.gagik.parser.spi.TerminalCommandSink

internal object OscDispatcher {
    fun dispatch(
        sink: TerminalCommandSink,
        payload: ByteArray,
        length: Int,
        overflowed: Boolean,
    ) {
        if (overflowed || length <= 0) {
            return
        }

        val commandEnd = findByte(payload, length, startIndex = 0, byteValue = ';'.code)
        if (commandEnd <= 0) {
            return
        }

        val command = parseDecimal(payload, commandEnd) ?: return
        when (command) {
            0 -> sink.setIconAndWindowTitle(decodePayload(payload, commandEnd + 1, length))
            1 -> sink.setIconTitle(decodePayload(payload, commandEnd + 1, length))
            2 -> sink.setWindowTitle(decodePayload(payload, commandEnd + 1, length))
            4 -> {
                val payloadStr = decodePayload(payload, commandEnd + 1, length)
                val parts = payloadStr.split(';')
                var i = 0
                while (i + 1 < parts.size) {
                    val indexStr = parts[i].trim()
                    val index = indexStr.toIntOrNull()
                    if (index != null && index in 0..255) {
                        val colorSpec = parts[i + 1].trim()
                        if (colorSpec == "?") {
                            sink.queryPaletteColor(index)
                        } else {
                            val color = parseColor(colorSpec)
                            if (color != null) {
                                sink.setPaletteColor(index, color)
                            }
                        }
                    }
                    i += 2
                }
            }
            8 -> dispatchHyperlink(sink, payload, length, commandEnd + 1)
            10, 11, 12 -> {
                val payloadStr = decodePayload(payload, commandEnd + 1, length)
                val parts = payloadStr.split(';')
                var target = command
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed == "?") {
                        sink.queryDynamicColor(target)
                    } else {
                        val color = parseColor(trimmed)
                        if (color != null) {
                            sink.setDynamicColor(target, color)
                        }
                    }
                    target++
                }
            }
        }
    }

    private fun dispatchHyperlink(
        sink: TerminalCommandSink,
        payload: ByteArray,
        length: Int,
        paramsStart: Int,
    ) {
        val uriStart = findByte(payload, length, startIndex = paramsStart, byteValue = ';'.code)
        if (uriStart < 0) {
            return
        }

        val params = decodePayload(payload, paramsStart, uriStart)
        val uri = decodePayload(payload, uriStart + 1, length)
        if (uri.isEmpty()) {
            sink.endHyperlink()
            return
        }

        sink.startHyperlink(
            uri = uri,
            id = findHyperlinkId(params),
        )
    }

    private fun parseDecimal(
        payload: ByteArray,
        endExclusive: Int,
    ): Int? {
        var value = 0
        var i = 0
        while (i < endExclusive) {
            val digit = (payload[i].toInt() and 0xff) - '0'.code
            if (digit !in 0..9) {
                return null
            }
            if (value > (Int.MAX_VALUE - digit) / 10) {
                return null
            }
            value = value * 10 + digit
            i++
        }
        return value
    }

    private fun findHyperlinkId(params: String): String? {
        if (params.isEmpty()) {
            return null
        }

        val parts = params.split(':')
        for (part in parts) {
            if (part.startsWith("id=")) {
                return part.substring("id=".length)
            }
        }

        return null
    }

    private fun findByte(
        payload: ByteArray,
        length: Int,
        startIndex: Int,
        byteValue: Int,
    ): Int {
        var i = startIndex
        while (i < length) {
            if ((payload[i].toInt() and 0xff) == byteValue) {
                return i
            }
            i++
        }
        return -1
    }

    private fun decodePayload(
        payload: ByteArray,
        startInclusive: Int,
        endExclusive: Int,
    ): String = payload.decodeToString(startIndex = startInclusive, endIndex = endExclusive)

    private fun parseColor(spec: String): Int? {
        val trimmed = spec.trim()
        return parseHexColor(trimmed) ?: parseRgbColon(trimmed)
    }

    private fun parseHexColor(spec: String): Int? {
        if (!spec.startsWith("#")) return null
        val len = spec.length - 1
        return when (len) {
            3 -> {
                val r = parseHexDigit(spec[1])
                if (r < 0) return null
                val g = parseHexDigit(spec[2])
                if (g < 0) return null
                val b = parseHexDigit(spec[3])
                if (b < 0) return null
                0xFF000000.toInt() or (((r shl 4) or r) shl 16) or (((g shl 4) or g) shl 8) or ((b shl 4) or b)
            }
            6 -> {
                val r = parseHex(spec, 1, 3)
                if (r < 0) return null
                val g = parseHex(spec, 3, 5)
                if (g < 0) return null
                val b = parseHex(spec, 5, 7)
                if (b < 0) return null
                0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            9 -> {
                val r = parseHex(spec, 1, 4)
                if (r < 0) return null
                val g = parseHex(spec, 4, 7)
                if (g < 0) return null
                val b = parseHex(spec, 7, 10)
                if (b < 0) return null
                0xFF000000.toInt() or ((r ushr 4) shl 16) or ((g ushr 4) shl 8) or (b ushr 4)
            }
            12 -> {
                val r = parseHex(spec, 1, 5)
                if (r < 0) return null
                val g = parseHex(spec, 5, 9)
                if (g < 0) return null
                val b = parseHex(spec, 9, 13)
                if (b < 0) return null
                0xFF000000.toInt() or ((r ushr 8) shl 16) or ((g ushr 8) shl 8) or (b ushr 8)
            }
            else -> null
        }
    }

    private fun parseRgbColon(spec: String): Int? {
        if (!spec.startsWith("rgb:")) return null
        val content = spec.substring(4)
        val parts = content.split('/')
        if (parts.size != 3) return null

        val r = parseChannel(parts[0]) ?: return null
        val g = parseChannel(parts[1]) ?: return null
        val b = parseChannel(parts[2]) ?: return null

        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun parseChannel(s: String): Int? {
        if (s.isEmpty() || s.length > 4) return null
        val hexVal = parseHex(s)
        if (hexVal < 0) return null
        return when (s.length) {
            1 -> (hexVal shl 4) or hexVal
            2 -> hexVal
            3 -> hexVal ushr 4
            4 -> hexVal ushr 8
            else -> null
        }
    }

    private fun parseHexDigit(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

    private fun parseHex(
        s: String,
        start: Int,
        endExclusive: Int,
    ): Int {
        var value = 0
        for (i in start until endExclusive) {
            val d = parseHexDigit(s[i])
            if (d < 0) return -1
            value = (value shl 4) or d
        }
        return value
    }

    private fun parseHex(s: String): Int = parseHex(s, 0, s.length)
}
