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

import com.sun.management.ThreadMXBean
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.render.painter.TerminalTextRunStyle
import org.openjdk.jmh.annotations.*
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Prepared native metadata: dense paint-run preparation and primitive action/style lookup. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalHyperlinkStyleBenchmark {
    @Param("80x24", "160x48", "240x48")
    @JvmField
    var grid = "80x24"
    private lateinit var cache: TerminalRenderCache
    private lateinit var actions: TerminalHyperlinkActions
    private val run = TerminalTextRunStyle()
    private lateinit var allocationBean: ThreadMXBean
    private var threadId = 0L
    private var phase = 0

    @Setup(Level.Trial)
    open fun setup() {
        val (columns, rows) = grid.split('x').map(String::toInt)
        cache = TerminalRenderCache(columns, rows).apply { accept(TerminalRenderBenchmarkFrame(List(rows) { "x".repeat(columns) })) }
        actions = TerminalHyperlinkActions()
        val range = SwingHyperlinkTextRange(SwingHyperlinkTextPosition(0, 0), SwingHyperlinkTextPosition(0, 1))
        repeat(4) { index ->
            val normal =
                SwingHyperlinkStyle(0xff336699.toInt() + index, 0xff112233.toInt(), 0xff445566.toInt(), TerminalRenderUnderline.SINGLE)
            val hover = SwingHyperlinkStyle(0xff669933.toInt() + index, underlineStyle = TerminalRenderUnderline.DOTTED)
            actions.retain(
                actions.add(
                    SwingHyperlink(
                        range,
                        range,
                        SwingHyperlinkAction.NONE,
                        presentation = SwingHyperlinkPresentation(normal, hover, hover, normal, isVisible = true),
                    ),
                ),
            )
        }
        for (index in cache.hyperlinkIds.indices) cache.hyperlinkIds[index] = -(index / 5 % 4 + 1)
        allocationBean = (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply { isThreadAllocatedMemoryEnabled = true }
        threadId = Thread.currentThread().threadId()
        repeat(32) { prepareNativeStyles() }
    }

    @Benchmark
    open fun prepareNativeStyles(): Int {
        phase = (phase + 1) and 3
        val hovered = -(phase + 1)
        actions.follow(-((phase + 1) % 4 + 1))
        var checksum = 0
        for (row in 0 until cache.rows) {
            run.configureRow(true, cache.hyperlinkIds, hovered, phase % 2 == 0, 0, actions)
            val offset = cache.rowOffset(row)
            var column = 0
            while (column < cache.columns) {
                run.begin(cache, cache.palette, offset, column)
                checksum = checksum xor run.foreground xor run.hyperlinkUnderlineColor
                val styleOffset = actions.styleOffset(run.hyperlinkId, run.hovered, phase % 2 == 0)
                checksum = checksum xor actions.background(styleOffset)
                check(actions.get(run.hyperlinkId) != null)
                column++
                while (column < cache.columns && run.matches(cache, cache.palette, offset, column)) column++
            }
        }
        return checksum
    }

    @Benchmark
    open fun countPreparedAllocations(counters: AllocationCounters): Int {
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        val result = prepareNativeStyles()
        counters.allocatedBytes += allocationBean.getThreadAllocatedBytes(threadId) - before
        return result
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    open class AllocationCounters {
        @JvmField var allocatedBytes = 0L
    }
}
