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
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

// ---------- Write benchmarks ----------

/**
 * Measures raw [TerminalBufferApi] write throughput for ASCII codepoints,
 * CJK wide characters, and grapheme clusters on a single line.
 *
 * Each invocation clears the screen and homes the cursor so every iteration
 * starts from the same clean state.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalCoreWriteBenchmark {
    private val width = 160
    private val height = 48

    private lateinit var buffer: TerminalBufferApi

    /** Pre-allocated cluster: U+0065 LATIN SMALL LETTER E + U+0301 COMBINING ACUTE ACCENT. */
    private val eAcuteCluster = intArrayOf('e'.code, 0x0301)

    @Setup(Level.Trial)
    open fun createBuffer() {
        buffer = TerminalBuffers.create(width, height)
    }

    @Setup(Level.Invocation)
    open fun resetScreen() {
        buffer.clearAll()
        buffer.positionCursor(0, 0)
    }

    /** Writes 160 ASCII codepoints to fill one line — fast-path writeCodepoint throughput. */
    @Benchmark
    open fun writeAsciiLine(bh: Blackhole) {
        for (i in 0 until width) {
            buffer.writeCodepoint('A'.code + (i % 26))
        }
        bh.consume(buffer)
    }

    /** Writes 80 CJK ideographs (each width-2) to fill one 160-column line. */
    @Benchmark
    open fun writeWideCharLine(bh: Blackhole) {
        val base = 0x4E00 // CJK Unified Ideographs block start
        for (i in 0 until 80) {
            buffer.writeCodepoint(base + i)
        }
        bh.consume(buffer)
    }

    /** Writes 80 grapheme clusters (é = e + combining acute) to fill one line. */
    @Benchmark
    open fun writeClusterLine(bh: Blackhole) {
        for (i in 0 until 80) {
            buffer.writeCluster(eAcuteCluster)
        }
        bh.consume(buffer)
    }
}

// ---------- Scroll benchmarks ----------

/**
 * Measures scroll-up throughput when the cursor is at the bottom of the screen
 * and newlines force content into scrollback.
 *
 * Trial setup fills the screen with content. Each invocation re-homes the
 * cursor to the last row so newlines trigger scrolling immediately.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalCoreScrollBenchmark {
    private val width = 160
    private val height = 48
    private val maxHistory = 10_000

    private lateinit var buffer: TerminalBufferApi

    @Setup(Level.Trial)
    open fun createAndFillBuffer() {
        buffer = TerminalBuffers.create(width, height, maxHistory)
        fillScreen()
    }

    @Setup(Level.Invocation)
    open fun homeCursorToBottom() {
        buffer.positionCursor(0, height - 1)
    }

    /** Forces 48 scroll-up operations by issuing newlines from the bottom row. */
    @Benchmark
    open fun scrollUpRepeat(bh: Blackhole) {
        for (i in 0 until height) {
            buffer.newLine()
        }
        bh.consume(buffer)
    }

    private fun fillScreen() {
        for (row in 0 until height) {
            buffer.positionCursor(0, row)
            for (col in 0 until width) {
                buffer.writeCodepoint('A'.code + (col % 26))
            }
        }
    }
}

// ---------- Erase benchmarks ----------

/**
 * Measures erase, insert, and delete character operations on a filled screen.
 *
 * Each invocation re-fills the screen and homes the cursor so that erase
 * operations always start from fully-populated content.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalCoreEraseBenchmark {
    private val width = 160
    private val height = 48

    private lateinit var buffer: TerminalBufferApi

    @Setup(Level.Trial)
    open fun createBuffer() {
        buffer = TerminalBuffers.create(width, height)
    }

    @Setup(Level.Invocation)
    open fun fillAndHome() {
        for (row in 0 until height) {
            buffer.positionCursor(0, row)
            for (col in 0 until width) {
                buffer.writeCodepoint('A'.code + (col % 26))
            }
        }
        buffer.positionCursor(0, 0)
    }

    /** ED 2 — erase entire screen. */
    @Benchmark
    open fun eraseEntireScreen(bh: Blackhole) {
        buffer.eraseEntireScreen()
        bh.consume(buffer)
    }

    /** EL 0 — erase from mid-line to end, repeated for every row. */
    @Benchmark
    open fun eraseLineToEnd(bh: Blackhole) {
        for (row in 0 until height) {
            buffer.positionCursor(width / 2, row)
            buffer.eraseLineToEnd()
        }
        bh.consume(buffer)
    }

    /** Alternating ICH/DCH — insert then delete one character, 80 times each on a filled line. */
    @Benchmark
    open fun insertDeleteCharacters(bh: Blackhole) {
        buffer.positionCursor(width / 2, 0)
        for (i in 0 until 80) {
            buffer.insertBlankCharacters(1)
            buffer.deleteCharacters(1)
        }
        bh.consume(buffer)
    }
}
