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
package io.github.ketraterm.core.buffer.impl

import io.github.ketraterm.core.model.HostResponseQueue
import io.github.ketraterm.protocol.TerminalCapabilityIdentity

/** Owns the XTGETTCAP allowlist and bounded, per-capability reply encoding. */
internal object XtgettcapResponder {
    // Independent of parser collection: direct core callers also cannot build an unbounded reply.
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val FRAME_BYTES = 7 // ESC P, status, +r, ESC \.
    private const val HEX = "0123456789abcdef"

    private enum class Kind { BOOLEAN, NUMBER, STRING }

    private class Capability(
        val names: Array<String>,
        val kind: Kind,
        val value: String = "",
        val available: Boolean = true,
    )

    // Values are decoded ASCII bytes, including real ESC bytes. Parameter expressions
    // belong to the application; the terminal never evaluates a terminfo program.
    private val capabilities =
        arrayOf(
            Capability(arrayOf("TN", "name"), Kind.STRING, TerminalCapabilityIdentity.TERM_NAME),
            Capability(arrayOf("Co", "colors"), Kind.NUMBER, TerminalCapabilityIdentity.TERMINFO_COLOR_COUNT),
            Capability(arrayOf("RGB", "Tc"), Kind.BOOLEAN, available = TerminalCapabilityIdentity.TERMINFO_TRUECOLOR_SUPPORTED),
            Capability(arrayOf("indn"), Kind.STRING, "\u001B[%p1%dS"),
            Capability(arrayOf("PS"), Kind.STRING, "\u001B[200~"),
            Capability(arrayOf("PE"), Kind.STRING, "\u001B[201~"),
            Capability(arrayOf("Su"), Kind.BOOLEAN),
            Capability(arrayOf("Smulx"), Kind.STRING, "\u001B[4:%p1%dm"),
            Capability(
                arrayOf("Setulc"),
                Kind.STRING,
                "\u001B[58:2::%p1%{65536}%/%d:%p1%{256}%/%{255}%&%d:%p1%{255}%&%dm",
            ),
        )

    /**
     * Appends one frame per well-formed name, preserving order, duplicates and hex spelling.
     * Malformed batches or responses exceeding 64 KiB append one empty failure instead.
     * Validation precedes emission so a rejected batch cannot leave partial success frames.
     */
    fun respond(
        raw: String,
        queue: HostResponseQueue,
    ) {
        if (!validate(raw)) {
            append(queue, "\u001BP0+r\u001B\\")
            return
        }
        var start = 0
        while (start < raw.length) {
            val end = tokenEnd(raw, start)
            val capability = lookup(raw, start, end)
            append(queue, if (capability == null) "\u001BP0+r" else "\u001BP1+r")
            for (index in start until end) queue.enqueueByte(raw[index].code)
            if (capability != null && capability.kind != Kind.BOOLEAN) {
                queue.enqueueByte('='.code)
                for (char in capability.value) {
                    queue.enqueueByte(HEX[char.code ushr 4].code)
                    queue.enqueueByte(HEX[char.code and 15].code)
                }
            }
            append(queue, "\u001B\\")
            start = end + 1
        }
    }

    private fun validate(raw: String): Boolean {
        // Even unknown-name replies echo the validated hex key, so a longer input
        // cannot fit the reply budget. Check before scanning caller-owned strings.
        if (raw.isEmpty() || raw.length > MAX_RESPONSE_BYTES) return false
        var start = 0
        var responseBytes = 0
        while (start <= raw.length) {
            val end = tokenEnd(raw, start)
            val length = end - start
            if (length == 0 || length % 2 != 0) return false
            for (index in start until end) if (hexDigit(raw[index]) < 0) return false
            val capability = lookup(raw, start, end)
            val valueBytes = if (capability == null || capability.kind == Kind.BOOLEAN) 0 else 1 + 2 * capability.value.length
            val frameBytes = FRAME_BYTES + length + valueBytes
            if (frameBytes > MAX_RESPONSE_BYTES - responseBytes) return false
            responseBytes += frameBytes
            start = end + 1
        }
        return true
    }

    private fun tokenEnd(
        raw: String,
        start: Int,
    ): Int = raw.indexOf(';', start).let { if (it < 0) raw.length else it }

    private fun lookup(
        raw: String,
        start: Int,
        end: Int,
    ): Capability? {
        for (capability in capabilities) {
            if (!capability.available) continue
            for (name in capability.names) {
                if (end - start != name.length * 2) continue
                var index = 0
                while (index < name.length) {
                    val offset = start + index * 2
                    val decoded = (hexDigit(raw[offset]) shl 4) or hexDigit(raw[offset + 1])
                    if (decoded != name[index].code) break
                    index++
                }
                if (index == name.length) return capability
            }
        }
        return null
    }

    private fun hexDigit(char: Char): Int =
        when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }

    private fun append(
        queue: HostResponseQueue,
        text: String,
    ) {
        for (char in text) queue.enqueueByte(char.code)
    }
}
