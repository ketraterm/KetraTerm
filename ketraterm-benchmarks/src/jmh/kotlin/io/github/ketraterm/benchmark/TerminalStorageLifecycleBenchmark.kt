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
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.BenchmarkParams
import java.util.concurrent.TimeUnit

/**
 * Measures history disposal and warmed mutation after extended cell attributes have been cleared.
 * All methods use public core APIs, reused buffers, and payloads prepared outside timing.
 * History operations are single shots; their iteration setup repopulates history before each shot.
 * GC profiling includes that setup and semantic teardown, so those totals are not disposal-only allocation.
 * Steady-state methods have no invocation/iteration fixtures: one operation touches one 24-row viewport.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalStorageLifecycleBenchmark {
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 15)
    @Measurement(iterations = 15)
    open fun discardHistory(state: HistoryState): Int {
        state.buffer.eraseScreenAndHistory()
        return state.buffer.historySize
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 15)
    @Measurement(iterations = 15)
    open fun clearAll(state: HistoryState): Int {
        state.buffer.clearAll()
        return state.buffer.historySize
    }

    /** Writes, shifts, and partially erases each row with a zero extended-attribute pen. */
    @Benchmark
    open fun writeShiftErase(state: SteadyState): Int {
        val buffer = state.buffer
        for (row in 0 until ROWS) {
            buffer.positionCursor(0, row)
            buffer.writeAscii(state.ascii, 0, state.ascii.size)
            buffer.positionCursor(state.columns / 2, row)
            buffer.insertBlankCharacters(1)
            buffer.deleteCharacters(1)
            buffer.eraseLineToEnd()
        }
        return buffer.cursorCol
    }

    /** Repeatedly clears a blank viewport; row storage is retained between calls. */
    @Benchmark
    open fun clearScreen(state: SteadyState): Int {
        state.buffer.clearScreen()
        return state.buffer.cursorCol
    }

    /** Rewrites a full clustered viewport, exercising payload-region reuse. */
    @Benchmark
    open fun rewriteClusters(state: ClusterState): Int {
        state.lastPayload = state.payload
        return rewriteClusterRows(state)
    }

    /** Alternating retained lengths forces growth, dead regions, and ordinary mutation compaction. */
    @Benchmark
    open fun resizeClusters(state: ClusterState): Int {
        state.lastPayload = if (state.lastPayload === state.payload) state.shortPayload else state.payload
        return rewriteClusterRows(state)
    }

    private fun rewriteClusterRows(state: ClusterState): Int {
        for (row in 0 until ROWS) {
            state.buffer.positionCursor(0, row)
            for (column in 0 until HISTORY_COLUMNS) state.buffer.writeCluster(state.lastPayload)
        }
        return state.buffer.cursorCol
    }

    @State(Scope.Thread)
    open class ClusterState {
        @Param("2", "32")
        var length: Int = 0
        lateinit var buffer: TerminalRenderBuffer
        lateinit var payload: IntArray
        val shortPayload = intArrayOf('e'.code, 0x0301)
        lateinit var lastPayload: IntArray

        @Setup(Level.Trial)
        open fun createFixture() {
            buffer = TerminalBuffers.create(HISTORY_COLUMNS, ROWS, 0)
            payload = IntArray(length) { if (it == 0) 'e'.code else 0x0301 }
            lastPayload = payload
        }

        @TearDown(Level.Trial)
        open fun verifyContent() {
            val copy = IntArray(lastPayload.size)
            for (row in 0 until ROWS) {
                val line = buffer.getLine(row)
                for (column in 0 until HISTORY_COLUMNS) {
                    check(line.getClusterLength(column) == lastPayload.size)
                    check(line.readCluster(column, copy) == lastPayload.size)
                    check(copy.contentEquals(lastPayload))
                }
            }
            check(buffer.historySize == 0)
            check(buffer.cursorCol == HISTORY_COLUMNS - 1 && buffer.cursorRow == ROWS - 1)
        }
    }

    @State(Scope.Thread)
    open class HistoryState {
        @Param("10000")
        var retainedRows: Int = 0

        @Param("ascii", "cluster", "clusterEnd", "denseClusters")
        lateinit var content: String

        lateinit var buffer: TerminalRenderBuffer
        private lateinit var ascii: ByteArray
        private lateinit var codes: IntArray
        private lateinit var flags: IntArray
        private val cluster = intArrayOf('e'.code, 0x0301)
        private val clusterCopy = IntArray(2)
        private var clearsViewport = false

        @Setup(Level.Trial)
        open fun createFixture(params: BenchmarkParams) {
            require(params.mode == Mode.SingleShotTime) { "History disposal requires single-shot timing" }
            require(params.warmup.batchSize == 1 && params.measurement.batchSize == 1) { "Each shot must dispose one history" }
            clearsViewport = params.benchmark.endsWith(".clearAll")
            buffer = TerminalBuffers.create(HISTORY_COLUMNS, ROWS, retainedRows)
            ascii = ByteArray(HISTORY_COLUMNS - 1) { ('A'.code + it % 26).toByte() }
            codes = IntArray(HISTORY_COLUMNS)
            flags = IntArray(HISTORY_COLUMNS)
        }

        @Setup(Level.Iteration)
        open fun populateHistory() {
            buffer.clearScreen()
            for (row in 0 until retainedRows + ROWS) {
                when (content) {
                    "ascii" -> buffer.writeAscii(ascii, 0, ascii.size)
                    "cluster" -> {
                        buffer.writeCluster(cluster)
                        buffer.writeAscii(ascii, 1, ascii.size - 1)
                    }
                    "clusterEnd" -> {
                        buffer.writeAscii(ascii, 0, ascii.size)
                        buffer.writeCluster(cluster)
                    }
                    "denseClusters" -> {
                        for (column in 0 until HISTORY_COLUMNS) buffer.writeCluster(cluster)
                    }
                    else -> error("Unknown history content: $content")
                }
                if (row != retainedRows + ROWS - 1) {
                    buffer.carriageReturn()
                    buffer.newLine()
                }
            }
            buffer.positionCursor(7, ROWS - 2)
            check(buffer.historySize == retainedRows)
        }

        @TearDown(Level.Iteration)
        open fun verifyDisposal() {
            check(buffer.historySize == 0)
            check(buffer.cursorCol == if (clearsViewport) 0 else 7)
            check(buffer.cursorRow == if (clearsViewport) 0 else ROWS - 2)
            buffer.readRenderFrame { frame ->
                check(frame.rows == ROWS && frame.historySize == 0)
                for (row in 0 until ROWS) {
                    check(!frame.lineWrapped(row))
                    frame.copyTextLine(row, codes, flags = flags)
                    val line = buffer.getLine(row)
                    for (column in 0 until HISTORY_COLUMNS) {
                        val isCluster =
                            !clearsViewport &&
                                when (content) {
                                    "cluster" -> column == 0
                                    "clusterEnd" -> column == HISTORY_COLUMNS - 1
                                    "denseClusters" -> true
                                    else -> false
                                }
                        val isEmpty = clearsViewport || ((content == "ascii" || content == "cluster") && column == HISTORY_COLUMNS - 1)
                        val expected = if (isEmpty || isCluster) 0 else ascii[column].toInt()
                        check(codes[column] == expected)
                        check((flags[column] and TerminalRenderCellFlags.CLUSTER != 0) == isCluster)
                        check((flags[column] and TerminalRenderCellFlags.EMPTY != 0) == isEmpty)
                        if (isCluster) {
                            check(line.getClusterLength(column) == cluster.size)
                            check(line.readCluster(column, clusterCopy) == cluster.size)
                            check(clusterCopy.contentEquals(cluster))
                        }
                    }
                }
            }
        }
    }

    @State(Scope.Thread)
    open class SteadyState {
        @Param("120")
        var columns: Int = 0

        @Param("never", "cleared")
        lateinit var extendedHistory: String

        lateinit var buffer: TerminalRenderBuffer
        lateinit var ascii: ByteArray
        private lateinit var codes: IntArray
        private lateinit var attrs: LongArray
        private lateinit var extraAttrs: LongArray
        private lateinit var hyperlinkIds: IntArray
        private lateinit var flags: IntArray
        private var writesRows = false

        @Setup(Level.Trial)
        open fun createFixture(params: BenchmarkParams) {
            writesRows = params.benchmark.endsWith(".writeShiftErase")
            buffer = TerminalBuffers.create(columns, ROWS, 0)
            ascii = ByteArray(columns) { ('A'.code + it % 26).toByte() }
            codes = IntArray(columns)
            attrs = LongArray(columns)
            extraAttrs = LongArray(columns)
            hyperlinkIds = IntArray(columns)
            flags = IntArray(columns)
            if (extendedHistory == "cleared") {
                buffer.setHyperlinkId(37)
                for (row in 0 until ROWS) {
                    buffer.positionCursor(0, row)
                    buffer.writeAscii(ascii, 0, ascii.size)
                }
                buffer.setHyperlinkId(0)
            }
            buffer.clearScreen()
        }

        @TearDown(Level.Trial)
        open fun verifyViewport() {
            check(buffer.historySize == 0)
            check(buffer.cursorCol == if (writesRows) columns / 2 else 0)
            check(buffer.cursorRow == if (writesRows) ROWS - 1 else 0)
            buffer.readRenderFrame { frame ->
                for (row in 0 until ROWS) {
                    check(!frame.lineWrapped(row))
                    frame.copyLine(row, codes, attrWords = attrs, flags = flags, extraAttrWords = extraAttrs, hyperlinkIds = hyperlinkIds)
                    for (column in 0 until columns) {
                        val expected = if (writesRows && column < columns / 2) ascii[column].toInt() else 0
                        check(codes[column] == expected)
                        check(attrs[column] == 0L && extraAttrs[column] == 0L && hyperlinkIds[column] == 0)
                    }
                }
            }
        }
    }

    private companion object {
        const val ROWS = 24
        const val HISTORY_COLUMNS = 120
    }
}
