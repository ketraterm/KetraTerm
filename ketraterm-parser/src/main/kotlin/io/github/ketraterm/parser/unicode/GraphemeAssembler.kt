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
package io.github.ketraterm.parser.unicode

import io.github.ketraterm.parser.runtime.ParserState
import io.github.ketraterm.parser.spi.TerminalAsciiCommandSink
import io.github.ketraterm.parser.spi.TerminalCommandSink

/**
 * Allocation-free grapheme assembly boundary.
 *
 * The assembler owns cluster buffering only. Unicode properties come from [UnicodeClass] and break decisions
 * from [GraphemeSegmenter], while terminal grid width remains owned by :terminal-core.
 * Only the bounded prefix is retained and published. Discarded continuations still advance
 * segmentation context so capacity exhaustion never introduces a grapheme boundary.
 */
internal class GraphemeAssembler(
    private val sink: TerminalCommandSink,
) {
    fun accept(
        state: ParserState,
        codepoint: Int,
    ) {
        val properties = UnicodeClass.properties(codepoint)
        val currentClass = UnicodeClass.graphemeBreakClass(properties)

        if (state.clusterLength > 0 && !GraphemeSegmenter.continuesCurrentCluster(state, currentClass, properties)) {
            flush(state)
        }

        if (state.clusterLength < state.clusterBuffer.size) {
            state.clusterBuffer[state.clusterLength++] = codepoint
        }
        GraphemeSegmenter.updateContext(state, properties, currentClass)
    }

    /** Accepts a non-empty, charset-mapped slice containing only `0x20..0x7e`. */
    fun acceptAsciiRun(
        state: ParserState,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(sink is TerminalAsciiCommandSink)
        // The first base can extend a preceding PREPEND, including an already published one.
        accept(state, bytes[offset].toInt())
        if (length == 1) return

        // Two adjacent ASCII bases always break. Keep the final base available for a later
        // combining mark, variation selector, or ZWJ, even when it arrives in another read.
        flush(state)
        if (length > 2) sink.writeAscii(bytes, offset + 1, length - 2)
        accept(state, bytes[offset + length - 1].toInt())
    }

    fun flush(state: ParserState) {
        flushForRender(state)
        state.clearActiveClusterAfterFlush()
    }

    fun flushForRender(state: ParserState) {
        if (state.clusterLength == 0 || state.clusterEmittedLength == state.clusterLength) return
        when {
            state.clusterEmittedLength > 0 -> sink.updatePreviousCluster(state.clusterBuffer, state.clusterLength)
            state.clusterLength == 1 -> sink.writeCodepoint(state.clusterBuffer[0])
            else ->
                sink.writeCluster(
                    codepoints = state.clusterBuffer,
                    length = state.clusterLength,
                )
        }
        state.clusterEmittedLength = state.clusterLength
    }

    fun reset(state: ParserState) {
        state.clearActiveClusterAfterFlush()
    }
}
