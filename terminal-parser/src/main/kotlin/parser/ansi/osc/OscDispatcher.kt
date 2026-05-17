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
            8 -> dispatchHyperlink(sink, payload, length, commandEnd + 1)
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
}
