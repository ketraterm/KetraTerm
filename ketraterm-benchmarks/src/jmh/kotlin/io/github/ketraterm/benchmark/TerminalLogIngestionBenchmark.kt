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
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.Blackhole
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reproduces the IntelliJ comparison's 6,000-line ASCII log and completion marker.
 * Each shot starts with a fresh terminal; construction and payload generation are outside timing.
 * No compatibility projection, transport conversion, publication worker, or frontend is included.
 * Iteration teardown verifies every retained cell, wrapping, eviction, and the final cursor.
 *
 * The GC profiler includes iteration setup: its allocation totals must not be called ingestion allocation.
 * [pipeline] selects direct core writes, isolated parsing into a no-op sink, or parser-to-core ingestion.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 60)
@Measurement(iterations = 30)
@Fork(3)
open class TerminalLogIngestionBenchmark {
    @Param("core", "parser", "host")
    lateinit var pipeline: String

    @Param("80", "240")
    var columns: Int = 0

    @Param("13107", "1000")
    var maxHistory: Int = 0

    @Param("4096", "16384")
    var chunkBytes: Int = 0

    private lateinit var lines: List<String>
    private lateinit var bytes: ByteArray
    private lateinit var expectedRows: List<String>
    private lateinit var expectedWraps: BooleanArray
    private lateinit var codes: IntArray
    private lateinit var attrs: LongArray
    private lateinit var flags: IntArray
    private var buffer: TerminalRenderBuffer? = null
    private var parser: TerminalOutputParser? = null

    @Setup(Level.Trial)
    open fun preparePayload(params: BenchmarkParams) {
        require(params.mode == Mode.SingleShotTime) { "Fresh-terminal ingestion requires single-shot timing" }
        require(params.warmup.batchSize == 1 && params.measurement.batchSize == 1) { "Each shot must ingest exactly one log" }
        require(chunkBytes > 0) { "Input chunk size must be positive" }
        lines = List(6000) { index ->
            buildString {
                append("2026-08-07 12:34:56,")
                append(String.format(Locale.ROOT, "%03d", index % 1000))
                append(" [").append(String.format(Locale.ROOT, "%6d", index))
                append("] INFO - #c.i.o.diagnostic.ExampleComponent - ")
                append("request ").append(index % 10_000)
                append(" handled in 12 ms, state=RUNNING, payload: ")
                append("abcdefghijklmnopqrstuvwxyz-0123456789")
            }
        } + "" + MARKER
        bytes = lines.joinToString("\r\n").encodeToByteArray()
        expectedRows = lines.flatMap { line -> if (line.isEmpty()) listOf("") else line.chunked(columns) }
        expectedWraps = BooleanArray(expectedRows.size)
        var row = 0
        for (line in lines) {
            val count = maxOf(1, (line.length + columns - 1) / columns)
            repeat(count - 1) { expectedWraps[row++] = true }
            row++
        }
        codes = IntArray(columns)
        attrs = LongArray(columns)
        flags = IntArray(columns)
    }

    @Setup(Level.Iteration)
    open fun createPipeline() {
        buffer = if (pipeline == "parser") null else TerminalBuffers.create(columns, ROWS, maxHistory)
        parser =
            when (pipeline) {
                "core" -> null
                "parser" -> TerminalParsers.create(NoOpCommandSink())
                "host" -> TerminalParsers.create(HostCommandAdapter(checkNotNull(buffer)))
                else -> error("Unknown pipeline: $pipeline")
            }
    }

    @Benchmark
    open fun ingest(blackhole: Blackhole) {
        val terminal = buffer
        val outputParser = parser
        if (outputParser != null) {
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(chunkBytes, bytes.size - offset)
                outputParser.accept(bytes, offset, length)
                offset += length
            }
            blackhole.consume(outputParser)
        } else {
            checkNotNull(terminal)
            for (index in lines.indices) {
                terminal.writeText(lines[index])
                if (index != lines.lastIndex) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
            }
        }
        blackhole.consume(terminal)
    }

    @TearDown(Level.Iteration)
    open fun verifyRetainedOutput() {
        val terminal = buffer ?: return
        val history = minOf(maxHistory, maxOf(0, expectedRows.size - ROWS))
        val discarded = maxOf(0, expectedRows.size - ROWS - history)
        check(terminal.historySize == history)
        check(terminal.cursorRow == ROWS - 1 && terminal.cursorCol == MARKER.length)
        terminal.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
            check(frame.discardedCount == discarded.toLong())
            check(frame.rows == history + ROWS)
            for (row in 0 until frame.rows) {
                val expected = expectedRows[discarded + row]
                check(frame.lineWrapped(row) == expectedWraps[discarded + row]) { "Wrap mismatch at row $row" }
                frame.copyLine(row, codes, attrWords = attrs, flags = flags)
                for (column in 0 until columns) {
                    val code = if (column < expected.length) expected[column].code else 0
                    check(codes[column] == code) { "Cell mismatch at [$column, $row]" }
                    check(attrs[column] == 0L) { "Unexpected style at [$column, $row]" }
                    check((flags[column] and TerminalRenderCellFlags.EMPTY != 0) == (code == 0))
                }
            }
        }
    }

    private companion object {
        const val ROWS = 24
        const val MARKER = "PERFDONE0"
    }
}
