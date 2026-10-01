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
import io.github.ketraterm.render.api.TerminalRenderClusterDataSink
import io.github.ketraterm.render.api.TerminalRenderClusterSink
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.SwingHyperlinkAction
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkDetectionAccumulator
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkIndex
import io.github.ketraterm.ui.swing.api.detectionRequest
import org.openjdk.jmh.annotations.*
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Prepared dense history: frame copy, scrolling projection and action lookup, without discovery. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalHyperlinkProjectionBenchmark {
    @Param("80x24", "160x48", "240x48")
    @JvmField
    var grid: String = "80x24"

    @Param("1000", "10000")
    @JvmField
    var retainedRows: Int = 1000

    private lateinit var index: TerminalHyperlinkIndex
    private lateinit var cache: TerminalRenderCache
    private lateinit var frame: ScrollingFrame
    private val repaint: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    private lateinit var allocationBean: ThreadMXBean
    private var threadId = 0L

    @Setup(Level.Trial)
    open fun setup() {
        allocationBean =
            (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply {
                check(isThreadAllocatedMemorySupported)
                isThreadAllocatedMemoryEnabled = true
            }
        threadId = Thread.currentThread().threadId()
        val (columns, rows) = grid.split('x').map(String::toInt)
        val source = TerminalRenderBenchmarkFrame(List(retainedRows) { "$URL row $it".padEnd(columns) })
        val full = TerminalRenderCache(columns, retainedRows).apply { accept(source) }
        index = TerminalHyperlinkIndex()
        index.update(full)
        val lines = index.pendingLines()
        val request = detectionRequest(lines)
        val sink = TerminalHyperlinkDetectionAccumulator(lines)
        for (line in lines.indices) {
            sink.addHyperlink(request.hyperlink(line, 0, URL.length, SwingHyperlinkAction.NONE, uri = URL))
        }
        index.accept(lines, sink.links)
        frame = ScrollingFrame(source, rows)
        cache = TerminalRenderCache(columns, rows)
        repeat(2) { scrollPreparedHistory() }
    }

    @Benchmark
    open fun scrollPreparedHistory(): Int {
        frame.top = (frame.top + 37) % (retainedRows - frame.rows + 1)
        frame.generation++
        cache.accept(frame)
        index.update(cache)
        index.writeOverlay(cache, repaint)
        val id = index.idsFor(cache)[0]
        check(index.hyperlinkFor(id, cache)?.uri == URL)
        return id
    }

    /** Per-owner allocation accounting excludes JMH/profiler activity on other threads. */
    @Benchmark
    open fun countPreparedAllocations(counters: AllocationCounters): Int {
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        val id = scrollPreparedHistory()
        counters.allocatedBytes += allocationBean.getThreadAllocatedBytes(threadId) - before
        return id
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    open class AllocationCounters {
        @JvmField var allocatedBytes = 0L
    }

    private class ScrollingFrame(
        private val source: TerminalRenderFrame,
        override val rows: Int,
    ) : TerminalRenderFrame by source {
        var top = 0
        var generation = 1L
        override val historySize: Int get() = source.rows - rows
        override val scrollbackOffset: Int get() = historySize - top
        override val frameGeneration: Long get() = generation
        override val historyContentGeneration: Long = source.historyContentGeneration

        override fun lineId(row: Int): Long = source.lineId(top + row)

        override fun lineGeneration(row: Int): Long = source.lineGeneration(top + row)

        override fun lineWrapped(row: Int): Boolean = source.lineWrapped(top + row)

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
        ) = source.copyLine(
            top + row,
            codeWords,
            codeOffset,
            attrWords,
            attrOffset,
            flags,
            flagOffset,
            extraAttrWords,
            extraAttrOffset,
            hyperlinkIds,
            hyperlinkOffset,
            clusterSink,
            clusterDataSink,
        )
    }

    private companion object {
        const val URL = "https://example.invalid/prepared"
    }
}
