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

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderRowSnapshot
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.session.TerminalShellIntegrationView
import io.github.ketraterm.ui.swing.settings.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.*

class StickyCommandHeaderTest {
    @Test
    fun `top output activates header while input alternate screen and untouched tail do not`() =
        onEdt {
            val fixture = Fixture()
            assertTrue(fixture.update())
            assertEquals(fixture.state.latestCommandRecordId(), fixture.header.recordId)
            assertFalse(fixture.update())
            fixture.install(firstLine = 10)
            fixture.update()
            assertFalse(fixture.header.visible)
            fixture.install(firstLine = 11, outputEnd = 0)
            fixture.update()
            assertFalse(fixture.header.visible)
            fixture.install(firstLine = 11, buffer = TerminalRenderBufferKind.ALTERNATE)
            fixture.update()
            assertFalse(fixture.header.visible)
            fixture.install(firstLine = 11)
            fixture.update()
            assertTrue(fixture.header.visible)
            fixture.settings = fixture.settings.copy { it.stickyCommandHeadersEnabled = false }
            fixture.update()
            assertFalse(fixture.header.visible)
        }

    @Test
    fun `fractional scrolling waits until input leaves and never covers a divider band`() =
        onEdt {
            val fixture = Fixture()
            fixture.install(firstLine = 10)
            fixture.geometry.updateContentOrigin(-19.5)
            fixture.update()
            assertFalse(fixture.header.visible)
            fixture.geometry.updateContentOrigin(-20.0)
            fixture.update()
            assertTrue(fixture.header.visible)
            fixture.geometry.updateContentOrigin(0.5)
            fixture.update()
            assertFalse(fixture.header.visible)
        }

