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
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.TerminalPointerShape
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Measures warmed pointer metadata reads and three valid OSC 22 requests without UI or grid work. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalPointerShapeBenchmark {
    private lateinit var adapter: HostCommandAdapter
    private lateinit var parser: TerminalOutputParser
    private lateinit var bytes: ByteArray

    @Setup(Level.Trial)
    open fun setup() {
        adapter = HostCommandAdapter(TerminalBuffers.create(80, 24))
        parser = TerminalParsers.create(adapter)
        bytes = "\u001B]22;pointer\u0007\u001B]22;\u001B\\\u001B]22;=wait\u0007".encodeToByteArray()
        parser.accept(bytes)
    }

    @Benchmark
    open fun readPointerShape(): TerminalPointerShape? = adapter.pointerShape

    @Benchmark
    @OperationsPerInvocation(3)
    open fun parsePointerShapes(): TerminalPointerShape? {
        parser.accept(bytes)
        return adapter.pointerShape
    }

    @TearDown(Level.Trial)
    open fun verifyShape() {
        check(adapter.pointerShape == TerminalPointerShape.WAIT)
    }
}
