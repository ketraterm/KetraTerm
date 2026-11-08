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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.*
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.painter.TerminalBackgroundPainter
import io.github.ketraterm.ui.swing.render.primitives.TerminalCellPrimitivePainter
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.viewport.SwingRepaintPlanner
import io.github.ketraterm.ui.swing.viewport.TerminalRepaintSink
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color
import java.awt.Insets
import java.awt.Rectangle
import java.awt.geom.Area
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalFractionalScalingTest {
    private val metrics = SwingMetrics(7, 13, 9, 11, 5, 0, 1)
    private val settings = defaultTestSettings().copy { it.shellIntegrationDecorationGutterWidth = 0 }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 1.75, 2.0, 2.25])
    fun `background cells share half-open device boundaries at every translation phase`(scale: Double) {
        val cells =
            Array(3) { row ->
                Array(4) { column ->
                    TestCell(
                        attr =
                            TerminalRenderAttrs.pack(
                                backgroundKind = TerminalRenderColorKind.RGB,
                                backgroundValue = if ((row + column) % 2 == 0) 0xff0000 else 0x00ff00,
                            ),
                    )
                }
            }
        val cache = renderCache(TestRenderFrame(cells))
        for (phase in listOf(0.0, 0.25, 0.5, 0.75)) {
            val image = BufferedImage(90, 110, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.translate(phase, phase)
                g.scale(scale, scale)
                val painter = TerminalBackgroundPainter(AwtColorCache())
                for (row in cells.indices) painter.paintRow(g, cache, settings.palette, metrics, row)
            } finally {
                g.dispose()
            }
            for (row in cells.indices) {
                for (column in cells[row].indices) {
                    val expected = if ((row + column) % 2 == 0) TEST_RED else TEST_GREEN
                    for (y in ceil(row * 13 * scale + phase - 0.5).toInt() until ceil((row + 1) * 13 * scale + phase - 0.5).toInt()) {
                        for (x in ceil(
                            column * 7 * scale + phase - 0.5,
                        ).toInt() until ceil((column + 1) * 7 * scale + phase - 0.5).toInt()) {
                            assertEquals(expected, image.getRGB(x, y), "cell=$column,$row pixel=$x,$y scale=$scale phase=$phase")
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 1.75, 2.0, 2.25])
    fun `image placement and clipping round once in device space`(scale: Double) {
        val resource =
            object : TerminalRenderImage {
                override val width = 1
                override val height = 1

                override fun copyArgb(
                    destination: IntArray,
                    offset: Int,
                ) {
                    destination[offset] = 0x80ff0000.toInt()
                }
            }
        val cache =
            renderCache(
                object : TestRenderFrame(Array(3) { Array(4) { TestCell() } }) {
                    override fun copyImages(sink: TerminalRenderImageSink) {
                        sink.accept(resource, 0.5, 0.5, 1.5, 1.0, 0, 0, 1, 1, 0.75, 1.5, 0)
                    }
                },
            )
        val painter = GraphicsPainter()
        painter.prepare(cache.images)
        try {
            for (phase in listOf(0.0, 0.5)) {
                val image = BufferedImage(90, 110, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                val verticalScale = scale + 0.25
                try {
                    g.translate(phase, phase - 0.35)
                    g.scale(scale, verticalScale)
                    painter.paint(g, cache.images, metrics, null, 2, 4, 3)
                } finally {
                    g.dispose()
                }
                val left = ceil(3.5 * scale + phase - 0.5).toInt()
                val right = ceil(14 * scale + phase - 0.5).toInt()
                val top = ceil(9.75 * verticalScale + phase - 0.35 - 0.5).toInt()
                val bottom = ceil(19.5 * verticalScale + phase - 0.35 - 0.5).toInt()
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val expected = if (x in left until right && y in top until bottom) 0x80ff0000.toInt() else 0
                        assertEquals(expected, image.getRGB(x, y), "scale=$scale phase=$phase pixel=$x,$y")
                    }
                }
            }
        } finally {
            painter.clear()
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.35, 1.57])
    fun `rotated and reflected backgrounds retain Java2D transform and clip semantics`(angle: Double) {
        val actual = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
        val expected = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
        for (image in listOf(actual, expected)) {
            val g = image.createGraphics()
            try {
                g.translate(40.0, 30.0)
                g.rotate(angle)
                g.scale(-1.25, 1.75)
                g.clip = Area(Rectangle(0, 0, 7, 13)).apply { subtract(Area(Rectangle(2, 3, 3, 5))) }
                if (image === expected) {
                    g.color = Color.RED
                    g.fillRect(0, 0, 7, 13)
                } else {
                    val palette = defaultTestSettings(background = TEST_RED).palette
                    TerminalBackgroundPainter(AwtColorCache()).paintRow(
                        g,
                        renderCache(TestRenderFrame(arrayOf(arrayOf(TestCell())))),
                        palette,
                        metrics,
                        0,
                    )
                }
            } finally {
                g.dispose()
            }
        }
        assertPixelsEqual(expected, actual, "angle=$angle")
    }

    @ParameterizedTest
    @CsvSource(
        "1.0,1",
        "1.0,2",
        "1.0,3",
        "1.25,1",
        "1.25,2",
        "1.25,3",
        "1.5,1",
        "1.5,2",
        "1.5,3",
        "1.75,1",
        "1.75,2",
        "1.75,3",
        "2.0,1",
        "2.0,2",
        "2.0,3",
        "2.25,1",
        "2.25,2",
        "2.25,3",
    )
    fun `cursor blink and movement damage reproduce full renders with wide text and overlays`(
        scale: Double,
        cursorColumn: Int,
    ) {
        val rows = arrayOf("A\u2588\u2500\u05d0\u05d1", "\u2588A\u2500\u05d1\u05d0", "B\u2588\u2500\u05d0\u05d1")
        val cells =
            Array(3) { row ->
                Array(5) { column ->
                    TestCell(
                        codeWord = rows[row][column].code,
                        flags = TerminalRenderCellFlags.CODEPOINT,
                        attr =
                            TerminalRenderAttrs.pack(
                                backgroundKind = TerminalRenderColorKind.RGB,
                                backgroundValue = 0x152433,
                                underlineStyle = TerminalRenderUnderline.SINGLE,
                            ),
                    )
                }
            }
        cells[1][1] =
            cells[1][1].copy(
                codeWord = 0x4f60,
                flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
            )
        cells[1][2] = cells[1][2].copy(codeWord = 0, flags = TerminalRenderCellFlags.WIDE_TRAILING)
        cells[2][1] =
            cells[2][1].copy(
                codeWord = 0,
                flags = TerminalRenderCellFlags.CLUSTER,
                cluster = "e\u0301",
            )
        val selection = CellSelection(0, 0, 5, 2)
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(3)
                for (row in 0..2) add(row, 1, 4, false)
                finish()
            }
        for (origin in listOf(0.0, -0.35, -3.5)) {
            for (phase in listOf(0.0, 0.5)) {
                for (shape in TerminalRenderCursorShape.entries) {
                    for (focused in listOf(false, true)) {
                        val cursor = TerminalRenderCursor(cursorColumn, 1, true, true, shape, 1)
                        val cache = renderCache(TestRenderFrame(cells, cursor))
                        val geometry =
                            TerminalVisualViewportGeometry().apply {
                                updateLayout(metrics, 3, 39)
                                updateContentOrigin(origin)
                            }
                        val painter = GridPainter(cellGeometry = geometry.bidiLayout)
                        val damage = ArrayList<Rectangle>()
                        val sink =
                            object : TerminalRepaintSink {
                                override fun requestFullRepaint() {
                                    damage.add(Rectangle(0, 0, 42, 39))
                                }

                                override fun requestRegionRepaint(
                                    x: Int,
                                    y: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    damage.add(Rectangle(x, y, width, height))
                                }
                            }
                        val planner = SwingRepaintPlanner()
                        planner.requestFrameRepaint(
                            cache,
                            metrics,
                            42,
                            39,
                            Insets(0, 0, 0, 0),
                            sink,
                            visualGeometry = geometry,
                            searchHighlights = highlights,
                        )
                        damage.clear()
                        val actual = BufferedImage(110, 110, BufferedImage.TYPE_INT_ARGB)
                        val expected = BufferedImage(110, 110, BufferedImage.TYPE_INT_ARGB)

                        fun paint(
                            image: BufferedImage,
                            blink: Boolean,
                            clip: Rectangle? = null,
                        ) {
                            val g = image.createGraphics()
                            try {
                                g.translate(phase, phase)
                                g.scale(scale, scale)
                                g.clip = clip
                                painter.paint(
                                    g,
                                    cache,
                                    settings,
                                    metrics,
                                    42,
                                    39,
                                    blink,
                                    terminalFocused = focused,
                                    visualGeometry = geometry,
                                    selection = selection,
                                    searchHighlights = highlights,
                                )
                            } finally {
                                g.dispose()
                            }
                        }
                        paint(actual, true)
                        paint(expected, true)
                        assertPixelsEqual(
                            expected,
                            actual,
                            "initial scale=$scale phase=$phase origin=$origin shape=$shape focused=$focused",
                        )
                        planner.requestCursorBlinkRepaint(cache, metrics, 42, 39, Insets(0, 0, 0, 0), sink, geometry)
                        assertTrue(damage.isNotEmpty())
                        for (clip in damage) paint(actual, false, clip)
                        paint(expected, false)
                        val context = "scale=$scale phase=$phase origin=$origin shape=$shape focused=$focused column=$cursorColumn"
                        assertPixelsEqual(expected, actual, context)
                        // Move first onto a combining cluster, then into a bidi row.
                        for ((column, row) in listOf(1 to 2, 4 to 0)) {
                            damage.clear()
                            cache.updateFrom(TestRenderFrame(cells, cursor.copy(column = column, row = row, generation = 2L + row)))
                            planner.requestFrameRepaint(
                                cache,
                                metrics,
                                42,
                                39,
                                Insets(0, 0, 0, 0),
                                sink,
                                visualGeometry = geometry,
                                searchHighlights = highlights,
                            )
                            assertTrue(damage.isNotEmpty())
                            for (clip in damage) paint(actual, true, clip)
                            paint(expected, true)
                            assertPixelsEqual(expected, actual, "$context moved=$column,$row damage=$damage")
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.25, 1.5, 1.75])
    fun `primitive painting preserves holes in caller clips`(scale: Double) {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.scale(scale, scale)
            val clip = Area(Rectangle(0, 0, 7, 13)).apply { subtract(Area(Rectangle(2, 3, 3, 5))) }
            g.clip = clip
            g.color = Color.RED
            TerminalCellPrimitivePainter().paint(g, 0x2588, 0, 0, metrics)
            assertEquals(0, image.getRGB((3 * scale).toInt(), (5 * scale).toInt()), "Clip hole was overwritten")
            assertEquals(TEST_RED, image.getRGB(0, 0))
        } finally {
            g.dispose()
        }
    }

    private fun assertPixelsEqual(
        expected: BufferedImage,
        actual: BufferedImage,
        context: String,
    ) {
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "$context pixel=$x,$y")
            }
        }
    }
}
