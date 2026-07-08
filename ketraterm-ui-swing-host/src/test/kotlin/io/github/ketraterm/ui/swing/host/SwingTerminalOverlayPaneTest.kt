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

import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SwingTerminalOverlayPaneTest {
    @Test
    fun overlayDoesNotContributeToPanePreferredSize() {
        val content =
            JPanel().apply {
                preferredSize = Dimension(640, 400)
            }
        val overlay =
            JPanel().apply {
                preferredSize = Dimension(240, 48)
            }
        val pane = SwingTerminalOverlayPane(content, overlay)

        assertEquals(Dimension(640, 400), pane.preferredSize)
    }

    @Test
    fun laysOutContentFullBleedAndOverlayAtTopTrailingEdge() {
        val content =
            JPanel().apply {
                preferredSize = Dimension(640, 400)
            }
        val overlay =
            JPanel().apply {
                preferredSize = Dimension(240, 48)
            }
        val pane = SwingTerminalOverlayPane(content, overlay)

        SwingUtilities.invokeAndWait {
            pane.setSize(800, 600)
            pane.doLayout()

            assertEquals(0, content.x)
            assertEquals(0, content.y)
            assertEquals(800, content.width)
            assertEquals(600, content.height)
            assertEquals(548, overlay.x)
            assertEquals(6, overlay.y)
            assertEquals(240, overlay.width)
            assertEquals(48, overlay.height)
            assertFalse(pane.isOptimizedDrawingEnabled)
        }
    }
}
