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
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.render.cache.TextContrastCache
import io.github.ketraterm.ui.swing.render.painter.TerminalTextRunStyle
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.openjdk.jmh.annotations.*
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Color resolution only; Java2D painting and its platform allocations are measured separately. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalColorResolutionBenchmark {
    @Param("1.0", "4.5")
    @JvmField
    var ratio = 1.0

    @Param("false", "true")
    @JvmField
    var overlays = false

    private lateinit var cache: TerminalRenderCache
    private lateinit var settings: SwingSettings
    private val style = TerminalTextRunStyle()
    private val contrast = TextContrastCache()
    private val search = TerminalSearchViewportHighlights()
    private var sequence = 0
    private lateinit var allocationBean: ThreadMXBean
    private var threadId = 0L

    @Setup
    open fun setup() {
        cache = TerminalRenderCache(80, 1).apply { accept(TerminalRenderBenchmarkFrame(listOf("A".repeat(80)))) }
        cache.attrWords.fill(
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.RGB,
                foregroundValue = 0x666666,
                faint = true,
            ),
        )
        settings =
            SwingSettings.create {
                it.minimumContrastRatio = ratio
                it.searchMatchStyle = SwingSearchMatchStyle(0x55ffd54f, 0xff123456.toInt(), 0xffffd54f.toInt())
                it.searchActiveMatchStyle = SwingSearchMatchStyle(0xaaff8c00.toInt(), 0xfffefefe.toInt(), 0xffff8c00.toInt())
            }
        style.prepareColumns(80)
        search.reset(1)
        search.add(0, 20, 40, false)
        search.add(0, 40, 60, true)
        search.finish()
        contrast.configure(ratio)
        allocationBean = (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply { isThreadAllocatedMemoryEnabled = true }
        threadId = Thread.currentThread().threadId()
        repeat(32) { resolveRowStyles() }
    }

    @Benchmark
    @OperationsPerInvocation(80)
    open fun resolveRowStyles(): Int {
        style.configureRow(
            true,
            cache.hyperlinkIds,
            null,
            settings,
            selectionRange = if (overlays) CellSelection.packRange(30, 50) else CellSelection.NO_RANGE,
            searchHighlights = if (overlays) search else null,
            columns = 80,
        )
        var checksum = 0
        for (column in 0 until 80) {
            style.begin(cache, cache.palette, 0, column)
            checksum = checksum xor style.foreground
        }
        return checksum
    }

    @Benchmark
    @OperationsPerInvocation(80)
    open fun resolveChangingTruecolor(): Int {
        var checksum = 0
        repeat(80) {
            val color = 0xff000000.toInt() or (++sequence and 0xffffff)
            checksum = checksum xor contrast.resolve(color, 0xff222222.toInt())
        }
        return checksum
    }

    @Benchmark
    @OperationsPerInvocation(160)
    open fun countColorAllocations(counters: AllocationCounters): Int {
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        val checksum = resolveRowStyles() xor resolveChangingTruecolor()
        counters.allocatedBytes += allocationBean.getThreadAllocatedBytes(threadId) - before
        return checksum
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    open class AllocationCounters {
        @JvmField var allocatedBytes = 0L
    }
}
