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

import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Host-side overlay container for a terminal component and floating chrome.
 *
 * The content component keeps the pane's preferred, minimum, and maximum sizes.
 * The overlay component is positioned over the content at the top trailing edge
 * and never participates in normal content layout, so opening host chrome does
 * not resize or push down the terminal grid.
 *
 * @param content primary terminal-area component.
 * @param overlay host-owned overlay chrome.
 * @param overlayInsets distance from the pane edges to the overlay bounds.
 */
class SwingTerminalOverlayPane
    @JvmOverloads
    constructor(
        private val content: JComponent,
        private val overlay: JComponent,
        private val overlayInsets: Insets = Insets(DEFAULT_TOP_INSET, 0, 0, DEFAULT_RIGHT_INSET),
    ) : JPanel(null) {
        init {
            isOpaque = false
            add(content)
            add(overlay)
            setComponentZOrder(overlay, 0)
        }

        /**
         * Allows overlapping child painting for the floating overlay.
         *
         * @return always `false`.
         */
        override fun isOptimizedDrawingEnabled(): Boolean = false

        /**
         * Returns the preferred size of the terminal content.
         *
         * @return content preferred size.
         */
        override fun getPreferredSize(): Dimension = content.preferredSize

        /**
         * Returns the minimum size of the terminal content.
         *
         * @return content minimum size.
         */
        override fun getMinimumSize(): Dimension = content.minimumSize

        /**
         * Returns the maximum size of the terminal content.
         *
         * @return content maximum size.
         */
        override fun getMaximumSize(): Dimension = content.maximumSize

        /**
         * Lays out content full-bleed and positions overlay chrome above it.
         */
        override fun doLayout() {
            content.setBounds(0, 0, width, height)
            layoutOverlay(overlay)
        }

        private fun layoutOverlay(component: Component) {
            val preferred = component.preferredSize
            val availableWidth = (width - overlayInsets.left - overlayInsets.right).coerceAtLeast(0)
            val overlayWidth = preferred.width.coerceAtMost(availableWidth)
            val availableHeight = (height - overlayInsets.top - overlayInsets.bottom).coerceAtLeast(0)
            val overlayHeight = preferred.height.coerceAtMost(availableHeight)
            val x = width - overlayInsets.right - overlayWidth
            val y = overlayInsets.top
            component.setBounds(x.coerceAtLeast(overlayInsets.left), y, overlayWidth, overlayHeight)
        }

        private companion object {
            private const val DEFAULT_TOP_INSET = 6
            private const val DEFAULT_RIGHT_INSET = 12
        }
    }
