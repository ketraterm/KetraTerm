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
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Warmed core-to-publisher-to-consumer frames; upload and capacity growth occur only in setup.
 *
 * @property placements number of one-cell placements sharing one immutable image resource.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class TerminalGraphicsFrameBenchmark {
    @Param("0", "1", "4096")
    var placements: Int = 0
    private lateinit var terminal: TerminalRenderBuffer
    private lateinit var publisher: TerminalRenderPublisher
    private lateinit var cache: TerminalRenderCache

    /** Creates the image placements and warms publisher/cache capacity before measurement. */
    @Setup
    fun setup() {
        terminal = TerminalBuffers.create(80, 60)
        terminal.resize(80, 60, 0, 640, 960)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001b_Ga=t,i=1,f=24,s=1,v=1,q=2;/wAA\u001b\\".encodeToByteArray())
        repeat(placements) { i ->
            parser.accept(
                (
                    "\u001b[${i / 80 + 1};${i % 80 + 1}H" +
                        "\u001b_Ga=p,i=1,p=${i + 1},C=1,c=1,r=1,q=2;\u001b\\"
                ).encodeToByteArray(),
            )
        }
        publisher = TerminalRenderPublisher(80, 60)
        cache = TerminalRenderCache(80, 60)
        repeat(4) {
            publisher.updateAndPublish(terminal)
            publisher.readCurrent { cache.updateFrom(it) }
        }
        check(cache.images.size == placements)
    }

    /**
     * Publishes and copies one unchanged frame with the configured image placements.
     *
     * @param blackhole consumes placement count and generation to keep the result observable.
     */
    @Benchmark
    fun publishAndCopy(blackhole: Blackhole) {
        publisher.updateAndPublish(terminal)
        publisher.readCurrent { cache.updateFrom(it) }
        blackhole.consume(cache.images.size)
        blackhole.consume(cache.frameGeneration)
    }
}