    @Test
    fun `header paints one opaque row and leaves output below its shadow unchanged in all prompt styles`() =
        onEdt {
            for (style in SwingPromptDecoration.entries) {
                val fixture = Fixture()
                fixture.settings = fixture.settings.copy { it.promptDecoration = style }
                fixture.update()
                val image = BufferedImage(360, 60, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                try {
                    g.color = Color.MAGENTA
                    g.fillRect(0, 0, image.width, image.height)
                    fixture.header.paint(g)
                } finally {
                    g.dispose()
                }
                assertNotEquals(Color.MAGENTA.rgb, image.getRGB(200, 2))
                assertEquals(Color.MAGENTA.rgb, image.getRGB(200, 24))
                assertTrue(fixture.header.contains(200, 1))
                assertFalse(fixture.header.contains(200, 20))
            }
        }

    @Test
    fun `metadata queries are cached across fractional scroll ticks and invalidated by revision`() =
        onEdt {
            val fixture = Fixture()
            var ownershipReads = 0
            var textReads = 0
            val view =
                object : TerminalShellIntegrationView by fixture.state {
                    override fun commandOutputRecordIdAtLine(lineId: Long): Int {
                        ownershipReads++
                        return fixture.state.commandOutputRecordIdAtLine(lineId)
                    }

                    override fun commandPrompt(recordId: Int): TerminalRenderRowSnapshot? {
                        textReads++
                        return fixture.state.commandPrompt(recordId)
                    }
                }
            fixture.view = view
            fixture.update()
            for (tick in 1..30) {
                fixture.geometry.updateContentOrigin(-tick / 100.0)
                assertFalse(fixture.update())
            }
            assertEquals(1, ownershipReads)
            assertEquals(1, textReads)
            fixture.state.recordCommandFinished(12, 0)
            fixture.update()
            assertEquals(2, ownershipReads)
            assertEquals(2, textReads)
        }

    @Test
    fun `completion next prompt missing text and clear replace or remove context`() =
        onEdt {
            val fixture = Fixture()
            fixture.update()
            val first = fixture.header.recordId
            fixture.state.recordCommandFinished(12, 0)
            fixture.update()
            assertEquals(first, fixture.header.recordId)
            fixture.state.recordPromptStart(13)
            fixture.install(firstLine = 13)
            fixture.update()
            assertFalse(fixture.header.visible)
            fixture.start("next", 14)
            fixture.install(firstLine = 14)
            fixture.update()
            assertNotEquals(first, fixture.header.recordId)
            assertTrue(fixture.header.visible)
            fixture.state.recordCommandStart(15, true)
            fixture.install(firstLine = 15)
            fixture.update()
            assertFalse(fixture.header.visible)
            fixture.state.clear()
            fixture.update()
            assertFalse(fixture.header.visible)
        }

    @Test
    fun `stationary pointer and captured clicks follow header identity and binding`() =
        onEdt {
            val fixture = Fixture()
            fixture.header.updatePointer(100, 2)
            fixture.update()
            assertTrue(fixture.header.hovered)
            val first = fixture.header.recordId
            fixture.header.press(1)
            assertEquals(first, fixture.header.release(100, 2, 1))
            fixture.header.press(1)
            fixture.header.drag(100, 40)
            assertEquals(0, fixture.header.release(100, 2, 1))
            fixture.header.press(3)
            assertEquals(0, fixture.header.release(100, 2, 3))
            fixture.header.press(1)
            fixture.start("replacement")
            fixture.update()
            assertEquals(0, fixture.header.release(100, 2, 1))
            fixture.header.press(1)
            fixture.view =
                TerminalShellIntegrationState().apply {
                    recordCommandStart(
                        11,
                        true,
                        "other binding",
                        null,
                        captured("PS > other binding"),
                    )
                }
            fixture.update()
            assertEquals(0, fixture.header.release(100, 2, 1))
            fixture.header.reset()
            assertFalse(fixture.header.visible)
            assertFalse(fixture.header.gestureActive)
        }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun `header chords retain every button until released and cancel navigation`(firstRelease: Int) =
        onEdt {
            val fixture = Fixture()
            fixture.update()
            fixture.header.press(1)
            fixture.header.press(3)
            assertTrue(fixture.header.ownsButton(1))
            assertTrue(fixture.header.ownsButton(3))
            assertFalse(fixture.header.ownsButton(2))
            assertEquals(0, fixture.header.release(100, 2, 2))
            assertEquals(0, fixture.header.release(100, 2, firstRelease))
            assertFalse(fixture.header.ownsButton(firstRelease))
            assertTrue(fixture.header.gestureActive)
            assertEquals(0, fixture.header.release(100, 2, 4 - firstRelease))
            assertFalse(fixture.header.gestureActive)
            fixture.header.press(1)
            assertEquals(fixture.header.recordId, fixture.header.release(100, 2, 1))
        }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 2.0])
    fun `full prompt uses exactly the terminal font cell size and original colors`(scale: Double) =
        onEdt {
            val fixture = Fixture()
            val configuration = ScaledConfiguration(scale)
            val pixelWidth = (360 * scale).toInt()
            val pixelHeight = (60 * scale).toInt()
            val comparedHeight = (19 * scale).toInt()
            val text = "PS C:\\Users\\gagik> cat".padEnd(40)
            val attrs =
                LongArray(40) { index ->
                    if (index in 19..21) {
                        TerminalRenderAttrs.pack(foregroundKind = TerminalRenderColorKind.RGB, foregroundValue = 0xffcc55)
                    } else {
                        TerminalRenderAttrs.DEFAULT
                    }
                }
            val frame = TestRenderFrame.text(text, attrs = attrs)
            fixture.state.recordCommandStart(11, true, "cat", null, TerminalRenderRowSnapshot.copyOf(frame, 0))
            fixture.update(configuration = configuration)
            val actual = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
            actual.createGraphics().let { g ->
                g.scale(scale, scale)
                try {
                    fixture.header.paint(g)
                } finally {
                    g.dispose()
                }
            }
            val expected = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
            expected.createGraphics().let { g ->
                g.scale(scale, scale)
                try {
                    GridPainter().paint(g, renderCache(frame), fixture.settings, METRICS, 360, 60, cursorBlinkVisible = false)
                } finally {
                    g.dispose()
                }
            }
            assertContentEquals(
                expected.getRGB(0, 0, pixelWidth, comparedHeight, null, 0, pixelWidth),
                actual.getRGB(0, 0, pixelWidth, comparedHeight, null, 0, pixelWidth),
            )
            fixture.settings = fixture.settings.copy { it.font = it.font.deriveFont(18f) }
            assertTrue(fixture.update(configuration = configuration))
            val changed = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
            changed.createGraphics().let { g ->
                g.scale(scale, scale)
                try {
                    fixture.header.paint(g)
                } finally {
                    g.dispose()
                }
            }
            assertFalse(
                actual
                    .getRGB(
                        0,
                        0,
                        pixelWidth,
                        comparedHeight,
                        null,
                        0,
                        pixelWidth,
                    ).contentEquals(changed.getRGB(0, 0, pixelWidth, comparedHeight, null, 0, pixelWidth)),
            )
        }

    @Test
    fun `new prompt snapshots with repeated line identities refresh bidirectional layout`() =
        onEdt {
            val fixture = Fixture()
            for (text in listOf("PS > build", "PS > \u05d0\u05d1\u05d2", "PS > test")) {
                val frame = TestRenderFrame.text(text.padEnd(40))
                fixture.state.recordCommandStart(11, true, text, null, TerminalRenderRowSnapshot.copyOf(frame, 0))
                fixture.update()
                val actual = BufferedImage(360, 60, BufferedImage.TYPE_INT_RGB)
                actual.createGraphics().let { g ->
                    try {
                        fixture.header.paint(g)
                    } finally {
                        g.dispose()
                    }
                }
                val expected = BufferedImage(360, 60, BufferedImage.TYPE_INT_RGB)
                expected.createGraphics().let { g ->
                    try {
                        GridPainter().paint(g, renderCache(frame), fixture.settings, METRICS, 360, 60, cursorBlinkVisible = false)
                    } finally {
                        g.dispose()
                    }
                }
                assertContentEquals(expected.getRGB(0, 0, 360, 19, null, 0, 360), actual.getRGB(0, 0, 360, 19, null, 0, 360), text)
            }
        }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 2.0])
    fun `fractional raster widths preserve the background at the right edge`(scale: Double) =
        onEdt {
            val fixture = Fixture()
            val colors = TerminalColorPalette(defaultBackground = 0xff26323e.toInt())
            fixture.install(colors = colors)
            val configuration = ScaledConfiguration(scale)
            for (width in 337..345) {
                fixture.update(width, configuration)
                val image = BufferedImage(ceil(width * scale).toInt(), (60 * scale).toInt(), BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { g ->
                    try {
                        g.scale(scale, scale)
                        fixture.header.paint(g)
                    } finally {
                        g.dispose()
                    }
                }
                val right = floor(width * scale).toInt() - 1
                assertEquals(colors.defaultBackground, image.getRGB(right, (2 * scale).toInt()), "width=$width scale=$scale")
            }
        }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 2.0])
    fun `shadow fades below the header and paints clips that exclude the header itself`(scale: Double) =
        onEdt {
            val fixture = Fixture()
            fixture.update(configuration = ScaledConfiguration(scale))
            val image = BufferedImage((360 * scale).toInt(), (60 * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { g ->
                try {
                    g.color = Color.WHITE
                    g.fillRect(0, 0, image.width, image.height)
                    g.scale(scale, scale)
                    g.clipRect(0, 20, 360, 4)
                    fixture.header.paint(g)
                } finally {
                    g.dispose()
                }
            }
            val x = (200 * scale).toInt()
            val start = (20 * scale).toInt()
            val end = (24 * scale).toInt()
            assertEquals(Color.WHITE.rgb, image.getRGB(x, start - 1))
            assertNotEquals(Color.WHITE.rgb, image.getRGB(x, start))
            var previous = 0
            for (y in start until end) {
                val intensity = image.getRGB(x, y) and 0xff
                assertTrue(intensity >= previous, "shadow must fade at scale=$scale y=$y")
                previous = intensity
            }
            assertEquals(Color.WHITE.rgb, image.getRGB(x, end - 1))
            assertEquals(Color.WHITE.rgb, image.getRGB(x, end))
            assertFalse(fixture.header.contains(200, 21), "shadow must not intercept output interaction")
        }

    @Test
    fun `shadow stops at the content bottom padding`() =
        onEdt {
            val fixture = Fixture()
            fixture.header.update(fixture.view, fixture.cache, fixture.geometry, fixture.settings, METRICS, 360, 22, null)
            val image = BufferedImage(360, 60, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { g ->
                try {
                    g.color = Color.WHITE
                    g.fillRect(0, 0, 360, 60)
                    fixture.header.paint(g)
                } finally {
                    g.dispose()
                }
            }
            assertNotEquals(Color.WHITE.rgb, image.getRGB(200, 20))
            assertEquals(Color.WHITE.rgb, image.getRGB(200, 22))
        }

    private class Fixture {
        val state =
            TerminalShellIntegrationState().apply {
                recordPromptStart(10)
                recordPromptEnd(10)
                recordCommandStart(11, true, "./gradlew build", null, captured("PS C:\\work> ./gradlew build"))
            }
        var view: TerminalShellIntegrationView = state
        var settings =
            SwingSettings.create {
                it.promptDecoration = SwingPromptDecoration.NONE
                it.padding = SwingPadding(0, 0, 0, 0)
                it.useSystemFallbackFonts = false
            }
        val geometry = TerminalVisualViewportGeometry()
        val cache = TerminalRenderCache(40, 2)
        val header = StickyCommandHeader(SwingTerminalChrome())

        init {
            install()
        }

        fun install(
            firstLine: Long = 11,
            outputEnd: Long = Long.MAX_VALUE,
            buffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
            colors: TerminalColorPalette = TerminalColorPalette(),
        ) {
            cache.reset()
            cache.accept(
                object : TestRenderFrame(Array(2) { Array(40) { TestCell() } }) {
                    override val activeBuffer = buffer
                    override val palette = colors
                    override val outputEndAbsoluteRow = outputEnd

                    override fun lineId(row: Int): Long = firstLine + row
                },
            )
            geometry.updateLayout(METRICS, cache.rows, 60)
            geometry.updateContentOrigin(0.0)
        }

        fun start(
            text: String,
            line: Long = 11,
        ) = state.recordCommandStart(line, true, text, null, captured("PS > $text"))

        fun update(
            width: Int = 360,
            configuration: GraphicsConfiguration? = null,
        ): Boolean = header.update(view, cache, geometry, settings, METRICS, width, 60, configuration)
    }

    private class ScaledConfiguration(
        private val scale: Double,
    ) : GraphicsConfiguration() {
        override fun getDefaultTransform(): AffineTransform = AffineTransform.getScaleInstance(scale, scale)

        override fun getNormalizingTransform(): AffineTransform = AffineTransform()

        override fun getBounds(): Rectangle = Rectangle(0, 0, 1920, 1080)

        override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()

        override fun getColorModel(transparency: Int): ColorModel = colorModel

        override fun getDevice(): GraphicsDevice = error("No device required for offscreen row replay")
    }

    private companion object {
        fun captured(text: String): TerminalRenderRowSnapshot = TerminalRenderRowSnapshot.copyOf(TestRenderFrame.text(text.padEnd(40)), 0)

        val METRICS = SwingMetrics(8, 20, 14, 16, 8, 0, 1)

        fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
    }
}
