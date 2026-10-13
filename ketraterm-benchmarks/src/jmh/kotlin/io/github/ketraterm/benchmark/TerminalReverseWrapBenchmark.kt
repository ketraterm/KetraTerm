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
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * Measures CUB with a hostile count through core and parser/host boundaries.
 * Construction, soft-wrap seeding and byte encoding occur once per trial.
 * Each operation positions the cursor then moves it; no writes or resizing occur.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalReverseWrapBenchmark {
    @Param("0", "45", "1045")
    var mode: Int = 0

    private lateinit var terminal: TerminalBuffer
    private lateinit var parser: TerminalOutputParser
    private lateinit var bytes: ByteArray

    @Setup(Level.Trial)
    open fun setup() {
        terminal = TerminalBuffers.create(80, 24)
        terminal.writeText("A".repeat(80 * 23 + 1))
        terminal.setReverseWrap(mode == 45)
        terminal.setReverseWrapExtended(mode == 1045)
        parser = TerminalParsers.create(HostCommandAdapter(terminal))
        bytes = "\u001B[24;2H\u001B[2147483647D".encodeToByteArray()
    }

    @Benchmark
    open fun core(): Int {
        terminal.positionCursor(1, 23)
        terminal.cursorLeft(Int.MAX_VALUE)
        return terminal.cursorRow * 80 + terminal.cursorCol
    }

    @Benchmark
    open fun host(): Int {
        parser.accept(bytes)
        return terminal.cursorRow * 80 + terminal.cursorCol
    }

    @TearDown(Level.Trial)
    open fun verifyCursor() {
        val expected =
            when (mode) {
                0 -> 1840
                45 -> 0
                1045 -> 1714
                else -> error("Unknown mode")
            }
        check(terminal.cursorRow * 80 + terminal.cursorCol == expected)
        check(terminal.historySize == 0)
    }
}
