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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.*

class TerminalShellIntegrationDecorationPainterTest {
    private val metrics = SwingMetrics(8, 20, 14, 16, 8, 0, 1)
    private val painter = TerminalShellIntegrationDecorationPainter(AwtColorCache())
    private val palette = TerminalColorPalette(defaultForeground = TEST_GREEN)
    private val settings =
        SwingSettings.create {
            it.shellIntegrationPromptDotDiameter = 6
            it.shellIntegrationFailedPromptDotColor = TEST_RED
            it.shellIntegrationFailedCommandRailColor = TEST_BLUE
        }

    @ParameterizedTest
    @CsvSource("1.0,6", "1.0,8", "1.25,8", "1.5,8", "2.0,8", "1.25,9", "1.5,9", "2.0,1", "2.0,2")
    fun `hollow markers preserve filled marker bounds and graphics state across display scales`(
        scale: Double,
        diameter: Int,
    ) {
        val cache = renderCache(TestRenderFrame(arrayOf(arrayOf(TestCell()))))
        val state = TerminalShellIntegrationState().apply { recordPromptStart(1) }
        val decorations = TerminalShellIntegrationViewportDecorations().apply { updateFrom(state, cache) }
        val configured =
            settings.copy {
                it.shellIntegrationPromptDotDiameter = diameter
                it.shellIntegrationPromptDotColor = TEST_WHITE
            }
        val inheritedStroke = BasicStroke(4f)

        fun paint(): BufferedImage =
            image { g ->
                g.scale(scale, scale)
                g.translate(20, 0)
                g.stroke = inheritedStroke
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
                painter.paint(g, configured, metrics, decorations, 20, 0, false, palette)
                assertEquals(inheritedStroke, g.stroke)
                assertEquals(RenderingHints.VALUE_STROKE_NORMALIZE, g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL))
            }

