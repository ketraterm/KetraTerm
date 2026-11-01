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
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.render.TerminalShellIntegrationViewportDecorations
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.painter.TerminalShellIntegrationDecorationPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.openjdk.jmh.annotations.*
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/**
 * Stable prompt decoration repainting with warmed color caches and reused graphics/frame storage.
 * Each invocation paints normal and failed dots, a failure rail, and a divider. Includes Java2D
 * antialiasing allocations; overrides retain default alpha values so comparisons use the same
 * Java2D compositing paths under each hover state.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalPromptDecorationBenchmark {
    @Param("false", "true")
    @JvmField
    var customColors = false

    @Param("false", "true")
    @JvmField
    var hovered = false

    private lateinit var graphics: Graphics2D
    private lateinit var image: BufferedImage
    private lateinit var settings: SwingSettings
    private val painter = TerminalShellIntegrationDecorationPainter(AwtColorCache())
    private val metrics = SwingMetrics(8, 20, 14, 16, 8, 0, 1)
    private val decorations = TerminalShellIntegrationViewportDecorations()

    @Setup
    open fun setup() {
        settings =
            SwingSettings.create {
                if (customColors) {
                    it.shellIntegrationPromptDotHoverColor = 0x8cffffff.toInt()
                    it.shellIntegrationFailedPromptDotHoverColor = 0xffff8a8a.toInt()
                    it.shellIntegrationPromptDotHoverHaloColor = 0x524b6eaf
                    it.shellIntegrationFailedPromptDotHoverHaloColor = 0x52e74856
                    it.shellIntegrationPromptDividerColor = 0x304b6eaf
                    it.shellIntegrationPromptDividerHoverColor = 0x704b6eaf
                }
            }
        val state =
            TerminalShellIntegrationState().apply {
                recordPromptStart(1)
                recordCommandStart(1, includeLine = true)
                recordCommandFinished(1, exitCode = 0)
                recordPromptStart(2)
                recordCommandStart(3, includeLine = true)
                recordCommandFinished(3, exitCode = 1)
            }
        val cache =
            TerminalRenderCache(80, 3).apply {
                accept(TerminalRenderBenchmarkFrame(List(3) { " ".repeat(80) }))
            }
        decorations.updateFrom(state, cache)
        check(decorations.hasPromptStartAt(0) && decorations.hasPromptStartAt(1) && decorations.hasFailedCommandRailAt(2))
        image = BufferedImage(660, 100, BufferedImage.TYPE_INT_ARGB)
        graphics = image.createGraphics().apply { translate(20, 0) }
        repeat(32) { paintDecorations() }
    }

    @Benchmark
    open fun paintDecorations(): Int {
        for (row in 0..2) {
            painter.paint(graphics, settings, metrics, decorations, 20, row, hovered, settings.palette)
        }
        painter.paintDivider(graphics, settings, metrics, 4, 640, hovered, settings.palette, interactive = true)
        return image.getRGB(10, 10) xor image.getRGB(21, 69)
    }

    @TearDown
    open fun tearDown() {
        graphics.dispose()
    }
}
