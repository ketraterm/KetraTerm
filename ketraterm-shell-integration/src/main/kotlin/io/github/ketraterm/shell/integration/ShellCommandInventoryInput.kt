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

import io.github.ketraterm.session.TerminalShellIntegrationContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, independent input for a shell's completed PATH inventory snapshots.
 * Bind once through [OscShellIntegration.configured]. The host owns transport,
 * polling and cleanup; this input owns no I/O, jobs or terminal byte stream.
 * Only inventory frames for the environment announced by the live shell are accepted.
 */
public class ShellCommandInventoryInput : AutoCloseable {
    private val bound = AtomicBoolean()
    private val closed = AtomicBoolean()

    @Volatile private var binding: Binding? = null

    internal fun bind(
        context: TerminalShellIntegrationContext,
        protocol: NativeCompletionProtocol,
    ) {
        check(bound.compareAndSet(false, true)) { "Inventory input already bound to a session" }
        check(!closed.get()) { "Inventory input is closed" }
        binding = Binding(context, protocol)
        if (closed.get()) binding = null
    }

    /**
     * Borrows a complete snapshot, without retaining [bytes]. Returns false only
     * when its live-shell environment announcement has not arrived yet; the host
     * may retry until a newer snapshot replaces it. Malformed/closed input is
     * consumed without publication. Calls must be serialized by the host.
     */
    public fun accept(bytes: ByteArray): Boolean {
        if (closed.get()) return true
        if (bytes.size > MAX_SNAPSHOT_BYTES || bytes.isEmpty()) return true
        if (bytes.size < PREFIX.size || PREFIX.indices.any { bytes[it] != PREFIX[it] }) return true
        val firstEnd = endOfFrame(bytes, 0)
        if (firstEnd < 0 || firstEnd > NativeCompletionProtocol.MAX_PAYLOAD_BYTES) return true
        val first = String(bytes, PREFIX.size, firstEnd - PREFIX.size, Charsets.US_ASCII).split(';')
        if (first.size != 3 || first[0] != "Begin") return true
        val epoch = first[1]
        val current = binding ?: return false
        // An early file may wait for several polls. Inspect only its short header
        // until Ready arrives, rather than repeatedly scanning megabytes of data.
        if (!current.context.withTerminalState { current.protocol.acceptsPathEpoch(epoch) }) return false
        // Validate the whole envelope before admitting any record. This channel
        // cannot inject text, prompt markers, completion replies or other OSCs.
        var offset = 0
        var frames = 0
        var lastOperation = ""
        while (offset < bytes.size) {
            if (bytes.size - offset < PREFIX.size || PREFIX.indices.any { bytes[offset + it] != PREFIX[it] }) return true
            val end = endOfFrame(bytes, offset)
            if (end < 0 || end - offset > NativeCompletionProtocol.MAX_PAYLOAD_BYTES) return true
            val operationStart = offset + PREFIX.size
            var operationEnd = operationStart
            while (operationEnd < end && bytes[operationEnd] != ';'.code.toByte()) operationEnd++
            lastOperation = String(bytes, operationStart, operationEnd - operationStart, Charsets.US_ASCII)
            if (lastOperation !in OPERATIONS || ++frames > ShellCommandInventoryProtocol.MAX_CHUNKS + 2) return true
            offset = end + 1
        }
        if (lastOperation != "End" && lastOperation != "Fail") return true
        offset = 0
        while (offset < bytes.size) {
            val end = endOfFrame(bytes, offset)
            val accepted =
                current.context.withTerminalState {
                    if (binding !== current || closed.get()) return@withTerminalState true
                    if (!current.protocol.acceptsPathEpoch(epoch)) return@withTerminalState false
                    current.protocol.observe(633, bytes, offset + OSC_PREFIX_BYTES, end - offset - OSC_PREFIX_BYTES, promptActive = false)
                    true
                }
            if (!accepted) return false
            offset = end + 1
        }
        return true
    }

    /** Detaches the session; subsequent input is discarded. Idempotent. */
    override fun close() {
        closed.set(true)
        binding = null
    }

    private fun endOfFrame(
        bytes: ByteArray,
        start: Int,
    ): Int {
        for (index in start + PREFIX.size until bytes.size) {
            val value = bytes[index].toInt()
            if (value == 7) return index
            if (value !in 32..126) return -1
        }
        return -1
    }

    private class Binding(
        val context: TerminalShellIntegrationContext,
        val protocol: NativeCompletionProtocol,
    )

    public companion object {
        /** Maximum complete encoded snapshot, including framing; decoded budgets remain stricter. */
        public const val MAX_SNAPSHOT_BYTES: Int = 8 * 1024 * 1024
        private const val OSC_PREFIX_BYTES = 6
        private val PREFIX = "\u001b]633;KetraTermCommands;3;".toByteArray(Charsets.US_ASCII)
        private val OPERATIONS = setOf("Begin", "Chunk", "End", "Fail")
    }
}
