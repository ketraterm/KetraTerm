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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.TerminalShellCompletionCandidate
import io.github.ketraterm.session.TerminalShellCompletionChannel
import io.github.ketraterm.session.TerminalShellCompletionKind
import io.github.ketraterm.session.TerminalShellCompletionResult
import java.nio.ByteBuffer
import java.util.*

/** Bounded private OSC 633 completion messages and shell command inventories. */
internal class NativeCompletionProtocol(
    private val channel: TerminalShellCompletionChannel,
) {
    private val inventory = ShellCommandInventoryProtocol(channel)

    fun acceptsPathEpoch(epoch: String): Boolean = inventory.acceptsPathEpoch(epoch)

    fun clear() = inventory.clear()

    fun outputProcessed() = inventory.expire()

    fun observe(
        command: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (command != 633 || length > MAX_PAYLOAD_BYTES) return
        for (index in offset until offset + length) if (payload[index].toInt() !in 32..126) return
        val fields = String(payload, offset, length, Charsets.US_ASCII).split(';')
        if (fields.firstOrNull() == "KetraTermCommands" || fields.firstOrNull() == "KetraTermShellCommands") {
            inventory.observe(fields)
            return
        }
        if (fields.size < 4 || fields[0] != "KetraTermCompletion" || fields[1] != "1") return
        val epoch = fields[3]
        if (epoch.length != 32 || epoch.any { it !in '0'..'9' && it !in 'a'..'f' }) return
        if (fields[2] == "Ready" && fields.size == 4) {
            channel.ready(epoch, listOf(TerminalKeyEvent(key = TerminalKey.F12), TerminalKeyEvent(codepoint = 'e'.code)))
            return
        }
        if (fields[2] != "Result" || fields.size < 6) return
        val id = fields[4].toLongOrNull()?.takeIf { it > 0L } ?: return
        val result =
            try {
                decode(fields)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: CharacterCodingException) {
                null
            }
        channel.respond(epoch, id, result)
    }

    private fun decode(fields: List<String>): TerminalShellCompletionResult? {
        if (fields[5] != "Ok") return null
        require(fields.size >= 11)
        val line = text(fields[9], 4096)
        val cursor = fields[6].toIntOrNull() ?: return null
        val start = fields[7].toIntOrNull() ?: return null
        val end = fields[8].toIntOrNull() ?: return null
        require(start in 0..cursor && cursor <= end && end <= line.length)
        require(line.boundary(start) && line.boundary(end) && line.boundary(cursor))
        require(line.none { it.isISOControl() })
        val count = fields[10].toIntOrNull() ?: return null
        require(count in 0..128 && fields.size == 11 + count * 4)
        val candidates = ArrayList<TerminalShellCompletionCandidate>(count)
        for (index in 0 until count) {
            val base = 11 + index * 4
            val kind =
                when (fields[base]) {
                    "Command" -> TerminalShellCompletionKind.COMMAND
                    "ParameterName" -> TerminalShellCompletionKind.OPTION
                    "ProviderItem" -> TerminalShellCompletionKind.FILE
                    "ProviderContainer" -> TerminalShellCompletionKind.DIRECTORY
                    else -> TerminalShellCompletionKind.ARGUMENT
                }
            val replacement = text(fields[base + 1], 4096)
            require(replacement.none { it.isISOControl() })
            candidates += TerminalShellCompletionCandidate(replacement, text(fields[base + 2], 512), text(fields[base + 3], 1024), kind)
        }
        return TerminalShellCompletionResult(line, cursor, start, end, candidates)
    }

    private fun text(
        encoded: String,
        maxCharacters: Int,
    ): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= maxCharacters * 4)
        val value =
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        require(value.length <= maxCharacters)
        return value
    }

    private fun String.boundary(offset: Int): Boolean =
        !(offset in 1..<length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())

    companion object {
        const val MAX_PAYLOAD_BYTES = 262_144
    }
}
