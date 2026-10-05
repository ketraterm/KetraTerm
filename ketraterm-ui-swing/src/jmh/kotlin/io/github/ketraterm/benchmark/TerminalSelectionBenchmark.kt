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

import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.TerminalSelectionController
import io.github.ketraterm.ui.swing.api.TerminalSelectionHost
import io.github.ketraterm.ui.swing.api.TerminalSelectionRange
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Measures selection reconciliation and snapshot reuse, excluding Swing dispatch, cache copying, and painting. */
@Suppress("unused")
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalSelectionBenchmark {
    @Param("false", "true")
    var selected: Boolean = false

    private lateinit var cache: TerminalRenderCache
    private lateinit var controller: TerminalSelectionController
    private var notifications = 0

    @Setup
    fun setup() {
        val frame = TerminalRenderBenchmarkFrame(List(24) { "x".repeat(80) })
        cache = TerminalRenderCache(80, 24)
        cache.accept(frame)
        controller =
            TerminalSelectionController(
                object : TerminalSelectionHost {
                    override val settings: SwingSettings get() = error("not used")
                    override val metrics: SwingMetrics get() = error("not used")
                    override val renderCache: TerminalRenderCache get() = cache
                    override val contentYOffset: Double = 0.0
                    override val componentWidth: Int = 800
                    override val componentHeight: Int = 480

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = error("not used")

                    override fun visualCellAt(
                        x: Int,
                        y: Int,
                    ): Long = error("not used")

                    override fun scrollViewportByRows(deltaRows: Int): Boolean = error("not used")

                    override fun copySelection(): Unit = error("not used")

                    override fun repaint() = Unit

                    override fun requestFocusInWindow(): Boolean = error("not used")
                },
            )
        controller.updateFrame(cache)
        controller.addListener { _, _ -> notifications++ }
        if (selected) {
            val range = requireNotNull(controller.createRange(frame, 0, 0, 80, 23, false))
            check(controller.setRange(range, frame))
        }
        controller.publishChange()
    }

    @Benchmark
    fun reconcileUnchangedSelection(): TerminalSelectionRange? {
        controller.updateFrame(cache)
        controller.publishChange()
        return controller.currentRange()
    }

    @TearDown
    fun verify() {
        check(notifications == if (selected) 1 else 0)
    }
}
