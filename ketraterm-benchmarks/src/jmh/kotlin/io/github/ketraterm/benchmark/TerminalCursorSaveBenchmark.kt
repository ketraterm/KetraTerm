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

/** Measures parser-to-core cursor saves and mode-dependent margin resets with prebuilt input. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalCursorSaveBenchmark {
    @Param("dec", "sco", "margins")
    lateinit var workload: String

    private lateinit var terminal: TerminalBuffer
    private lateinit var parser: TerminalOutputParser
    private lateinit var bytes: ByteArray

    @Setup(Level.Trial)
    open fun setup() {
        terminal = TerminalBuffers.create(width = 80, height = 24)
        parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001B[3;5H\u001B(0\u001B7".encodeToByteArray())
        bytes =
            when (workload) {
                "dec" -> "\u001B7\u001B[10;20H\u001B8"
                "sco" -> "\u001B[s\u001B[10;20H\u001B[u"
                "margins" -> "\u001B[?69h\u001B[3;70s\u001B[s\u001B[u\u001B[?69l"
                else -> error("Unknown workload: $workload")
            }.encodeToByteArray()
    }

    @Benchmark
    open fun saveRestore(): Int {
        parser.accept(bytes)
        return terminal.cursorCol
    }
}