        fun bounds(image: BufferedImage): Rectangle {
            val bounds = Rectangle()
            var empty = true
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getRGB(x, y) ushr 24 == 0) continue
                    if (empty) {
                        bounds.setBounds(x, y, 1, 1)
                        empty = false
                    } else {
                        bounds.add(Rectangle(x, y, 1, 1))
                    }
                }
            }
            assertFalse(empty)
            return bounds
        }

        val hollow = paint()
        state.recordCommandStart(1, includeLine = true)
        state.recordCommandFinished(1, exitCode = 0)
        decorations.updateFrom(state, cache)
        val filled = paint()
        assertEquals(bounds(filled), bounds(hollow), "Completion preserves the marker's outer bounds")
        val centerX = ((10 - diameter / 2 + diameter / 2.0) * scale).toInt()
        val centerY = (((metrics.cellHeight - diameter) / 2 + diameter / 2.0) * scale).toInt()
        val filledCenter = filled.getRGB(centerX, centerY)
        assertTrue(filledCenter ushr 24 > 0)
        assertEquals(if (diameter > 2) 0 else filledCenter, hollow.getRGB(centerX, centerY))
    }

    @ParameterizedTest
    @ValueSource(
        ints = [
            TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
            TerminalShellIntegrationCommandLifecycle.RUNNING,
            TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            TerminalShellIntegrationCommandLifecycle.FAILED,
            TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN,
            TerminalShellIntegrationCommandLifecycle.ABANDONED,
        ],
    )
    fun `only completed prompt records expose interactive markers`(lifecycle: Int) {
        val cache = renderCache(TestRenderFrame(Array(3) { arrayOf(TestCell()) }))
        val state =
            TerminalShellIntegrationState().apply {
                recordPromptStart(1)
                if (lifecycle != TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY) recordCommandStart(2, includeLine = true)
                when (lifecycle) {
                    TerminalShellIntegrationCommandLifecycle.SUCCEEDED -> recordCommandFinished(2, exitCode = 0)
                    TerminalShellIntegrationCommandLifecycle.FAILED -> recordCommandFinished(2, exitCode = 1)
                    TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN -> recordCommandFinished(2, exitCode = null)
                    TerminalShellIntegrationCommandLifecycle.ABANDONED -> recordPromptStart(3)
                }
            }
        val decorations = TerminalShellIntegrationViewportDecorations().apply { updateFrom(state, cache) }
        val completed =
            lifecycle == TerminalShellIntegrationCommandLifecycle.SUCCEEDED ||
                lifecycle == TerminalShellIntegrationCommandLifecycle.FAILED ||
                lifecycle == TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN
        assertEquals(lifecycle, decorations.commandLifecycleAt(0))
        assertEquals(completed, decorations.isPromptMarkerInteractiveAt(0))
        assertFalse(decorations.isPromptMarkerInteractiveAt(-1))
        assertFalse(decorations.isPromptMarkerInteractiveAt(1), "Command output is not a prompt marker")
        assertFalse(decorations.isPromptMarkerInteractiveAt(3))

        val configured =
            settings.copy {
                it.shellIntegrationPromptDotColor = TEST_WHITE
                it.shellIntegrationPromptDotHoverColor = TEST_GREEN
                it.shellIntegrationPromptDotHoverHaloColor = TEST_RED
            }

        fun paint(hovered: Boolean) =
            image { g ->
                g.translate(20, 0)
                painter.paint(g, configured, metrics, decorations, 20, 0, hovered, palette)
            }
        val resting = paint(false)
        val completedColor = if (lifecycle == TerminalShellIntegrationCommandLifecycle.FAILED) TEST_RED else TEST_WHITE
        assertEquals(if (completed) completedColor else 0, resting.getRGB(10, 10))
        assertNotEquals(0, resting.getRGB(10, 7), "Every prompt keeps a visible outline or fill")
        if (!completed) {
            val hovered = paint(true)
            assertContentEquals(
                resting.getRGB(0, 0, 40, 40, null, 0, 40),
                hovered.getRGB(0, 0, 40, 40, null, 0, 40),
            )
        }
        assertTrue(decorations.updateFrom(state.apply { clear() }, cache))
        assertFalse(decorations.isPromptMarkerInteractiveAt(0))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `inactive divider remains a solid line and ignores hover colors`(hovered: Boolean) {
        val configured =
            settings.copy {
                it.shellIntegrationPromptDividerColor = TEST_WHITE
                it.shellIntegrationPromptDividerHoverColor = TEST_RED
            }
        val image = divider(configured, palette, hovered, interactive = false)
        assertEquals(0, image.getRGB(8, 8))
        assertEquals(TEST_WHITE, image.getRGB(8, 9))
        assertEquals(TEST_WHITE, image.getRGB(8, 10))
        assertEquals(TEST_WHITE, image.getRGB(0, 9))
        assertEquals(TEST_WHITE, image.getRGB(19, 9))
        assertEquals(0, image.getRGB(8, 7))
        assertEquals(0, image.getRGB(8, 11))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `divider defaults track the current palette independently of other overrides`(hovered: Boolean) {
        val configured = settings.copy { it.shellIntegrationPromptDividerColor = TEST_RED }
        for (foreground in intArrayOf(TEST_GREEN, TEST_BLUE)) {
            val image = divider(configured, palette.copy(defaultForeground = foreground), hovered)
            assertEquals(if (hovered) (foreground and 0xffffff) or 0x70000000 else TEST_RED, image.getRGB(8, 9))
        }
        val image = divider(settings, palette, hovered)
        assertEquals((TEST_GREEN and 0xffffff) or (if (hovered) 0x70000000 else 0x30000000), image.getRGB(8, 9))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 48, 112, 128, 255])
    fun `divider overrides preserve every requested alpha in normal and hover states`(alpha: Int) {
        val color = (alpha shl 24) or 0xff0000
        val configured =
            settings.copy {
                it.shellIntegrationPromptDividerColor = color
                it.shellIntegrationPromptDividerHoverColor = color
            }
        for (hovered in booleanArrayOf(false, true)) {
            val image = divider(configured, palette, hovered)
            assertEquals(if (alpha == 0) 0 else color, image.getRGB(8, 9))
            assertEquals(0, image.getRGB(8, 8))
            assertEquals(0, image.getRGB(8, 11))
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `hover dot and halo colors are independent for normal and failed commands`(
        failed: Boolean,
        hovered: Boolean,
    ) {
        val configured =
            settings.copy {
                it.shellIntegrationPromptDotColor = TEST_WHITE
                it.shellIntegrationPromptDotHoverColor = TEST_GREEN
                it.shellIntegrationFailedPromptDotHoverColor = TEST_BLUE
                it.shellIntegrationPromptDotHoverHaloColor = TEST_RED
                it.shellIntegrationFailedPromptDotHoverHaloColor = TEST_WHITE
            }
        val image = marker(configured, failed, hovered)
        val dot =
            when {
                hovered && failed -> TEST_BLUE
                hovered -> TEST_GREEN
                failed -> TEST_RED
                else -> TEST_WHITE
            }
        val halo =
            when {
                !hovered -> 0
                failed -> TEST_WHITE
                else -> TEST_RED
            }
        assertEquals(dot, image.getRGB(10, 10))
        assertEquals(halo, image.getRGB(10, 3))
        assertEquals(if (failed) TEST_BLUE else 0, image.getRGB(10, 25), "Hover must not recolor the failure rail")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `null hover overrides retain marker and palette derived halo defaults`(failed: Boolean) {
        val image = marker(settings, failed, true)
        val rgb = if (failed) TEST_RED else TEST_GREEN
        assertEquals((rgb and 0xffffff) or 0x52000000, image.getRGB(10, 3))
        // The normal marker uses foreground RGB with its established 0x8c alpha over the halo.
        assertEquals(if (failed) TEST_RED else 0xb100ff00.toInt(), image.getRGB(10, 10))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `transparent dot and halo overrides hide hover paint without changing normal state`(failed: Boolean) {
        val configured =
            settings.copy {
                it.shellIntegrationPromptDotHoverColor = 0
                it.shellIntegrationFailedPromptDotHoverColor = 0
                it.shellIntegrationPromptDotHoverHaloColor = 0
                it.shellIntegrationFailedPromptDotHoverHaloColor = 0
            }
        val hovered = marker(configured, failed, true)
        assertEquals(0, hovered.getRGB(10, 10))
        assertEquals(0, hovered.getRGB(10, 3))
        val normal = marker(configured, failed, false)
        assertEquals(if (failed) TEST_RED else (TEST_GREEN and 0xffffff) or 0x8c000000.toInt(), normal.getRGB(10, 10))
    }

    @Test
    fun `dot and halo overrides preserve alpha and treat the legacy normal dot default as an exact color`() {
        for (color in intArrayOf(0, 0x010000ff, 0x520000ff, 0x8cffffff.toInt(), TEST_WHITE)) {
            val dotOnly =
                settings.copy {
                    it.shellIntegrationPromptDotHoverColor = color
                    it.shellIntegrationFailedPromptDotHoverColor = color
                    it.shellIntegrationPromptDotHoverHaloColor = 0
                    it.shellIntegrationFailedPromptDotHoverHaloColor = 0
                }
            val haloOnly =
                dotOnly.copy {
                    it.shellIntegrationPromptDotHoverColor = 0
                    it.shellIntegrationFailedPromptDotHoverColor = 0
                    it.shellIntegrationPromptDotHoverHaloColor = color
                    it.shellIntegrationFailedPromptDotHoverHaloColor = color
                }
            for (failed in booleanArrayOf(false, true)) {
                assertEquals(color, marker(dotOnly, failed, true).getRGB(10, 10), "Dot failed=$failed color=$color")
                assertEquals(color, marker(haloOnly, failed, true).getRGB(10, 3), "Halo failed=$failed color=$color")
            }
        }
    }

    @Test
    fun `hidden markers suppress custom hover painting and zero gutter suppresses every decoration`() {
        val configured =
            settings.copy {
                it.shellIntegrationPromptDotsVisible = false
                it.shellIntegrationPromptDotHoverHaloColor = TEST_WHITE
            }
        val hidden = marker(configured, false, true)
        assertEquals(0, hidden.getRGB(10, 10))
        assertEquals(0, hidden.getRGB(10, 3))
        val noGutter = marker(settings, true, true, gutterWidth = 0)
        for (y in 0 until noGutter.height) for (x in 0 until noGutter.width) assertEquals(0, noGutter.getRGB(x, y))
    }

    @Test
    fun `divider paint respects fractional transform and existing clip`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.scale(1.5, 1.5)
            g.clip = Rectangle(4, 8, 3, 4)
            val clip = g.clip
            painter.paintDivider(
                g,
                settings.copy {
                    it.shellIntegrationPromptDividerColor = TEST_RED
                },
                metrics,
                1,
                20,
                false,
                palette,
                interactive = true,
            )
            assertEquals(clip, g.clip)
            assertEquals(TEST_RED, image.getRGB(6, 14))
            assertEquals(0, image.getRGB(5, 14))
            assertEquals(0, image.getRGB(11, 14))
            assertEquals(0, image.getRGB(6, 12))
        } finally {
            g.dispose()
        }
    }

    private fun divider(
        settings: SwingSettings,
        palette: TerminalColorPalette,
        hovered: Boolean,
        interactive: Boolean = true,
    ): BufferedImage = image { g -> painter.paintDivider(g, settings, metrics, 1, 20, hovered, palette, interactive) }

    private fun marker(
        settings: SwingSettings,
        failed: Boolean,
        hovered: Boolean,
        gutterWidth: Int = 20,
    ): BufferedImage {
        val cache = renderCache(TestRenderFrame(Array(2) { arrayOf(TestCell()) }))
        val state =
            TerminalShellIntegrationState().apply {
                recordPromptStart(1)
                recordCommandStart(2, includeLine = true)
                recordCommandFinished(2, exitCode = if (failed) 1 else 0)
            }
        val decorations = TerminalShellIntegrationViewportDecorations().apply { updateFrom(state, cache) }
        return image { g ->
            g.translate(20, 0)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_DEFAULT)
            painter.paint(g, settings, metrics, decorations, gutterWidth, 0, hovered, palette)
            assertEquals(RenderingHints.VALUE_ANTIALIAS_DEFAULT, g.getRenderingHint(RenderingHints.KEY_ANTIALIASING))
            painter.paint(g, settings, metrics, decorations, gutterWidth, 1, false, palette)
        }
    }

    private fun image(paint: (Graphics2D) -> Unit): BufferedImage {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            paint(g)
        } finally {
            g.dispose()
        }
        return image
    }
}
