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

import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.protocol.TerminalGraphicsResult

/**
 * Serialized APC collection with one bounded upload spanning multiple strings.
 * [header] belongs to the current APC; [transfer] preserves first-chunk controls.
 * Base64 groups end at chunk boundaries, while decoded [bytes] accumulate until
 * final delivery. The sink borrows both controls and bytes before [cancel] reuses
 * or releases staging. Display resources and reply policy belong downstream.
 */
internal class GraphicsParser(
    private val sink: TerminalCommandSink,
) {
    private val headerBytes = ByteArray(TerminalGraphicsLimits.HEADER_BYTES)
    private val header = GraphicsHeader()
    private val transfer = GraphicsHeader()
    private var headerLength = 0
    private var firstByte = true
    private var graphics = false
    private var payload = false
    private var discarded = false
    private var active = false
    private var epoch = 0L
    private var bytes = EMPTY
    private var length = 0
    private var encoded = 0
    private var chunkLength = 0
    private var group = 0
    private var groupSize = 0
    private var padding = 0
    private var result = TerminalGraphicsResult.OK
    var pending: Boolean = false
        private set

    /** Starts one APC envelope, invalidating prior staging when the sink's token changed. */
    fun start() {
        val currentEpoch = sink.graphicsTransferGeneration
        if (active && epoch != currentEpoch) cancel()
        epoch = currentEpoch
        headerLength = 0
        firstByte = true
        graphics = false
        payload = false
        discarded = false
        chunkLength = 0
        group = 0
        groupSize = 0
        padding = 0
    }

    fun put(byte: Int) {
        if (firstByte) {
            firstByte = false
            graphics = byte == 'G'.code
            return
        }
        if (!graphics || discarded) return
        if (!payload) {
            if (byte == ';'.code) {
                beginPayload()
            } else if (headerLength < headerBytes.size && byte in 32..126) {
                headerBytes[headerLength++] = byte.toByte()
            } else {
                discard()
            }
            return
        }
        if (result != TerminalGraphicsResult.OK) return
        chunkLength++
        encoded++
        if (chunkLength > TerminalGraphicsLimits.CHUNK_BYTES || encoded > TerminalGraphicsLimits.ENCODED_BYTES) {
            result = TerminalGraphicsResult.TOO_LARGE
        }
        if (result != TerminalGraphicsResult.OK) return
        if (byte == '='.code) {
            if (groupSize !in 2..3 || ++padding > 4 - groupSize) result = TerminalGraphicsResult.INVALID
            return
        }
        val digit =
            when (byte) {
                in 'A'.code..'Z'.code -> byte - 'A'.code
                in 'a'.code..'z'.code -> byte - 'a'.code + 26
                in '0'.code..'9'.code -> byte - '0'.code + 52
                '+'.code -> 62
                '/'.code -> 63
                else -> -1
            }
        if (digit < 0 || padding != 0) {
            result = TerminalGraphicsResult.INVALID
            return
        }
        group = (group shl 6) or digit
        groupSize++
        if (groupSize == 4) {
            append(group ushr 16)
            append(group ushr 8)
            append(group)
            group = 0
            groupSize = 0
        }
    }

    private fun beginPayload() {
        payload = true
        if (!header.parse(headerBytes, headerLength)) {
            discard()
            return
        }
        val action = header.valueOr('a', 't'.code.toLong())
        if (action == 'd'.code.toLong()) cancel()
        if (active) {
            for (key in GraphicsHeader.KEYS) {
                val value = header.value(key)
                if (value != TerminalGraphicsCommand.OMITTED &&
                    key != 'm' &&
                    key != 'q' &&
                    !(key == 'a' && value == transfer.valueOr('a', 't'.code.toLong()))
                ) {
                    discard()
                    return
                }
            }
            if (header.value('q') != TerminalGraphicsCommand.OMITTED) transfer.set('q', header.value('q'))
        } else {
            transfer.copyFrom(header)
            length = 0
            encoded = 0
            result = TerminalGraphicsResult.OK
        }
        transfer.set('m', header.valueOr('m', 0))
        if (transfer.valueOr('m', 0) !in 0L..1L ||
            transfer.valueOr('q', 0) !in 0L..2L ||
            (transfer.valueOr('i', 0) != 0L && transfer.valueOr('I', 0) != 0L)
        ) {
            result = TerminalGraphicsResult.INVALID
        }
    }

    /** Completes a chunk at ST; only a final chunk or validation failure reaches the sink. */
    fun finish(yieldForDecode: Boolean = false) {
        if (!graphics || discarded) return
        // Core mutations can invalidate even a partial header between ingress reads.
        if (epoch != sink.graphicsTransferGeneration) {
            cancel()
            return
        }
        if (!payload) beginPayload()
        if (discarded) return
        val more = transfer.valueOr('m', 0) == 1L
        if (result == TerminalGraphicsResult.OK) {
            if ((more && (groupSize != 0 || padding != 0)) ||
                groupSize == 1 ||
                (padding != 0 && groupSize + padding != 4)
            ) {
                result = TerminalGraphicsResult.INVALID
            } else if (groupSize == 2) {
                append(group ushr 4)
            } else if (groupSize == 3) {
                append(group ushr 10)
                append(group ushr 2)
            }
        }
        if (more && result == TerminalGraphicsResult.OK) {
            val action = transfer.valueOr('a', 't'.code.toLong())
            if (action == 't'.code.toLong() || action == 'T'.code.toLong() || action == 'q'.code.toLong()) {
                active = true
                return
            }
            result = TerminalGraphicsResult.INVALID
        }
        val action = transfer.valueOr('a', 't'.code.toLong()).toInt().toChar()
        if (yieldForDecode &&
            result == TerminalGraphicsResult.OK &&
            action in "tTq" &&
            (action != 'q' || transfer.valueOr('i', 0) != 0L) &&
            (transfer.valueOr('f', 32) == 100L || transfer.valueOr('o', 0) == 'z'.code.toLong())
        ) {
            pending = true
            return
        }
        try {
            sink.applyGraphicsCommand(transfer, bytes, length, result)
        } finally {
            cancel()
        }
    }

    fun consumePending(consumer: (TerminalGraphicsCommand, ByteArray, Int) -> Unit) {
        check(pending) { "No graphics payload is pending" }
        try {
            consumer(transfer, bytes, length)
        } finally {
            cancel()
        }
    }

    /** Malformed graphics drains to ST without dispatching a truncated command. */
    fun discard() {
        if (graphics) cancel()
        discarded = true
    }

    /** Drops transfer state and large staging arrays; retains small scratch capacity for reuse. */
    fun cancel() {
        pending = false
        active = false
        length = 0
        encoded = 0
        result = TerminalGraphicsResult.OK
        if (bytes.size > TerminalGraphicsLimits.CHUNK_BYTES) bytes = EMPTY
    }

    private fun append(value: Int) {
        if (result != TerminalGraphicsResult.OK) return
        if (length == TerminalGraphicsLimits.TRANSFER_BYTES) {
            result = TerminalGraphicsResult.TOO_LARGE
            return
        }
        if (length == bytes.size) {
            bytes = bytes.copyOf((bytes.size.coerceAtLeast(4096) * 2).coerceAtMost(TerminalGraphicsLimits.TRANSFER_BYTES))
        }
        bytes[length++] = value.toByte()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
