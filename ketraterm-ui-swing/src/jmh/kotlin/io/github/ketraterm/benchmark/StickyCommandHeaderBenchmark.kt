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
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderRowSnapshot
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.render.StickyCommandHeader
import io.github.ketraterm.ui.swing.render.TerminalVisualViewportGeometry
import io.github.ketraterm.ui.swing.settings.*
import org.openjdk.jmh.annotations.*
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Owner-thread overlay updates and painting. Command transitions include cold raster preparation. */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class StickyCommandHeaderBenchmark {
    @Param("hidden", "ascii", "unicode")
    @JvmField
    var workload = "ascii"
    private val state = TerminalShellIntegrationState()
    private val replacement = TerminalShellIntegrationState()
    private val cache = TerminalRenderCache(80, 24)
    private val geometry = TerminalVisualViewportGeometry()
    private val header = StickyCommandHeader(SwingTerminalChrome())
    private val metrics = SwingMetrics(8, 20, 14, 16, 8, 0, 1)
    private lateinit var settings: SwingSettings
    private lateinit var graphics: Graphics2D
    private lateinit var image: BufferedImage
    private lateinit var allocations: ThreadMXBean
    private var threadId = 0L
    private var tick = 0

    @Setup
    open fun setup() {
        settings =
            SwingSettings.create {
                it.promptDecoration = SwingPromptDecoration.NONE
                it.padding = SwingPadding(0, 0, 0, 0)
                it.useSystemFallbackFonts = false
                it.stickyCommandHeadersEnabled = workload != "hidden"
            }
        val text = if (workload == "unicode") "echo e\u0301 \uD83D\uDC69\u200D\uD83D\uDCBB العربية" else "./gradlew build --console=plain"
        val prompt = TerminalRenderRowSnapshot.copyOf(TerminalRenderBenchmarkFrame(listOf("PS C:\\work> $text".padEnd(80))), 0)
        val nextPrompt = TerminalRenderRowSnapshot.copyOf(TerminalRenderBenchmarkFrame(listOf("PS C:\\work> second $text".padEnd(80))), 0)
        state.recordCommandStart(1, true, text, null, prompt)
        replacement.recordCommandStart(1, true, "second $text", null, nextPrompt)
        val prepared = TerminalRenderBenchmarkFrame(List(24) { "output".padEnd(80) })
        cache.accept(
            object : TerminalRenderFrame by prepared {
                override fun lineId(row: Int): Long = row + 11L
            },
        )
        geometry.updateLayout(metrics, 24, 480)
        image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        graphics = image.createGraphics()
        allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        allocations.isThreadAllocatedMemoryEnabled = true
        threadId = Thread.currentThread().threadId()
        repeat(256) { fractionalScroll() }
        check(header.visible == (workload != "hidden"))
    }

    @Benchmark
    open fun fractionalScroll(): Int {
        geometry.updateContentOrigin(-((tick++ and 15) / 16.0))
        header.update(state, cache, geometry, settings, metrics, 640, 480, null)
        return header.recordId
    }

    @Benchmark
    @OperationsPerInvocation(128)
    open fun countUpdateAllocations(counters: AllocationCounters): Int {
        val before = allocations.getThreadAllocatedBytes(threadId)
        var checksum = 0
        repeat(128) { checksum = checksum xor fractionalScroll() }
        counters.allocatedBytes += allocations.getThreadAllocatedBytes(threadId) - before
        return checksum
    }

    @Benchmark
    open fun paint(): Int {
        header.paint(graphics)
        return image.getRGB(600, 2)
    }

    @Benchmark
    open fun commandTransition(): Int {
        val source = if (tick++ and 1 == 0) state else replacement
        header.update(source, cache, geometry, settings, metrics, 640, 480, null)
        return header.recordId
    }

    @TearDown
    open fun tearDown() {
        graphics.dispose()
        header.reset()
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    open class AllocationCounters {
        @JvmField var allocatedBytes = 0L
    }
}
