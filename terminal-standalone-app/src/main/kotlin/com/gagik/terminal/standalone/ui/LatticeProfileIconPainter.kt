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

import com.gagik.terminal.workspace.TerminalProfileKind
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Path2D

/**
 * Paints compact terminal profile glyphs used inside standalone tabs.
 */
internal class LatticeProfileIconPainter {
    private val diamondShape = Path2D.Float()

    fun paint(
        g2: Graphics2D,
        profileKind: TerminalProfileKind,
        x: Int,
        y: Int,
        selected: Boolean,
        highlighted: Boolean,
    ) {
        val opacity = if (selected || highlighted) 255 else 160
        when (profileKind) {
            TerminalProfileKind.POWERSHELL -> paintPowerShell(g2, x, y, opacity)
            TerminalProfileKind.COMMAND_PROMPT -> paintCommandPrompt(g2, x, y, opacity)
            TerminalProfileKind.GIT_BASH -> paintGitBash(g2, x, y, opacity)
            TerminalProfileKind.UBUNTU -> paintUbuntu(g2, x, y, opacity)
            TerminalProfileKind.UNIX_SHELL,
            TerminalProfileKind.DEFAULT,
            -> paintGenericShell(g2, x, y, selected, opacity)
        }
    }

    private fun paintPowerShell(
        g2: Graphics2D,
        x: Int,
        y: Int,
        opacity: Int,
    ) {
        g2.color = Color(0x1F, 0x8A, 0xDD, opacity)
        g2.fillRoundRect(x, y, ICON_WIDTH, ICON_HEIGHT, 3, 3)

        g2.color = Color(255, 255, 255, opacity)
        g2.stroke = LatticeTabMetrics.ICON_STROKE
        g2.drawLine(x + 4, y + 3, x + 7, y + 6)
        g2.drawLine(x + 7, y + 6, x + 4, y + 9)
        g2.drawLine(x + 10, y + 3, x + 8, y + 9)
    }

    private fun paintCommandPrompt(
        g2: Graphics2D,
        x: Int,
        y: Int,
        opacity: Int,
    ) {
        g2.color = Color(0x1E, 0x1E, 0x1E, opacity)
        g2.fillRoundRect(x, y, ICON_WIDTH, ICON_HEIGHT, 2, 2)

        g2.color = Color(0x8C, 0x90, 0x99, opacity)
        g2.stroke = LatticeTabMetrics.HAIRLINE_STROKE
        g2.drawRoundRect(x, y, ICON_WIDTH, ICON_HEIGHT, 2, 2)

        g2.color = Color(0xDF, 0xE1, 0xE5, opacity)
        g2.stroke = LatticeTabMetrics.ICON_DETAIL_STROKE
        g2.drawLine(x + 5, y + 4, x + 3, y + 4)
        g2.drawLine(x + 3, y + 4, x + 3, y + 8)
        g2.drawLine(x + 3, y + 8, x + 5, y + 8)
        g2.drawLine(x + 7, y + 4, x + 9, y + 6)
        g2.drawLine(x + 9, y + 6, x + 7, y + 8)
        g2.drawLine(x + 10, y + 8, x + 11, y + 8)
    }

    private fun paintGitBash(
        g2: Graphics2D,
        x: Int,
        y: Int,
        opacity: Int,
    ) {
        g2.color = Color(0xF1, 0x50, 0x2F, opacity)
        resetDiamondShape(x, y)
        g2.fill(diamondShape)

        g2.color = Color(255, 255, 255, opacity)
        g2.stroke = LatticeTabMetrics.ICON_STROKE
        g2.drawLine(x + 7, y + 3, x + 7, y + 9)
        g2.drawLine(x + 7, y + 6, x + 10, y + 4)
        g2.fillOval(x + 6, y + 2, 2, 2)
        g2.fillOval(x + 6, y + 8, 2, 2)
        g2.fillOval(x + 9, y + 3, 2, 2)
    }

    private fun paintUbuntu(
        g2: Graphics2D,
        x: Int,
        y: Int,
        opacity: Int,
    ) {
        g2.color = Color(0xE9, 0x54, 0x20, opacity)
        g2.fillOval(x + 1, y, 12, 12)

        g2.color = Color(255, 255, 255, opacity)
        g2.stroke = LatticeTabMetrics.HAIRLINE_STROKE
        g2.drawOval(x + 4, y + 3, 6, 6)
        g2.fillOval(x + 2, y + 5, 2, 2)
        g2.fillOval(x + 7, y + 2, 2, 2)
        g2.fillOval(x + 7, y + 8, 2, 2)
    }

    private fun paintGenericShell(
        g2: Graphics2D,
        x: Int,
        y: Int,
        selected: Boolean,
        opacity: Int,
    ) {
        g2.color = if (selected) LatticeChrome.accent else Color(0x9E, 0xA2, 0xA8, opacity)
        g2.stroke = LatticeTabMetrics.ICON_STROKE
        g2.drawRoundRect(x, y, ICON_WIDTH, ICON_HEIGHT, 2, 2)
        g2.drawLine(x + 4, y + 3, x + 7, y + 6)
        g2.drawLine(x + 7, y + 6, x + 4, y + 9)
        g2.drawLine(x + 9, y + 9, x + 11, y + 9)
    }

    private fun resetDiamondShape(
        x: Int,
        y: Int,
    ) {
        diamondShape.reset()
        diamondShape.moveTo((x + 7).toFloat(), y.toFloat())
        diamondShape.lineTo((x + 14).toFloat(), (y + 6).toFloat())
        diamondShape.lineTo((x + 7).toFloat(), (y + 12).toFloat())
        diamondShape.lineTo(x.toFloat(), (y + 6).toFloat())
        diamondShape.closePath()
    }

    private companion object {
        private const val ICON_WIDTH = 14
        private const val ICON_HEIGHT = 12
    }
}
