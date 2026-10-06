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

import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.BenchmarkParams
import java.util.concurrent.TimeUnit

/**
 * First parser input in a fresh JVM, including lazy text-class initialization.
 * Parser construction and payload encoding happen before timing. No core or adapter is involved.
 * GC-profiler results include harness activity and must not be attributed solely to Unicode tables.
 * Excluded from the normal warmed Gradle suite. Run explicitly from the JMH JAR with
 * `TerminalParserFirstInputBenchmark -wi 0 -i 1 -f 15 -bm ss -tu us`.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1, batchSize = 1)
@Fork(15)
open class TerminalParserFirstInputBenchmark {
    @Param("ascii", "unicode")
    lateinit var workload: String

    private lateinit var parser: TerminalOutputParser
    private lateinit var bytes: ByteArray

    @Setup(Level.Trial)
    open fun setup(params: BenchmarkParams) {
        require(params.mode == Mode.SingleShotTime)
        require(params.forks > 0 && params.threads == 1) { "First-input measurement requires one thread in a fresh JVM fork." }
        require(params.warmup.count == 0 && params.measurement.count == 1 && params.measurement.batchSize == 1) {
            "First-input measurement requires a fresh fork, no warmup, and exactly one invocation."
        }
        parser = TerminalParsers.create(NoOpCommandSink())
        bytes =
            when (workload) {
                "ascii" -> "hello\r\n".encodeToByteArray()
                "unicode" -> "\u4E2D\u6587\r\n".encodeToByteArray()
                else -> error("Unknown workload: $workload")
            }
    }

    @Benchmark
    open fun firstInput() {
        parser.accept(bytes, 0, bytes.size)
    }
}
