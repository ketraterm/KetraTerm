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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.render.api.TerminalRenderImageSink
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.Transparency
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class GraphicsRenderingTest {
    @Test
    fun `retired versions leave the raster cache without invalidating installed pinned frames`() =
        onEdt {
            val terminal = TerminalBuffers.create(10, 4)
            terminal.resize(10, 4, 0, 80, 64)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))

            fun upload(payload: String) = parser.accept("\u001b_Ga=T,i=7,f=24,s=1,v=1,C=1;$payload\u001b\\".encodeToByteArray())

            fun frame() = TerminalRenderCache(10, 4).apply { updateFrom(terminal as TerminalRenderFrameReader) }

            upload("/wAA")
            val oldFrame = frame()
            val oldImage = oldFrame.images.image(0)
            assertTrue(oldImage.isRetainedBySource)
            val rasters = GraphicsRasterCache()
            try {
                rasters.prepare(oldFrame.images)
                val oldRaster = rasters.image(0)
                upload("AP8A")
                val newFrame = frame()
                assertFalse(oldImage.isRetainedBySource)

                // Retirement is a cache hint; an installed old frame remains drawable.
                rasters.prepare(oldFrame.images)
                assertSame(oldRaster, rasters.image(0))
                assertEquals(Color.RED.rgb, rasters.image(0).getRGB(0, 0))

                rasters.prepare(newFrame.images)
                val newRaster = rasters.image(0)
                assertEquals(Color.GREEN.rgb, newRaster.getRGB(0, 0))
                rasters.prepare(oldFrame.images)
                assertNotSame(oldRaster, rasters.image(0))
                rasters.prepare(newFrame.images)
                assertSame(newRaster, rasters.image(0))

                parser.accept("\u001b_Ga=d,d=I,i=7;\u001b\\".encodeToByteArray())
                assertFalse(newFrame.images.image(0).isRetainedBySource)
                rasters.prepare(frame().images)
                rasters.prepare(newFrame.images)
                assertNotSame(newRaster, rasters.image(0))
                assertEquals(Color.GREEN.rgb, rasters.image(0).getRGB(0, 0))
            } finally {
                rasters.clear()
            }
        }

    @Test
    fun `scrolling resources completely out of view and back reuses their rasters`() =
        onEdt {
            val red = image(1, 1, intArrayOf(Color.RED.rgb))
            val blue = image(1, 1, intArrayOf(Color.BLUE.rgb))
            val first = imageCache(red)
            val second = imageCache(blue)
            val empty = imageCache()
            val rasters = GraphicsRasterCache()
            try {
                rasters.prepare(first.images)
                val redRaster = rasters.image(0)
                rasters.prepare(second.images)
                val blueRaster = rasters.image(0)
                repeat(3) {
                    rasters.prepare(empty.images)
                    rasters.prepare(first.images)
                    assertSame(redRaster, rasters.image(0))
                    rasters.prepare(second.images)
                    assertSame(blueRaster, rasters.image(0))
                }
            } finally {
                rasters.clear()
            }
        }

    @Test
    fun `raster byte pressure evicts offscreen resources without replacing visible ones`() =
        onEdt {
            fun large(color: Int) =
                object : TerminalRenderImage {
                    override val width = 4096
                    override val height = 2048

                    override fun copyArgb(
                        destination: IntArray,
                        offset: Int,
                    ) {
                        destination.fill(color, offset, offset + width * height)
                    }
                }
            val first = large(Color.RED.rgb)
            val visible = large(Color.BLUE.rgb)
            val incoming = image(1, 1, intArrayOf(Color.GREEN.rgb))
            val both = imageCache(first, visible)
            val rasters = GraphicsRasterCache()
            try {
                rasters.prepare(both.images)
                val firstRaster = rasters.image(0)
                val visibleRaster = rasters.image(1)
                rasters.prepare(imageCache(incoming, visible).images)
                assertEquals(Color.GREEN.rgb, rasters.image(0).getRGB(0, 0))
                assertSame(visibleRaster, rasters.image(1))
                rasters.prepare(both.images)
                assertNotSame(firstRaster, rasters.image(0))
                assertEquals(Color.RED.rgb, rasters.image(0).getRGB(0, 0))
                assertSame(visibleRaster, rasters.image(1))
            } finally {
                rasters.clear()
            }
        }

    @Test
    fun `raster record pressure is bounded and protects all current placements`() =
        onEdt {
            val resources = Array(TerminalGraphicsLimits.IMAGES) { image(1, 1, intArrayOf(Color.RED.rgb)) }
            val rasters = GraphicsRasterCache()
            try {
                rasters.prepare(imageCache(*resources).images)
                val firstRaster = rasters.image(0)
                val lastRaster = rasters.image(resources.lastIndex)
                val incoming = image(1, 1, intArrayOf(Color.BLUE.rgb))
                rasters.prepare(imageCache(incoming, resources.last()).images)
                assertEquals(Color.BLUE.rgb, rasters.image(0).getRGB(0, 0))
                assertSame(lastRaster, rasters.image(1))
                rasters.prepare(imageCache(resources.first(), resources.last()).images)
                assertNotSame(firstRaster, rasters.image(0))
                assertSame(lastRaster, rasters.image(1))

                assertFailsWith<IllegalArgumentException> {
                    rasters.prepare(imageCache(*resources, incoming).images)
                }
                // Failed preparation clears the partial cache and permits a valid retry.
                rasters.prepare(imageCache(incoming).images)
                assertEquals(Color.BLUE.rgb, rasters.image(0).getRGB(0, 0))
            } finally {
                rasters.clear()
            }
        }

    @Test
    fun `opaque raster views preserve samples and share storage across placements without losing alpha`() =
        onEdt {
            for (pixels in listOf(
                intArrayOf(0xff000001.toInt(), 0xff123456.toInt(), 0xffffffff.toInt()),
                intArrayOf(0x00112233, 0x0180ff02, 0x80ff0080.toInt()),
            )) {
                val resource = image(3, 1, pixels)
                val frame =
                    object : TestRenderFrame(Array(2) { Array(4) { TestCell() } }) {
                        override fun copyImages(sink: TerminalRenderImageSink) {
                            repeat(2) { row ->
                                sink.accept(resource, 0.0, row.toDouble(), 3.0, 1.0, 0, 0, 3, 1, 0.0, 2.0, 0)
                            }
                        }
                    }
                val cache = renderCache(frame)
                val rasters = GraphicsRasterCache()
                rasters.prepare(cache.images)
                val raster = rasters.image(0)
                assertSame(raster, rasters.image(1))
                assertEquals(if (pixels[0] ushr 24 == 255) Transparency.OPAQUE else Transparency.TRANSLUCENT, raster.transparency)
                for (x in pixels.indices) assertEquals(pixels[x], raster.getRGB(x, 0), "pixel=$x")
                rasters.prepare(cache.images)
                assertSame(raster, rasters.image(0))
                rasters.clear()
            }
        }

    @Test
    fun `fractional placement clipping preserves source sampling alpha and caller clip during scrolling`() =
        onEdt {
            val pixels = intArrayOf(0x80ff0000.toInt(), 0x8000ff00.toInt(), 0x80ffffff.toInt(), 0x80ffff00.toInt())
            val resource = image(1, 4, pixels)
            val source = BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB)
            source.setRGB(0, 0, 1, 4, pixels, 0, 1)
            val frame =
                object : TestRenderFrame(Array(4) { Array(4) { TestCell() } }) {
                    override fun copyImages(sink: TerminalRenderImageSink) {
                        sink.accept(resource, 0.5, 0.25, 1.5, 2.5, 0, 0, 1, 4, 0.75, 2.25, 0)
                    }
                }
            val cache = renderCache(frame)
            val painter = GraphicsPainter()
            painter.prepare(cache.images)
            try {
                for (callerClip in listOf(Rectangle(2, 0, 4, 16), null)) {
                    for (origin in listOf(-0.5, 0.0, 0.5)) {
                        val actual = BufferedImage(8, 16, BufferedImage.TYPE_INT_ARGB)
                        val expected = BufferedImage(8, 16, BufferedImage.TYPE_INT_ARGB)
                        val g = actual.createGraphics()
                        val reference = expected.createGraphics()
                        try {
                            for (graphics in listOf(g, reference)) {
                                graphics.color = Color.BLUE
                                graphics.fillRect(0, 0, 8, 16)
                                graphics.translate(0.0, origin)
                                graphics.clip = callerClip
                            }
                            // One scaled source crop, clipped after scaling; fractional source
                            // boundaries must not be rounded to whole source pixels.
                            reference.clipRect(0, 3, 8, 6)
                            reference.drawImage(source, 1, 1, 4, 11, 0, 0, 1, 4, null)
                            painter.paint(g, cache.images, IMAGE_METRICS, null, 2, 4, 4)
                            assertEquals(callerClip, g.clipBounds)
                            assertContentEquals(
                                expected.getRGB(0, 0, 8, 16, null, 0, 8),
                                actual.getRGB(0, 0, 8, 16, null, 0, 8),
                                "origin=$origin clip=$callerClip",
                            )
                        } finally {
                            g.dispose()
                            reference.dispose()
                        }
                    }
                }
            } finally {
                painter.clear()
            }
        }

    @Test
    fun `image rows remain aligned across prompt dividers without painting their gap`() =
        onEdt {
            val terminal = TerminalBuffers.create(4, 4)
            val state = TerminalShellIntegrationState()
            terminal.readRenderFrame { state.recordPromptStart(it.lineId(2)) }
            val dividers = PromptDividerLayout()
            dividers.updateFrom(terminal, state, 4)
            val geometry = TerminalVisualViewportGeometry()
            geometry.updateLayout(IMAGE_METRICS, 4, 20, dividers)
            assertTrue(geometry.hasDividerBefore(2))
            for (alpha in listOf(255, 128)) {
                val pixels = intArrayOf(0xff0000, 0x00ff00, 0xffffff, 0xffff00).map { it or (alpha shl 24) }.toIntArray()
                val resource = image(1, 4, pixels)
                val frame =
                    object : TestRenderFrame(Array(4) { Array(4) { TestCell() } }) {
                        override fun copyImages(sink: TerminalRenderImageSink) {
                            sink.accept(resource, 0.0, 0.0, 1.0, 4.0, 0, 0, 1, 4, 0.0, 4.0, 0)
                        }
                    }
                val cache = renderCache(frame)
                val painter = GraphicsPainter()
                painter.prepare(cache.images)
                val canvas = BufferedImage(8, 20, BufferedImage.TYPE_INT_ARGB)
                val g = canvas.createGraphics()
                try {
                    g.color = Color.BLUE
                    g.fillRect(0, 0, 8, 20)
                    g.clip = Rectangle(0, 0, 8, 20)
                    painter.paint(g, cache.images, IMAGE_METRICS, geometry, 2, 4, 4)
                    val expected =
                        if (alpha ==
                            255
                        ) {
                            pixels
                        } else {
                            intArrayOf(0xff80007f.toInt(), 0xff00807f.toInt(), 0xff8080ff.toInt(), 0xff80807f.toInt())
                        }
                    for (row in 0 until 4) {
                        for (y in geometry.rowTop(row) until geometry.rowBottom(row)) {
                            assertEquals(expected[row], canvas.getRGB(0, y), "alpha=$alpha row=$row y=$y")
                        }
                    }
                    for (y in 8 until 12) assertEquals(Color.BLUE.rgb, canvas.getRGB(0, y), "divider y=$y")
                    assertEquals(Rectangle(0, 0, 8, 20), g.clipBounds)
                } finally {
                    g.dispose()
                    painter.clear()
                }
            }
        }

    @Test
    fun `raster preparation reuses unchanged resources and clear releases the raster`() =
        onEdt {
            val terminal = TerminalBuffers.create(10, 4)
            terminal.resize(10, 4, 0, 80, 64)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept("\u001b_Ga=T,i=1,f=24,s=1,v=1,C=1;/wAA\u001b\\".encodeToByteArray())
            val cache = TerminalRenderCache(10, 4).apply { updateFrom(terminal as TerminalRenderFrameReader) }
            val rasters = GraphicsRasterCache()
            rasters.prepare(cache.images)
            val first = rasters.image(0)
            repeat(3) {
                cache.updateFrom(terminal)
                rasters.prepare(cache.images)
                assertSame(first, rasters.image(0))
            }
            rasters.clear()
            rasters.prepare(cache.images)
            assertNotSame(first, rasters.image(0))
            assertEquals(first.getRGB(0, 0), rasters.image(0).getRGB(0, 0))
            rasters.clear()
        }

    @Test
    fun `source crop and alpha compose over explicit cell backgrounds`() =
        onEdt {
            val canvas = BufferedImage(160, 96, BufferedImage.TYPE_INT_ARGB)
            val graphics = canvas.createGraphics()
            try {
                val settings =
                    SwingSettings.create {
                        it.padding = SwingPadding(0, 0, 0, 0)
                        it.shellIntegrationDecorationGutterWidth = 0
                    }
                val metrics = SwingMetrics.from(graphics.getFontMetrics(settings.font))
                val terminal = TerminalBuffers.create(10, 4)
                terminal.setThemePalette(settings.palette)
                terminal.resize(10, 4, 0, metrics.cellWidth * 10, metrics.cellHeight * 4)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept(
                    (
                        "\u001b[?25l\u001b[48;2;0;0;255m \u001b[H" +
                            "\u001b_Ga=T,i=1,f=32,s=2,v=1,C=1,c=1,r=1,x=1,w=1,z=-1;/wAA/wD/AIA=\u001b\\"
                    ).encodeToByteArray(),
                )
                val cache = TerminalRenderCache(10, 4).apply { updateFrom(terminal as TerminalRenderFrameReader) }
                val painter = GridPainter()
                painter.prepareImages(cache)
                painter.paint(graphics, cache, settings, metrics, canvas.width, canvas.height, false)
                assertEquals(0xff00807f.toInt(), canvas.getRGB(metrics.cellWidth / 2, metrics.cellHeight / 2))
                assertEquals(settings.palette.defaultBackground, canvas.getRGB(metrics.cellWidth + 1, metrics.cellHeight / 2))
                painter.clearImages()
            } finally {
                graphics.dispose()
            }
        }

    @Test
    fun `raw image crosses publication and paints pixels while pinned frames retain old versions`() =
        onEdt {
            val canvas = BufferedImage(160, 96, BufferedImage.TYPE_INT_ARGB)
            val graphics = canvas.createGraphics()
            try {
                val settings =
                    SwingSettings.create {
                        it.padding = SwingPadding(0, 0, 0, 0)
                        it.shellIntegrationDecorationGutterWidth = 0
                    }
                val metrics = SwingMetrics.from(graphics.getFontMetrics(settings.font))
                val terminal = TerminalBuffers.create(10, 4)
                terminal.setThemePalette(settings.palette)
                terminal.resize(10, 4, 0, 10 * metrics.cellWidth, 4 * metrics.cellHeight)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                val publisher = TerminalRenderPublisher(10, 4)
                val cache = TerminalRenderCache(10, 4)
                val painter = GridPainter()

                fun upload(payload: String) =
                    parser.accept("\u001b_Ga=T,i=7,f=24,s=1,v=1,C=1,c=2,r=2;$payload\u001b\\\u001b[?25l".encodeToByteArray())
                upload("/wAA")
                publisher.updateAndPublish(terminal as TerminalRenderFrameReader)
                val pinned = checkNotNull(publisher.acquireFrontLease())
                try {
                    cache.updateFrom(pinned)
                    painter.prepareImages(cache)
                    painter.paint(graphics, cache, settings, metrics, canvas.width, canvas.height, false)
                    assertEquals(0xffff0000.toInt(), canvas.getRGB(metrics.cellWidth, metrics.cellHeight))
                    upload("AP8A")
                    publisher.updateAndPublish(terminal)
                    val pixel = IntArray(1)
                    pinned.images.image(0).copyArgb(pixel)
                    assertEquals(0xffff0000.toInt(), pixel[0])
                    publisher.readCurrent { cache.updateFrom(it) }
                    painter.prepareImages(cache)
                    painter.paint(graphics, cache, settings, metrics, canvas.width, canvas.height, false)
                    assertEquals(0xff00ff00.toInt(), canvas.getRGB(metrics.cellWidth, metrics.cellHeight))
                    parser.accept("\u001b_Ga=d,d=I,i=7;\u001b\\".encodeToByteArray())
                    publisher.updateAndPublish(terminal)
                    publisher.readCurrent { cache.updateFrom(it) }
                    painter.prepareImages(cache)
                    painter.paint(graphics, cache, settings, metrics, canvas.width, canvas.height, false)
                    assertEquals(settings.palette.defaultBackground, canvas.getRGB(metrics.cellWidth, metrics.cellHeight))
                } finally {
                    publisher.releaseFrontLease(pinned)
                    painter.clearImages()
                }
            } finally {
                graphics.dispose()
            }
        }

    @Test
    fun `negative image bands respect default and explicit backgrounds`() =
        onEdt {
            val canvas = BufferedImage(160, 96, BufferedImage.TYPE_INT_ARGB)
            val graphics = canvas.createGraphics()
            try {
                val settings =
                    SwingSettings.create {
                        it.padding = SwingPadding(0, 0, 0, 0)
                        it.shellIntegrationDecorationGutterWidth = 0
                    }
                val metrics = SwingMetrics.from(graphics.getFontMetrics(settings.font))
                for (z in listOf(-2147483648, -1, 0)) {
                    val terminal = TerminalBuffers.create(10, 4)
                    terminal.setThemePalette(settings.palette)
                    terminal.resize(10, 4, 0, metrics.cellWidth * 10, metrics.cellHeight * 4)
                    val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                    parser.accept(
                        (
                            "\u001b[?25l\u001b[48;2;0;255;0m \u001b[0m \u001b[H" +
                                "\u001b_Ga=T,i=1,f=24,s=1,v=1,C=1,c=2,r=1,z=$z;/wAA\u001b\\"
                        ).encodeToByteArray(),
                    )
                    val cache = TerminalRenderCache(10, 4).apply { updateFrom(terminal as TerminalRenderFrameReader) }
                    val painter = GridPainter()
                    painter.prepareImages(cache)
                    painter.paint(graphics, cache, settings, metrics, canvas.width, canvas.height, false)
                    assertEquals(
                        if (z <
                            -1073741824
                        ) {
                            0xff00ff00.toInt()
                        } else {
                            0xffff0000.toInt()
                        },
                        canvas.getRGB(metrics.cellWidth / 2, metrics.cellHeight / 2),
                    )
                    assertEquals(0xffff0000.toInt(), canvas.getRGB(metrics.cellWidth + metrics.cellWidth / 2, metrics.cellHeight / 2))
                    painter.clearImages()
                }
            } finally {
                graphics.dispose()
            }
        }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }

    private fun image(
        width: Int,
        height: Int,
        pixels: IntArray,
    ): TerminalRenderImage =
        object : TerminalRenderImage {
            override val width = width
            override val height = height

            override fun copyArgb(
                destination: IntArray,
                offset: Int,
            ) {
                pixels.copyInto(destination, offset)
            }
        }

    private fun imageCache(vararg resources: TerminalRenderImage): TerminalRenderCache =
        renderCache(
            object : TestRenderFrame(Array(2) { Array(4) { TestCell() } }) {
                override fun copyImages(sink: TerminalRenderImageSink) {
                    for (resource in resources) {
                        sink.accept(resource, 0.0, 0.0, 1.0, 1.0, 0, 0, resource.width, resource.height, 0.0, 2.0, 0)
                    }
                }
            },
        )

    private companion object {
        val IMAGE_METRICS = SwingMetrics(2, 4, 3, 3, 2, 0, 1)
    }
}
