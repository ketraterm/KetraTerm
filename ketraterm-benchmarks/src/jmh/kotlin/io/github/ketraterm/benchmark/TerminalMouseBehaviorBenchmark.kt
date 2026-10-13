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
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * Measures primitive mouse-routing reads and parser/host negotiation.
 * Setup creates the terminal, parser, and byte stream outside measurement.
 * No UI events, grid mutations, or frame snapshot objects are created.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalMouseBehaviorBenchmark {
    @Param("false", "true")
    var captureShift: Boolean = false

    private lateinit var terminal: TerminalBuffer
    private lateinit var parser: TerminalOutputParser
    private lateinit var bytes: ByteArray

    @Setup(Level.Trial)
    open fun setup() {
        terminal = TerminalBuffers.create(80, 24)
        terminal.setShiftMouseCaptureEnabled(captureShift)
        terminal.setAlternateScrollEnabled(!captureShift)
        parser = TerminalParsers.create(HostCommandAdapter(terminal))
        bytes =
            if (captureShift) {
                "\u001B[?1007h\u001B[>0s\u001B[?1007l\u001B[>1s"
            } else {
                "\u001B[?1007l\u001B[>1s\u001B[?1007h\u001B[>0s"
            }.encodeToByteArray()
    }

    @Benchmark
    open fun readRouting(): Int {
        val bits = terminal.getInputModeBits()
        return (if (TerminalInputState.isAlternateScrollEnabled(bits)) 1 else 0) or
            (if (TerminalInputState.isShiftMouseCaptureEnabled(bits)) 2 else 0)
    }

    @Benchmark
    open fun negotiate(): Long {
        parser.accept(bytes)
        return terminal.getInputModeBits()
    }

    @TearDown(Level.Trial)
    open fun verifyModes() {
        val bits = terminal.getInputModeBits()
        check(TerminalInputState.isShiftMouseCaptureEnabled(bits) == captureShift)
        check(TerminalInputState.isAlternateScrollEnabled(bits) != captureShift)
        check(terminal.cursorCol == 0 && terminal.cursorRow == 0 && terminal.historySize == 0)
    }
}
