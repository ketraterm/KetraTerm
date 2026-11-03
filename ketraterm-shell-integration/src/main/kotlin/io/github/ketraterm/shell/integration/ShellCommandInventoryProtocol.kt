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

import io.github.ketraterm.session.*
import java.nio.ByteBuffer
import java.util.*

/** Independent shell-local and PATH snapshots, merged at publication. Calls are session-serialized. */
internal class ShellCommandInventoryProtocol(
    private val channel: TerminalShellCompletionChannel,
    nanoTime: () -> Long = System::nanoTime,
) {
    private var local: TerminalShellCommandInventory? = null
    private var path: TerminalShellCommandInventory? = null
    private val localSource =
        Source(nanoTime) {
            local = it
            publish()
        }
    private val pathSource =
        Source(nanoTime) {
            path = it
            publish()
        }

    fun acceptsPathEpoch(epoch: String): Boolean = pathSource.matchesEpoch(epoch)

    fun observe(fields: List<String>) {
        when (fields.firstOrNull()) {
            "KetraTermShellCommands" -> localSource.observe(fields)
            "KetraTermCommands" -> pathSource.observe(fields)
        }
    }

    fun expire() {
        localSource.expire()
        pathSource.expire()
    }

    fun clear() {
        localSource.clear()
        pathSource.clear()
        local = null
        path = null
    }

    private fun publish() {
        val locals = local
        val paths = path
        if (locals?.status != TerminalShellCommandInventoryStatus.READY) {
            channel.publishCommands(paths ?: locals ?: return)
            return
        }
        if (paths?.status != TerminalShellCommandInventoryStatus.READY) {
            channel.publishCommands(locals)
            return
        }
        // Shell-local resolution wins over an executable with the same name.
        val merged = LinkedHashMap<String, TerminalShellCompletionCandidate>()
        paths.commands.forEach { merged[it.displayText] = it }
        locals.commands.forEach { merged[it.displayText] = it }
        val inventory =
            try {
                TerminalShellCommandInventory(merged.values.toList())
            } catch (_: IllegalArgumentException) {
                // Never silently truncate a combined snapshot that exceeds the budget.
                TerminalShellCommandInventory(emptyList(), TerminalShellCommandInventoryStatus.UNAVAILABLE)
            }
        channel.publishCommands(inventory)
    }

    private class Source(
        private val nanoTime: () -> Long,
        private val publish: (TerminalShellCommandInventory) -> Unit,
    ) {
        private var epoch: String? = null
        private var lastScan = 0L
        private var hasCompleteInventory = false
        private var assembly: Assembly? = null

        fun matchesEpoch(value: String): Boolean = epoch == value

        fun observe(fields: List<String>) {
            if (fields.size < 4 || fields[1] != "3") return
            val incomingEpoch = fields[3]
            if (incomingEpoch.length != 32 || incomingEpoch.any { it !in '0'..'9' && it !in 'a'..'f' }) return
            expire()
            if (fields.size == 4 && fields[2] == "Ready") {
                if (epoch != incomingEpoch) {
                    epoch = incomingEpoch
                    lastScan = 0
                    hasCompleteInventory = false
                    assembly = null
                    publishStatus(TerminalShellCommandInventoryStatus.PENDING)
                }
                return
            }
            if (incomingEpoch != epoch || fields.size < 5) return
            val scan = fields[4].toLongOrNull()?.takeIf { it > 0 } ?: return
            if (fields[2] == "Begin" && fields.size == 5) {
                if (scan <= lastScan) return
                lastScan = scan
                assembly = Assembly(scan, nanoTime())
                if (!hasCompleteInventory) publishStatus(TerminalShellCommandInventoryStatus.PENDING)
                return
            }
            val current = assembly?.takeIf { it.scan == scan } ?: return
            try {
                when (fields[2]) {
                    "Chunk" -> {
                        require(fields.size == 7 && fields[5].toIntOrNull() == current.chunks)
                        current.append(fields[6])
                    }
                    "End" -> {
                        require(fields.size == 8)
                        require(fields[5].toIntOrNull() == current.chunks)
                        require(fields[6].toIntOrNull() == current.commands.size)
                        require(fields[7].toIntOrNull() == current.bytes)
                        val inventory = TerminalShellCommandInventory(current.commands)
                        assembly = null
                        hasCompleteInventory = true
                        publish(inventory)
                    }
                    "Fail" -> fail()
                    else -> fail()
                }
            } catch (_: IllegalArgumentException) {
                fail()
            } catch (_: CharacterCodingException) {
                fail()
            }
        }

        /** Activity-driven expiry; idle assemblies are bounded and are also released on session closure. */
        fun expire() {
            val current = assembly ?: return
            if (nanoTime() - current.startedAt >= MAX_AGE_NANOS) fail()
        }

        fun clear() {
            assembly = null
            epoch = null
            hasCompleteInventory = false
        }

        private fun fail() {
            assembly = null
            if (!hasCompleteInventory) publishStatus(TerminalShellCommandInventoryStatus.UNAVAILABLE)
        }

        private fun publishStatus(status: TerminalShellCommandInventoryStatus) {
            publish(TerminalShellCommandInventory(emptyList(), status))
        }
    }

    private class Assembly(
        val scan: Long,
        val startedAt: Long,
    ) {
        val commands = ArrayList<TerminalShellCompletionCandidate>()
        var chunks = 0
        var bytes = 0
        private var textLength = 0

        fun append(encoded: String) {
            require(encoded.isNotEmpty() && encoded.length <= MAX_CHUNK_BYTES / 3 * 4)
            require(chunks < MAX_CHUNKS)
            val decoded = Base64.getDecoder().decode(encoded)
            require(decoded.isNotEmpty() && decoded.size <= MAX_CHUNK_BYTES)
            require(bytes <= MAX_BYTES - decoded.size)
            val body =
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(decoded))
                    .toString()
            var cursor = 0

            fun field(maxLength: Int): String {
                val end = body.indexOf('\u0000', cursor)
                require(end >= cursor && end - cursor <= maxLength)
                return body.substring(cursor, end).also { cursor = end + 1 }
            }
            while (cursor < body.length) {
                require(commands.size < TerminalShellCommandInventory.MAX_COMMANDS)
                val name = field(512)
                val insertion = field(4096).let { if (it.isEmpty() || it == name) name else it }
                require(cursor + 1 < body.length && body[cursor + 1] == '\u0000')
                val kind = body[cursor]
                require(kind == 'C' || kind == 'A')
                cursor += 2
                val expansion = field(4096)
                require(kind == 'A' || expansion.isEmpty())
                require(name.isNotEmpty() && name.none(Char::isISOControl) && insertion.none(Char::isISOControl))
                textLength += name.length + (if (insertion == name) 0 else insertion.length) + expansion.length
                require(textLength <= TerminalShellCommandInventory.MAX_TEXT_LENGTH)
                commands +=
                    TerminalShellCompletionCandidate(
                        insertion,
                        name,
                        "",
                        TerminalShellCompletionKind.COMMAND,
                        expansion.takeIf { kind == 'A' },
                    )
            }
            bytes += decoded.size
            chunks++
        }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 49_152
        const val MAX_BYTES = 4_194_304
        const val MAX_CHUNKS = 256
        const val MAX_AGE_NANOS = 60_000_000_000L
    }
}
