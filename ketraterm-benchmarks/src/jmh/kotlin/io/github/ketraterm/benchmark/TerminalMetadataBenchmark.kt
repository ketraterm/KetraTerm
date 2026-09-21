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
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Measures steady render-cache reads and unchanged color controls with an installed observer. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalMetadataBenchmark {
    private lateinit var terminal: TerminalBuffer
    private lateinit var adapter: HostCommandAdapter
    private lateinit var cache: TerminalRenderCache
    private lateinit var reader: TerminalRenderFrameReader
    private var callbacks = 0

    @Setup(Level.Trial)
    open fun setup() {
        terminal = TerminalBuffers.create(80, 24)
        reader = terminal as TerminalRenderFrameReader
        cache = TerminalRenderCache(80, 24)
        adapter =
            HostCommandAdapter(
                terminal,
                object : HostEventSink by HostEventSink.NONE {
                    override fun paletteChanged(palette: TerminalColorPalette) {
                        callbacks++
                    }

                    override fun hyperlinkRegistered(
                        hyperlinkId: Int,
                        uri: String,
                        id: String?,
                    ) {
                        callbacks++
                    }
                },
            )
        adapter.startHyperlink("https://example.com", "link")
        terminal.writeText("linked text")
        adapter.endHyperlink()
        adapter.setPaletteColor(1, 0xff123456.toInt())
        adapter.setDynamicColor(10, 0xffabcdef.toInt())
        cache.updateFrom(reader)
    }

    @Benchmark
    open fun unchangedColors(): Int {
        adapter.setPaletteColor(1, 0xff123456.toInt())
        adapter.setDynamicColor(10, 0xffabcdef.toInt())
        return callbacks
    }

    @Benchmark
    open fun renderCacheWithMetadata(): TerminalRenderCache {
        cache.updateFrom(reader)
        return cache
    }

    @TearDown(Level.Trial)
    open fun verify() {
        check(callbacks == 3) { "Unchanged state emitted metadata callbacks" }
    }
}
