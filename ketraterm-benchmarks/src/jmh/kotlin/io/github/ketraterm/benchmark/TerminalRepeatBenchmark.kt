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
 * Equivalent output through scalar calls, a bulk core write, REP bytes, or expanded bytes.
 * Each invocation overwrites the same 80-column grid from home; setup and input generation
 * are excluded. At most 1024 single-width cells are written, so these cases never scroll.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalRepeatBenchmark {
    @Param("8", "80", "1024")
    var count: Int = 0

    private lateinit var terminal: TerminalBuffer
    private lateinit var parser: TerminalOutputParser
    private val graphic = intArrayOf('A'.code)
    private lateinit var rep: ByteArray
    private lateinit var expanded: ByteArray

    @Setup(Level.Trial)
    open fun setup() {
        terminal = TerminalBuffers.create(80, 24, maxHistory = 0)
        parser = TerminalParsers.create(HostCommandAdapter(terminal))
        rep = ("\u001B[HA\u001B[" + (count - 1) + "b").encodeToByteArray()
        expanded = ("\u001B[H" + "A".repeat(count)).encodeToByteArray()
    }

    @Benchmark
    open fun scalar(): TerminalBuffer {
        terminal.positionCursor(0, 0)
        var remaining = count
        while (remaining > 0) {
            terminal.writeCodepoint('A'.code)
            remaining--
        }
        return terminal
    }

    @Benchmark
    open fun bulk(): TerminalBuffer {
        terminal.positionCursor(0, 0)
        terminal.writeRepeatedCluster(graphic, 1, count)
        return terminal
    }

    @Benchmark
    open fun parseRep(): TerminalBuffer {
        parser.accept(rep)
        return terminal
    }

    @Benchmark
    open fun parseExpanded(): TerminalBuffer {
        parser.accept(expanded)
        return terminal
    }

    @TearDown(Level.Trial)
    open fun verify() {
        check(terminal.cursorCol == if (count % 80 == 0) 79 else count % 80)
        check(terminal.cursorRow == (count - 1) / 80)
        check(terminal.getCodepointAt(terminal.cursorCol - if (count % 80 == 0) 0 else 1, terminal.cursorRow) == 'A'.code)
        check(terminal.historySize == 0)
    }
}
