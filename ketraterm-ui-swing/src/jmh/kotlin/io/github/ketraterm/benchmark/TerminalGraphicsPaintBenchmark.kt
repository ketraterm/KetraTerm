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
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.render.GraphicsPainter
import io.github.ketraterm.ui.swing.render.PromptDividerLayout
import io.github.ketraterm.ui.swing.render.TerminalVisualViewportGeometry
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.openjdk.jmh.annotations.*
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Warmed image preparation and Java2D composition on one thread-owned software surface.
 * Upload, publication, raster construction and capacity growth happen in setup;
 * the measured operation includes reused-raster lookup, background clear and all three
 * image bands. The scroll workloads additionally copy alternating live/history
 * viewports so the existing image leaves and reenters view on every invocation.
 * It excludes Swing event dispatch and text/cursor painting. Java2D's
 * own scaling/clipping allocations remain visible to the GC profiler.
 *
 * @property workload opacity and geometry: one 60-row native/scaled image, two prompt
 * dividers within that image, 4,096 scaled one-cell placements sharing one resource,
 * or alternating empty/image scrollback viewports.
 * @property contentOriginY vertical translation in pixels, including fractional scrolling.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class TerminalGraphicsPaintBenchmark {
    @Param(
        "empty",
        "opaque-native",
        "opaque-scaled",
        "translucent-native",
        "translucent-scaled",
        "opaque-dividers",
        "translucent-dividers",
        "opaque-dense",
        "translucent-dense",
        "opaque-scroll",
        "translucent-scroll",
    )
    var workload: String = "empty"

    @Param("0.0", "-0.5")
    var contentOriginY: Double = 0.0
    private lateinit var terminal: TerminalRenderBuffer
    private lateinit var cache: TerminalRenderCache
    private lateinit var painter: GraphicsPainter
    private lateinit var geometry: TerminalVisualViewportGeometry
    private lateinit var canvas: BufferedImage
    private lateinit var graphics: Graphics2D
    private lateinit var pixels: IntArray
    private var scrolling = false
    private var scrollbackOffset = 0

    /** Builds a stable placement set and prepares reusable Java2D rasters before measurement. */
    @Setup
    fun setup() {
        terminal = TerminalBuffers.create(COLUMNS, ROWS)
        terminal.resize(COLUMNS, ROWS, 0, COLUMNS * METRICS.cellWidth, ROWS * METRICS.cellHeight)
        val dense = workload.endsWith("dense")
        scrolling = workload.endsWith("scroll")
        val native = workload.endsWith("native") || workload.endsWith("dividers") || scrolling
        val withDividers = workload.endsWith("dividers")
        val translucent = workload.startsWith("translucent")
        val placements =
            if (workload == "empty") {
                0
            } else if (dense) {
                4096
            } else {
                1
            }
        if (placements != 0) {
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            val imageWidth = if (native) METRICS.cellWidth else 1
            val imageHeight = if (native) ROWS * METRICS.cellHeight else 1
            val rgba = ByteArray(imageWidth * imageHeight * 4)
            for (i in rgba.indices step 4) {
                rgba[i + 1] = 255.toByte()
                rgba[i + 3] = if (translucent) 128.toByte() else 255.toByte()
            }
            parser.accept(
                "\u001b_Ga=t,i=1,f=32,s=$imageWidth,v=$imageHeight,q=2;${Base64.getEncoder().encodeToString(rgba)}\u001b\\"
                    .encodeToByteArray(),
            )
            repeat(placements) { index ->
                parser.accept(
                    (
                        "\u001b[${index / COLUMNS + 1};${index % COLUMNS + 1}H" +
                            "\u001b_Ga=p,i=1,p=${index + 1},C=1,c=1,r=${if (dense) 1 else ROWS},q=2;\u001b\\"
                    ).encodeToByteArray(),
                )
            }
        }
        cache = TerminalRenderCache(COLUMNS, ROWS).apply { updateFrom(terminal) }
        check(cache.images.size == placements)
        val dividers =
            if (withDividers) {
                val state = TerminalShellIntegrationState()
                terminal.readRenderFrame { frame ->
                    state.recordPromptStart(frame.lineId(20))
                    state.recordPromptStart(frame.lineId(40))
                }
                PromptDividerLayout().apply { updateFrom(terminal, state, ROWS) }
            } else {
                null
            }
        val canvasHeight = (ROWS + if (withDividers) 2 else 0) * METRICS.cellHeight
        geometry =
            TerminalVisualViewportGeometry().apply {
                updateLayout(METRICS, ROWS, canvasHeight, dividers)
                updateContentOrigin(contentOriginY)
            }
        canvas = BufferedImage(COLUMNS * METRICS.cellWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
        pixels = (canvas.raster.dataBuffer as DataBufferInt).data
        graphics = canvas.createGraphics().apply { setClip(0, 0, canvas.width, canvas.height) }
        painter = GraphicsPainter()
        if (scrolling) {
            terminal.scrollUp(ROWS)
            check(paint() == if (translucent) 0xff00807f.toInt() else Color.GREEN.rgb)
            check(paint() == Color.BLUE.rgb)
        }
        repeat(20) { paint() }
        check(
            pixels[0] ==
                if (placements == 0 || scrolling) {
                    Color.BLUE.rgb
                } else if (translucent) {
                    0xff00807f.toInt()
                } else {
                    Color.GREEN.rgb
                },
        )
    }

    /**
     * Clears and composes one frame using already prepared resource identities.
     *
     * @return a painted pixel read directly from the software surface without a wrapper allocation.
     */
    @Benchmark
    fun paint(): Int {
        if (scrolling) {
            scrollbackOffset = ROWS - scrollbackOffset
            cache.updateFrom(terminal, scrollbackOffset)
        }
        painter.prepare(cache.images)
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, canvas.width, canvas.height)
        graphics.translate(0.0, contentOriginY)
        try {
            for (band in 0..2) painter.paint(graphics, cache.images, METRICS, geometry, band, COLUMNS, ROWS)
        } finally {
            graphics.translate(0.0, -contentOriginY)
        }
        return pixels[0]
    }

    /** Releases the trial's software surface and raster references. */
    @TearDown
    fun close() {
        painter.clear()
        graphics.dispose()
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 60
        val METRICS = SwingMetrics(8, 16, 12, 14, 8, 0, 1)
    }
}
