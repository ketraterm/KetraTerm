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
package io.github.ketraterm.testkit

/**
 * Named parser-chunking variant for one logical host byte stream.
 *
 * @property name stable diagnostic name describing the partition.
 * @property transcript replay transcript containing that exact partition.
 */
data class TerminalReplayChunking(
    val name: String,
    val transcript: TerminalReplayTranscript,
)

/** Generates deterministic parser chunk partitions for conformance tests. */
object TerminalReplayChunkings {
    /**
     * Generates the standard exhaustive partition set for a bounded test stream.
     *
     * The result contains single-chunk delivery, every possible two-way split,
     * bytewise delivery, and fixed hostile chunk sizes 2, 3, and 7 when those
     * partitions add a distinct multi-chunk shape. [TerminalReplayEvent.EndOfInput]
     * is appended to every variant when [endOfInput] is true.
     *
     * This method intentionally performs O(n^2) byte copying across all split
     * variants and is meant for bounded conformance fixtures, not large recorded
     * TUI streams.
     *
     * @param bytes logical host-output byte stream.
     * @param endOfInput whether every transcript explicitly flushes parser state.
     * @return ordered named chunking variants.
     */
    @JvmStatic
    @JvmOverloads
    fun exhaustive(
        bytes: ByteArray,
        endOfInput: Boolean = true,
    ): List<TerminalReplayChunking> {
        val variants = ArrayList<TerminalReplayChunking>(bytes.size + 5)
        variants += variant("single", listOf(bytes.copyOf()), endOfInput)

        for (split in 1 until bytes.size) {
            variants +=
                variant(
                    name = "split@$split",
                    chunks =
                        listOf(
                            bytes.copyOfRange(0, split),
                            bytes.copyOfRange(split, bytes.size),
                        ),
                    endOfInput = endOfInput,
                )
        }

        if (bytes.size > 2) {
            variants += variant("bytewise", partition(bytes, 1), endOfInput)
        }
        addFixedVariant(variants, bytes, chunkSize = 2, endOfInput)
        addFixedVariant(variants, bytes, chunkSize = 3, endOfInput)
        addFixedVariant(variants, bytes, chunkSize = 7, endOfInput)
        return variants
    }

    private fun addFixedVariant(
        variants: MutableList<TerminalReplayChunking>,
        bytes: ByteArray,
        chunkSize: Int,
        endOfInput: Boolean,
    ) {
        val chunks = partition(bytes, chunkSize)
        if (chunks.size <= 2 || chunks.all { it.size == 1 }) {
            return
        }
        variants += variant("fixed-$chunkSize", chunks, endOfInput)
    }

    private fun partition(
        bytes: ByteArray,
        chunkSize: Int,
    ): List<ByteArray> {
        if (bytes.isEmpty()) {
            return listOf(ByteArray(0))
        }
        val chunks = ArrayList<ByteArray>((bytes.size + chunkSize - 1) / chunkSize)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            chunks += bytes.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    private fun variant(
        name: String,
        chunks: List<ByteArray>,
        endOfInput: Boolean,
    ): TerminalReplayChunking {
        val events = ArrayList<TerminalReplayEvent>(chunks.size + if (endOfInput) 1 else 0)
        for (chunk in chunks) {
            events += TerminalReplayEvent.Input(chunk)
        }
        if (endOfInput) {
            events += TerminalReplayEvent.EndOfInput
        }
        return TerminalReplayChunking(name, TerminalReplayTranscript(events))
    }
}
