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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** External frames intentionally inherit the default palette; no core or UI participates. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class ExternalFramePaletteBenchmark {
    @Param("unchanged", "changing")
    lateinit var content: String

    @Param("inherited", "allocating-control")
    lateinit var palette: String

    private lateinit var frame: ExternalFrame

    @Setup
    fun setup() {
        frame =
            if (palette == "inherited") {
                ExternalFrame()
            } else {
                object : ExternalFrame() {
                    override val palette: TerminalColorPalette get() = TerminalColorPalette()
                }
            }
    }

    private val reader =
        object : TerminalRenderFrameReader {
            override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = consumer.accept(frame)
        }
    private val cache = TerminalRenderCache(80, 24)

    @Benchmark
    fun acceptFrame(blackhole: Blackhole) {
        if (content == "changing") frame.frameGeneration++
        cache.updateFrom(reader)
        blackhole.consume(cache.palette)
        blackhole.consume(cache.codeWords[0])
    }

    private open class ExternalFrame : TerminalRenderFrame {
        override val columns = 80
        override val rows = 24
        override var frameGeneration = 1L
        override val structureGeneration = 1L
        override val activeBuffer = TerminalRenderBufferKind.PRIMARY
        override val cursor = TerminalRenderCursor(0, 0, true, false, TerminalRenderCursorShape.BLOCK, 1L)

        override fun lineGeneration(row: Int): Long = frameGeneration

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            codeWords.fill('A'.code, codeOffset, codeOffset + columns)
            attrWords.fill(TerminalRenderAttrs.DEFAULT, attrOffset, attrOffset + columns)
            flags.fill(TerminalRenderCellFlags.CODEPOINT, flagOffset, flagOffset + columns)
            extraAttrWords?.fill(TerminalRenderExtraAttrs.DEFAULT, extraAttrOffset, extraAttrOffset + columns)
            hyperlinkIds?.fill(0, hyperlinkOffset, hyperlinkOffset + columns)
        }
    }
}
