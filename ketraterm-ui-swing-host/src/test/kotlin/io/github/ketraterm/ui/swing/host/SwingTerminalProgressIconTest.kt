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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingTerminalProgressIconTest {
    @Test
    fun `size and phase bounds reject invalid configuration without mutation`() {
        SwingUtilities.invokeAndWait {
            for (size in intArrayOf(Int.MIN_VALUE, 0, 7, 129, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { SwingTerminalProgressIcon(size) }
            }
            for (size in intArrayOf(8, 128)) assertEquals(size, SwingTerminalProgressIcon(size).iconWidth)
            val icon = SwingTerminalProgressIcon()
            icon.phase = 0.5
            for (phase in doubleArrayOf(-1.0, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertFailsWith<IllegalArgumentException> { icon.phase = phase }
                assertEquals(0.5, icon.phase)
            }
        }
    }

    @Test
    fun `icon construction requires EDT`() {
        assertFailsWith<IllegalStateException> { SwingTerminalProgressIcon() }
    }

    @Test
    fun `hidden colors and indeterminate phase are visible in rendered pixels`() {
        SwingUtilities.invokeAndWait {
            val icon = SwingTerminalProgressIcon(16)

            fun pixels(): IntArray {
                val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try {
                    icon.paintIcon(null, g, 2, 2)
                } finally {
                    g.dispose()
                }
                return image.getRGB(0, 0, 20, 20, null, 0, 20)
            }
            assertTrue(pixels().all { it == 0 })
            icon.errorColor = Color.RED
            icon.progress = TerminalProgress(TerminalProgressState.ERROR, 100)
            assertTrue(pixels().any { it and 0x00ffffff == 0x00ff0000 })
            icon.warningColor = Color.BLUE
            icon.progress = TerminalProgress(TerminalProgressState.WARNING, 100)
            assertTrue(pixels().any { it and 0x00ffffff == 0x000000ff })
            icon.progress = TerminalProgress(TerminalProgressState.INDETERMINATE, 0)
            icon.phase = 0.0
            val before = pixels()
            icon.phase = 0.25
            assertFalse(before.contentEquals(pixels()))
            icon.setAccessibleIconDescription("Building: 25 percent")
            assertEquals("Building: 25 percent", icon.accessibleIconDescription)
            icon.progress = TerminalProgress.HIDDEN
            assertTrue(pixels().all { it == 0 })
        }
    }

    @Test
    fun `clock advances a quarter turn using controlled elapsed time`() {
        SwingUtilities.invokeAndWait {
            val component =
                object : javax.swing.JPanel() {
                    override fun isShowing(): Boolean = true
                }
            var phase = 0.0
            val clock = SwingTerminalProgressAnimation(component) { phase = it }
            try {
                clock.active = true
                val now = System.nanoTime()
                clock.advanceFrame(now)
                val first = phase
                clock.advanceFrame(now + 250_000_000L)
                assertEquals(0.25, (phase - first + 1.0) % 1.0, 0.00000001)
            } finally {
                clock.close()
            }
        }
    }

    @Test
    fun `animation stops on hide completion and disposal and rejects stale ticks`() {
        SwingUtilities.invokeAndWait {
            var showing = true
            val component =
                object : javax.swing.JPanel() {
                    override fun isShowing(): Boolean = showing
                }
            var frames = 0
            val baseline = component.hierarchyListeners.size
            val clock =
                SwingTerminalProgressAnimation(component) { phase ->
                    assertTrue(phase >= 0.0 && phase < 1.0)
                    frames++
                }
            try {
                clock.active = true
                assertTrue(clock.isRunning)
                clock.advanceFrame(System.nanoTime())
                assertEquals(1, frames)
                showing = false
                val event =
                    java.awt.event.HierarchyEvent(
                        component,
                        java.awt.event.HierarchyEvent.HIERARCHY_CHANGED,
                        component,
                        null,
                        java.awt.event.HierarchyEvent.SHOWING_CHANGED
                            .toLong(),
                    )
                for (listener in component.hierarchyListeners) listener.hierarchyChanged(event)
                assertFalse(clock.isRunning)
                clock.advanceFrame(System.nanoTime())
                assertEquals(1, frames)
                showing = true
                for (listener in component.hierarchyListeners) listener.hierarchyChanged(event)
                assertTrue(clock.isRunning)
                clock.active = false
                assertFalse(clock.isRunning)
                clock.close()
                assertEquals(baseline, component.hierarchyListeners.size)
                clock.active = true
                clock.advanceFrame(System.nanoTime())
                assertFalse(clock.isRunning)
                assertEquals(1, frames)
            } finally {
                clock.close()
            }
        }
    }

    @Test
    fun `ring represents percentages colors and phase while preserving graphics`() {
        SwingUtilities.invokeAndWait {
            val icon = SwingTerminalProgressIcon(16)
            val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                val oldStroke = g.stroke
                val oldHints = g.renderingHints
                g.color = Color.MAGENTA
                icon.progress = TerminalProgress(TerminalProgressState.NORMAL, 25)
                icon.paintIcon(null, g, 2, 2)
                assertEquals(Color.MAGENTA, g.color)
                assertSame(oldStroke, g.stroke)
                assertEquals(oldHints, g.renderingHints)
                val quarter = image.getRGB(0, 0, 20, 20, null, 0, 20)
                icon.progress = TerminalProgress(TerminalProgressState.NORMAL, 100)
                icon.paintIcon(null, g, 2, 2)
                assertFalse(quarter.contentEquals(image.getRGB(0, 0, 20, 20, null, 0, 20)))
                icon.progress = TerminalProgress(TerminalProgressState.INDETERMINATE, 0)
                icon.phase = 0.25
                assertEquals(0.25, icon.phase)
                assertFailsWith<IllegalArgumentException> { icon.phase = Double.NaN }
                assertEquals(0.25, icon.phase)
                assertFailsWith<IllegalArgumentException> { SwingTerminalProgressIcon(0) }
            } finally {
                g.dispose()
            }
        }
    }
}
