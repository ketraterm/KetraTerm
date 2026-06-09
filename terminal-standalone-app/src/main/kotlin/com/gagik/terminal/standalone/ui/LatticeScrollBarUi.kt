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
package com.gagik.terminal.standalone.ui

import java.awt.Dimension
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * Minimal dark scrollbar skin for the standalone terminal window.
 */
internal class LatticeScrollBarUi : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        trackColor = LatticeChrome.SCROLLBAR_TRACK
        thumbColor = LatticeChrome.SCROLLBAR_THUMB
    }

    override fun paintTrack(
        graphics: Graphics,
        component: JComponent,
        trackBounds: Rectangle,
    ) {
        graphics.color = trackColor
        graphics.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height)
    }

    override fun paintThumb(
        graphics: Graphics,
        component: JComponent,
        thumbBounds: Rectangle,
    ) {
        if (!component.isEnabled || thumbBounds.isEmpty) return

        graphics.color = thumbColor
        graphics.fillRoundRect(
            thumbBounds.x + 2,
            thumbBounds.y + 2,
            maxOf(1, thumbBounds.width - 4),
            maxOf(1, thumbBounds.height - 4),
            8,
            8,
        )
    }

    override fun createDecreaseButton(orientation: Int): JButton = invisibleButton()

    override fun createIncreaseButton(orientation: Int): JButton = invisibleButton()

    private fun invisibleButton(): JButton =
        JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
            isOpaque = false
            isFocusable = false
            border = null
        }
}
