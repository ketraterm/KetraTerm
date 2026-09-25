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

/** Measures bounded retention with whole reads or byte-at-a-time publication and recycled history. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalGraphemeBenchmark {
    @Param("parser", "core")
    lateinit var pipeline: String

    @Param("1", "2", "32", "1024")
    var codepoints: Int = 0

    @Param("0", "1")
    var chunkBytes: Int = 0

    private lateinit var bytes: ByteArray
    private lateinit var parser: TerminalOutputParser
    private var terminal: TerminalBuffer? = null

    @Setup(Level.Trial)
    open fun setup() {
        val sink =
            when (pipeline) {
                "parser" -> NoOpCommandSink()
                "core" -> {
                    val buffer = TerminalBuffers.create(80, 24, maxHistory = 32)
                    terminal = buffer
                    HostCommandAdapter(buffer)
                }
                else -> error("Unknown pipeline: $pipeline")
            }
        parser = TerminalParsers.create(sink)
        bytes = ("a" + "\u0301".repeat(codepoints - 1) + "X\r\n").repeat(64).encodeToByteArray()
    }

    @Benchmark
    @OperationsPerInvocation(64)
    open fun parse(): TerminalOutputParser {
        if (chunkBytes == 0) {
            parser.accept(bytes)
        } else {
            for (byte in bytes) parser.acceptByte(byte.toInt() and 0xff)
        }
        return parser
    }

    @TearDown(Level.Trial)
    open fun verify() {
        val buffer = terminal ?: return
        check(buffer.historySize == 32)
        check(buffer.getCodepointAt(1, 22) == 'X'.code)
        if (codepoints > 1) {
            val retained = IntArray(32)
            val length = buffer.getLine(22).readCluster(0, retained)
            check(length == minOf(codepoints, 32))
            check(retained[0] == 'a'.code)
            for (index in 1 until length) check(retained[index] == 0x0301)
        }
    }
}
