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

import io.github.ketraterm.parser.api.TerminalOutputParser

/**
 * One deterministic operation in a headless terminal replay.
 *
 * Input events preserve transport chunk boundaries: every [Input] is delivered
 * to the parser by one `accept` call. Resize and end-of-input events remain in
 * the same ordered stream, allowing transcripts to reproduce timing-sensitive
 * parser, reflow, and flush behavior without a PTY.
 */
sealed interface TerminalReplayEvent {
    /**
     * One immutable host-output byte chunk.
     *
     * The source array is copied on construction so later fixture mutation
     * cannot change a recorded transcript.
     *
     * @param bytes exact bytes supplied by the terminal host.
     */
    class Input(
        bytes: ByteArray,
    ) : TerminalReplayEvent {
        private val content = bytes.copyOf()

        /** Number of bytes in this transport chunk. */
        val size: Int
            get() = content.size

        /**
         * Returns a detached copy of this input chunk.
         *
         * @return exact recorded bytes.
         */
        fun copyBytes(): ByteArray = content.copyOf()

        internal fun replayWith(parser: TerminalOutputParser) {
            parser.accept(content)
        }

        override fun equals(other: Any?): Boolean = other is Input && content.contentEquals(other.content)

        override fun hashCode(): Int = content.contentHashCode()

        override fun toString(): String = "Input(${TerminalByteSequence.of(content).toHexString()})"

        companion object {
            /**
             * Encodes [text] as UTF-8 for one parser input chunk.
             *
             * @param text text or control sequence to encode.
             * @return immutable input event.
             */
            @JvmStatic
            fun utf8(text: String): Input = Input(text.encodeToByteArray())
        }
    }

    /**
     * Resizes the headless terminal between input chunks.
     *
     * @property columns new grid width in cells.
     * @property rows new grid height in rows.
     */
    data class Resize(
        val columns: Int,
        val rows: Int,
    ) : TerminalReplayEvent {
        init {
            require(columns > 0) { "columns must be > 0, was $columns" }
            require(rows > 0) { "rows must be > 0, was $rows" }
        }
    }

    /**
     * Flushes parser state exactly where the recorded host stream ended.
     */
    data object EndOfInput : TerminalReplayEvent
}

/**
 * Immutable ordered terminal replay artifact.
 *
 * @property events parser input chunks, resizes, and stream-finalization events
 * in the exact order in which they must be applied.
 */
class TerminalReplayTranscript(
    events: List<TerminalReplayEvent>,
) {
    /** Ordered replay events detached from the caller's source list. */
    val events: List<TerminalReplayEvent> = events.toList()

    companion object {
        /**
         * Creates a transcript from explicitly ordered events.
         *
         * @param events ordered replay events.
         * @return immutable transcript.
         */
        @JvmStatic
        fun of(vararg events: TerminalReplayEvent): TerminalReplayTranscript = TerminalReplayTranscript(events.asList())

        /**
         * Splits one logical byte stream into explicitly sized parser chunks.
         *
         * Chunk sizes must be positive and must consume the complete stream.
         * No end-of-input event is appended implicitly because flush placement
         * is part of the behavior under test.
         *
         * @param bytes logical host-output byte stream.
         * @param chunkSizes ordered positive chunk sizes summing to [bytes]'s size.
         * @return transcript containing one input event per requested chunk.
         */
        @JvmStatic
        fun chunked(
            bytes: ByteArray,
            vararg chunkSizes: Int,
        ): TerminalReplayTranscript {
            if (bytes.isEmpty()) {
                require(chunkSizes.isEmpty()) { "empty input cannot have non-empty chunk sizes" }
                return TerminalReplayTranscript(emptyList())
            }

            require(chunkSizes.isNotEmpty()) { "non-empty input requires at least one chunk size" }
            var consumed = 0
            val events = ArrayList<TerminalReplayEvent>(chunkSizes.size)
            for (size in chunkSizes) {
                require(size > 0) { "chunk sizes must be > 0, was $size" }
                require(consumed <= bytes.size - size) {
                    "chunk sizes exceed input length ${bytes.size} at offset $consumed"
                }
                events += TerminalReplayEvent.Input(bytes.copyOfRange(consumed, consumed + size))
                consumed += size
            }
            require(consumed == bytes.size) {
                "chunk sizes consumed $consumed bytes, expected ${bytes.size}"
            }
            return TerminalReplayTranscript(events)
        }

        /**
         * Creates the most fragmented replay: one parser call per input byte.
         *
         * @param bytes logical host-output byte stream.
         * @return transcript preserving every possible adjacent byte boundary.
         */
        @JvmStatic
        fun bytewise(bytes: ByteArray): TerminalReplayTranscript {
            val events = ArrayList<TerminalReplayEvent>(bytes.size)
            for (byte in bytes) {
                events += TerminalReplayEvent.Input(byteArrayOf(byte))
            }
            return TerminalReplayTranscript(events)
        }
    }
}
