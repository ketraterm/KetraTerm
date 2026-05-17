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
package com.gagik.benchmark

import com.gagik.core.TerminalBuffers
import com.gagik.core.api.TerminalBufferApi
import com.gagik.terminal.render.api.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalBufferBenchmark : TerminalRenderFrameConsumer {
    @Param("80", "120", "240")
    var width: Int = 0

    @Param("24", "40", "80")
    var height: Int = 0

    private lateinit var buffer: TerminalBufferApi
    private lateinit var reader: TerminalRenderFrameReader
    private lateinit var codeWords: IntArray
    private lateinit var attrWords: LongArray
    private lateinit var flags: IntArray

    private var runSingleRowOnly = false

    @Setup
    open fun setup() {
        buffer = TerminalBuffers.create(width, height)
        reader = buffer as TerminalRenderFrameReader
        codeWords = IntArray(width)
        attrWords = LongArray(width)
        flags = IntArray(width)

        // Fill with ASCII
        for (row in 0 until height) {
            buffer.positionCursor(0, row)
            for (col in 0 until width) {
                buffer.writeCodepoint('A'.code)
            }
        }
    }

    override fun accept(frame: TerminalRenderFrame) {
        if (runSingleRowOnly) {
            frame.copyLine(0, codeWords, 0, attrWords, 0, flags, 0)
        } else {
            for (row in 0 until height) {
                frame.copyLine(row, codeWords, 0, attrWords, 0, flags, 0)
            }
        }
    }

    @Benchmark
    open fun copyFullFrameAscii() {
        runSingleRowOnly = false
        reader.readRenderFrame(this)
    }

    @Benchmark
    open fun copySingleRowAscii() {
        runSingleRowOnly = true
        reader.readRenderFrame(this)
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalBufferClusterBenchmark : TerminalRenderFrameConsumer {
    @Param("0", "1", "100")
    var clusterPercent: Int = 0

    private val width = 80
    private val height = 24
    private lateinit var buffer: TerminalBufferApi
    private lateinit var reader: TerminalRenderFrameReader
    private lateinit var codeWords: IntArray
    private lateinit var attrWords: LongArray
    private lateinit var flags: IntArray
    private val clusterSink = TerminalRenderClusterSink { _, _ -> }
    private val clusterDataSink = TerminalRenderClusterDataSink { _, _, _, _ -> }

    private var runWithDataSink = false

    @Setup
    open fun setup() {
        buffer = TerminalBuffers.create(width, height)
        reader = buffer as TerminalRenderFrameReader
        codeWords = IntArray(width)
        attrWords = LongArray(width)
        flags = IntArray(width)

        for (row in 0 until height) {
            buffer.positionCursor(0, row)
            for (col in 0 until width) {
                if (clusterPercent > 0 && (row * width + col) % (100 / clusterPercent.coerceAtLeast(1)) == 0) {
                    buffer.writeCluster(intArrayOf('e'.code, 0x0301))
                } else {
                    buffer.writeCodepoint('A'.code)
                }
            }
        }
    }

    override fun accept(frame: TerminalRenderFrame) {
        if (runWithDataSink) {
            for (row in 0 until height) {
                frame.copyLine(
                    row = row,
                    codeWords = codeWords,
                    codeOffset = 0,
                    attrWords = attrWords,
                    attrOffset = 0,
                    flags = flags,
                    flagOffset = 0,
                    clusterDataSink = clusterDataSink,
                )
            }
        } else {
            for (row in 0 until height) {
                frame.copyLine(
                    row = row,
                    codeWords = codeWords,
                    codeOffset = 0,
                    attrWords = attrWords,
                    attrOffset = 0,
                    flags = flags,
                    flagOffset = 0,
                    clusterSink = clusterSink,
                )
            }
        }
    }

    @Benchmark
    open fun copyFullFrameWithClusters() {
        runWithDataSink = false
        reader.readRenderFrame(this)
    }

    @Benchmark
    open fun copyFullFrameWithClustersDataSink() {
        runWithDataSink = true
        reader.readRenderFrame(this)
    }
}
