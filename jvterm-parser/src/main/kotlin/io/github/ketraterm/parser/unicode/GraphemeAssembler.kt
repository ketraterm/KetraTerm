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
import io.github.ketraterm.parser.spi.TerminalCommandSink

/**
 * Allocation-free grapheme assembly boundary.
 *
 * The assembler owns cluster buffering only. Unicode classification and break decisions are
 * delegated to [GraphemeSegmenter], while terminal grid width remains owned by :terminal-core.
 */
internal class GraphemeAssembler(
    private val sink: TerminalCommandSink,
) {
    fun accept(
        state: ParserState,
        codepoint: Int,
    ) {
        val currentClass = UnicodeClass.graphemeBreakClass(codepoint)

        if (state.clusterLength == 0) {
            appendToClusterOrFlush(state, codepoint)
            GraphemeSegmenter.updateContext(state, codepoint, currentClass)
            return
        }

        if (GraphemeSegmenter.continuesCurrentCluster(state, currentClass, codepoint)) {
            appendToClusterOrFlush(state, codepoint)
            appendContinuationIfAlreadyEmitted(state, codepoint)
            GraphemeSegmenter.updateContext(state, codepoint, currentClass)
            return
        }

        finishActiveCluster(state)

        appendToClusterOrFlush(state, codepoint)
        GraphemeSegmenter.updateContext(state, codepoint, currentClass)
    }

    fun flush(state: ParserState) {
        if (state.clusterEmittedLength > 0) {
            state.clearActiveClusterAfterFlush()
            return
        }
        flushUnemitted(state)
        state.clearActiveClusterAfterFlush()
    }

    fun flushForRender(state: ParserState) {
        if (state.clusterLength == 0 || state.clusterEmittedLength == state.clusterLength) return
        flushUnemitted(state)
        state.clusterEmittedLength = state.clusterLength
    }

    private fun finishActiveCluster(state: ParserState) {
        if (state.clusterEmittedLength > 0) {
            state.clearActiveClusterAfterFlush()
        } else {
            flushUnemitted(state)
            state.clearActiveClusterAfterFlush()
        }
    }

    private fun flushUnemitted(state: ParserState) {
        when (state.clusterLength) {
            0 -> return
            1 -> sink.writeCodepoint(state.clusterBuffer[0])
            else ->
                sink.writeCluster(
                    codepoints = state.clusterBuffer,
                    length = state.clusterLength,
                )
        }
    }

    fun reset(state: ParserState) {
        state.clearActiveClusterAfterFlush()
    }

    private fun appendToClusterOrFlush(
        state: ParserState,
        codepoint: Int,
    ) {
        if (state.clusterLength >= state.clusterBuffer.size) {
            flush(state)
        }
        state.clusterBuffer[state.clusterLength] = codepoint
        state.clusterLength++
    }

    private fun appendContinuationIfAlreadyEmitted(
        state: ParserState,
        codepoint: Int,
    ) {
        if (state.clusterEmittedLength == 0) return
        sink.appendToPreviousCluster(codepoint)
        state.clusterEmittedLength = state.clusterLength
    }
}
