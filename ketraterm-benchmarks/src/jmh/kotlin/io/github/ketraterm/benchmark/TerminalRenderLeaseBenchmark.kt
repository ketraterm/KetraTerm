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
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures publisher-owned lease bookkeeping without Swing, frame copying, or callback allocation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class TerminalRenderLeaseBenchmark {
    @Param("empty", "published")
    lateinit var frame: String

    private lateinit var publisher: TerminalRenderPublisher

    @Setup
    fun setup() {
        publisher = TerminalRenderPublisher(80, 24)
        if (frame == "published") {
            val buffer = TerminalBuffers.create(80, 24, 0)
            buffer.writeCodepoint('A'.code)
            publisher.updateAndPublish(buffer)
        }
    }

    @Benchmark
    fun readLease(blackhole: Blackhole) {
        publisher.readCurrent { cache -> blackhole.consume(cache.codeWords[0]) }
    }
}
