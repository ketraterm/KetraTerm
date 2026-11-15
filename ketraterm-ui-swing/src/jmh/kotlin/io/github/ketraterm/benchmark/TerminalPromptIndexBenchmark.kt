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
package io.github.ketraterm.benchmark

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.render.PromptDividerLayout
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * Sparse prompt indexing over real retained rows, without EDT dispatch or concurrent session work.
 * A retained anchor forces a full line-ID scan. Ordinary output includes one cell overwrite and CR;
 * it does not grow history. Construction and initial indexing happen before measurement.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalPromptIndexBenchmark {
    @Param("100", "10000", "100000")
    var historyRows = 0
    private lateinit var buffer: TerminalBuffer
    private lateinit var reader: TerminalRenderFrameReader
    private lateinit var state: TerminalShellIntegrationState
    private lateinit var layout: PromptDividerLayout
    private var anchor = 0L

    @Setup
    fun setup() {
        buffer = TerminalBuffers.create(80, 24, maxHistory = historyRows)
        repeat(historyRows + 23) { buffer.newLine() }
        reader = buffer as TerminalRenderFrameReader
        state = TerminalShellIntegrationState()
        reader.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) {
            state.recordPromptStart(it.lineId(0))
            anchor = it.lineId(historyRows / 2)
        }
        layout = PromptDividerLayout()
        layout.updateFrom(reader, state, 24, anchorLineId = anchor)
        check(layout.resolvedAnchorRow == historyRows / 2L)
    }

    @Benchmark fun unchanged(): Long {
        layout.updateFrom(reader, state, 24)
        return layout.liveOrigin
    }

    @Benchmark fun ordinaryOutput(): Long {
        buffer.writeCodepoint('x'.code)
        buffer.carriageReturn()
        layout.updateFrom(reader, state, 24)
        return layout.liveOrigin
    }

    /** Includes the detached projection handed to the EDT, but excludes coroutine dispatch. */
    @Benchmark fun ordinaryOutputProjection(): Any {
        buffer.writeCodepoint('x'.code)
        buffer.carriageReturn()
        layout.updateFrom(reader, state, 24)
        return layout.snapshot()
    }

    @Benchmark fun anchoredUpdate(): Long {
        layout.updateFrom(reader, state, 24, anchorLineId = anchor)
        return layout.resolvedAnchorRow
    }
}
