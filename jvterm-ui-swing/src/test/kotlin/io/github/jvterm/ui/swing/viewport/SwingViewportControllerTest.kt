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
package io.github.jvterm.ui.swing.viewport

import io.github.jvterm.ui.swing.api.TerminalViewportState
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Insets

class SwingViewportControllerTest {
    private val settings = SwingSettings(padding = Insets(3, 5, 7, 11))
    private val metrics =
        SwingMetrics(
            cellWidth = 10,
            cellHeight = 20,
            baseline = 15,
            underlineY = 16,
            strikethroughY = 10,
            overlineY = 0,
            cursorStrokeWidth = 2,
        )

    @Nested
    inner class GridSizing {
        @Test
        fun `visible grid size uses left horizontal padding and vertical padding`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            val size =
                controller.visibleGridSizeOnEdt(
                    settings = settings,
                    metrics = metrics,
                    componentWidth = 216,
                    componentHeight = 130,
                )

            assertEquals(21, size.width)
            assertEquals(6, size.height)
            assertEquals(size, controller.visibleGridSizeSnapshot())
        }

        @Test
        fun `visible render rows cover partial pixel rows without changing grid rows`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            assertEquals(
                6,
                controller.visibleGridRows(
                    settings = settings,
                    metrics = metrics,
                    componentHeight = 130,
                ),
            )
            assertEquals(
                7,
                controller.visibleRenderRows(
                    settings = settings,
                    metrics = metrics,
                    componentHeight = 131,
                ),
            )
        }

        @Test
        fun `visible grid size clamps tiny components to one cell`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            val size =
                controller.visibleGridSizeOnEdt(
                    settings = settings,
                    metrics = metrics,
                    componentWidth = 1,
                    componentHeight = 1,
                )

            assertEquals(1, size.width)
            assertEquals(1, size.height)
        }
    }

    @Nested
    inner class ViewportPublishing {
        @Test
        fun `publishViewportState stores snapshot and notifies listener`() {
            val listener = RecordingViewportListener()
            val controller = SwingViewportController(listener)

            assertTrue(controller.scrollTo(offsetLines = 12.5, historySize = 100))
            controller.publishViewportState(historySize = 100, visibleRows = 24, renderRows = 25)

            val snapshot = controller.viewportStateSnapshot()
            assertEquals(TerminalViewportState(100, 12.5, 13, 24, 26), snapshot)
            assertEquals(snapshot, listener.lastState)
        }

        @Test
        fun `publishViewportState can update snapshot without listener callback`() {
            val listener = RecordingViewportListener()
            val controller = SwingViewportController(listener)

            controller.scrollTo(offsetLines = 3.0, historySize = 10)
            controller.publishViewportState(
                historySize = 10,
                visibleRows = 5,
                renderRows = 6,
                notifyListener = false,
            )

            assertEquals(TerminalViewportState(10, 3.0, 3, 5, 6), controller.viewportStateSnapshot())
            assertEquals(0, listener.callCount)
        }
    }

    @Nested
    inner class ScrollState {
        @Test
        fun `resize anchoring preserves fractional scroll offset`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 7.75, historySize = 100)
            val requestedOffset = controller.resizeRequestedOffset()
            val fraction = controller.resizeFraction()
            controller.anchorAfterResize(
                newOffset = requestedOffset + 10,
                newHistorySize = 100,
                oldFraction = fraction,
            )

            assertEquals(19, controller.requestedOffset)
            assertEquals(18.75, controller.viewportOffsetForAssertion())
        }

        @Test
        fun `contentYOffset uses divider aware leading stride for smooth scroll`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 2.25, historySize = 10)

            assertEquals(
                -19.5,
                controller.contentYOffset(
                    cacheRows = 8,
                    cacheScrollbackOffset = 3,
                    terminalRows = 6,
                    viewportPixelHeight = 120,
                    visualHeightForTerminalRows = 120,
                    leadingVisualStride = 26,
                ),
            )
        }

        @Test
        fun `contentYOffset does not bottom anchor partial render overscan by itself`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 1.0, historySize = 10)

            assertEquals(
                0.0,
                controller.contentYOffset(
                    cacheRows = 7,
                    cacheScrollbackOffset = 1,
                    terminalRows = 6,
                    viewportPixelHeight = 134,
                    visualHeightForTerminalRows = 120,
                    leadingVisualStride = 20,
                ),
            )
        }

        @Test
        fun `contentYOffset bottom anchors live viewport when dividers overflow terminal rows`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            assertEquals(
                -12.0,
                controller.contentYOffset(
                    cacheRows = 6,
                    cacheScrollbackOffset = 0,
                    terminalRows = 6,
                    viewportPixelHeight = 120,
                    visualHeightForTerminalRows = 132,
                    leadingVisualStride = 20,
                ),
            )
        }

        @Test
        fun `clamp reports whether requested offset changed`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 10.0, historySize = 10)

            assertTrue(controller.clamp(historySize = 3))
            assertEquals(3, controller.requestedOffset)
            assertFalse(controller.clamp(historySize = 3))
        }

        @Test
        fun `reset returns viewport to live output`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 4.5, historySize = 10)
            controller.reset()
            controller.publishViewportState(
                historySize = 10,
                visibleRows = 4,
                renderRows = 4,
                notifyListener = false,
            )

            assertEquals(TerminalViewportState(10, 0.0, 0, 4, 4), controller.viewportStateSnapshot())
        }

        @Test
        fun `scrollTo rejects NaN offsets`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            assertThrows(IllegalArgumentException::class.java) {
                controller.scrollTo(offsetLines = Double.NaN, historySize = 10)
            }
        }
    }

    private class RecordingViewportListener : io.github.jvterm.ui.swing.api.TerminalViewportListener {
        var callCount = 0
        var lastState: TerminalViewportState? = null

        override fun viewportChanged(
            historySize: Int,
            scrollbackOffset: Double,
            renderOffset: Int,
            visibleRows: Int,
            requestedRows: Int,
        ) {
            callCount++
            lastState = TerminalViewportState(historySize, scrollbackOffset, renderOffset, visibleRows, requestedRows)
        }
    }

    private fun SwingViewportController.viewportOffsetForAssertion(): Double {
        publishViewportState(historySize = 100, visibleRows = 1, renderRows = 1, notifyListener = false)
        return viewportStateSnapshot().scrollbackOffset
    }
}
