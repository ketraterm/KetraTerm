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
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkController
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkHost
import org.openjdk.jmh.annotations.*
import java.awt.Cursor
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Dense, disconnected groups: primitive hit testing, hover projection, modifier changes and repaint preparation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalHyperlinkHoverBenchmark {
    @Param("80x24", "160x48", "240x48")
    @JvmField
    var grid = "80x24"

    private lateinit var cache: TerminalRenderCache
    private lateinit var controller: TerminalHyperlinkController
    private lateinit var allocationBean: ThreadMXBean
    private var threadId = 0L
    private var column = 0
    private var repaintChecksum = 0

    @Setup(Level.Trial)
    open fun setup() {
        val (columns, rows) = grid.split('x').map(String::toInt)
        cache = TerminalRenderCache(columns, rows)
        for (index in cache.hyperlinkIds.indices) {
            // Three groups separated by unlinked cells; negative IDs exercise the same occurrence contract.
            cache.hyperlinkIds[index] = if (index % 4 == 3) 0 else -(index % 4 + 1)
        }
        controller =
            TerminalHyperlinkController(
                object : TerminalHyperlinkHost {
                    override val renderCache: TerminalRenderCache get() = cache
                    override var cursor: Cursor = Cursor.getDefaultCursor()

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = (x.toLong() shl 32) or y.toLong()

                    override fun hyperlinkIdAt(
                        row: Int,
                        column: Int,
                    ): Int = cache.hyperlinkIds[cache.rowOffset(row) + column]

                    override fun isHyperlinkResolvable(hyperlinkId: Int): Boolean = hyperlinkId != 0

                    override fun openHyperlink(hyperlinkId: Int): Boolean = hyperlinkId != 0

                    override fun repaintHyperlinkSpan(
                        startRow: Int,
                        startColumn: Int,
                        endRow: Int,
                        endColumn: Int,
                    ) {
                        repaintChecksum = repaintChecksum xor (startRow + startColumn + endRow + endColumn)
                    }
                },
            )
        allocationBean =
            (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply {
                check(isThreadAllocatedMemorySupported)
                isThreadAllocatedMemoryEnabled = true
            }
        threadId = Thread.currentThread().threadId()
        repeat(16) { hoverPreparedGroups() }
    }

    @Benchmark
    open fun hoverPreparedGroups(): Int {
        column = (column + 1) % 3
        controller.updatePointerPosition(column, 0)
        controller.updateHyperlinkActivationHover(true)
        controller.updateHyperlinkActivationHover(false)
        controller.refreshHyperlinkHover()
        return controller.hoveredHyperlinkId xor repaintChecksum
    }

    /** Measures only the owner thread; the host records repaint requests without invoking AWT. */
    @Benchmark
    open fun countPreparedAllocations(counters: AllocationCounters): Int {
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        val result = hoverPreparedGroups()
        counters.allocatedBytes += allocationBean.getThreadAllocatedBytes(threadId) - before
        return result
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    open class AllocationCounters {
        @JvmField var allocatedBytes = 0L
    }
}
