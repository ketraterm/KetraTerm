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
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.RetainedFrameViewport
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Measures frozen-row projection and cache copying; excludes Swing painting and EDT dispatch. */
@Suppress("unused")
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class RetainedFrameViewportBenchmark {
    private lateinit var viewport: RetainedFrameViewport
    private lateinit var cache: TerminalRenderCache
    private var offset = 0

    @Setup
    open fun setup() {
        val source = TerminalBuffers.create(80, 24, 128)
        repeat(100) { source.writeText("retained terminal output ".padEnd(80)) }
        viewport = RetainedFrameViewport(source).also { it.visibleRows = 12 }
        cache = TerminalRenderCache(80, 13)
        cache.updateFrom(viewport, 0, 13)
    }

    @Benchmark
    open fun unchanged(): Long {
        cache.updateFrom(viewport, 0, 13)
        return cache.lineIds[0]
    }

    @Benchmark
    open fun scroll(): Long {
        offset = (offset + 1) % 64
        cache.updateFrom(viewport, offset, 13)
        return cache.lineIds[0]
    }
}
