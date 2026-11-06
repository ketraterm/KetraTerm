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
package io.github.ketraterm.app.ui

import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.TerminalProfileKind
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileIconsTest {
    @Test
    fun paintsEveryProfileKindInsideItsDeclaredBounds() {
        val icons = ProfileIcons()
        TerminalProfileKind.entries.forEach { kind ->
            val icon = icons.icon(kind)
            val bounds = render(icon).paintedBounds()
            assertTrue(!bounds.isEmpty, "$kind icon did not paint any pixels")
            assertTrue(Rectangle(5, 6, icon.iconWidth, icon.iconHeight).contains(bounds), "$kind escaped its bounds: $bounds")
            assertEquals(14, icon.iconWidth)
            assertEquals(12, icon.iconHeight)
        }
    }

    @Test
    fun squareAndPortraitLogosKeepTheirProportions() {
        val icons = ProfileIcons()
        val ubuntu = render(icons.icon(TerminalProfileKind.UBUNTU)).paintedBounds()
        assertTrue(abs(ubuntu.width - ubuntu.height) <= 1, "Ubuntu circle was stretched: $ubuntu")
        val wsl = render(icons.icon(TerminalProfileKind.WSL)).paintedBounds()
        assertTrue(wsl.height > wsl.width, "WSL portrait was stretched: $wsl")
    }

    @Test
    fun cachedGenericIconFollowsPaletteChanges() {
        val icon = ProfileIcons().icon(TerminalProfileKind.DEFAULT)
        try {
            TerminalTheme.entries.forEach { theme ->
                Chrome.applyPalette(theme.createPalette())
                val image = render(icon)
                var readablePixels = 0
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val pixel = image.getRGB(x, y)
                        if (pixel ushr 24 < 200) continue
                        val contrast = Chrome.contrastRatio(Color(pixel, true), Chrome.tabSelectedBackground)
                        if (contrast >= 3.0) readablePixels++
                    }
                }
                assertTrue(readablePixels >= 12, "${theme.name} generic icon was not readable: $readablePixels pixels")
            }
        } finally {
            Chrome.applyPalette(TerminalTheme.entries.first().createPalette())
        }
    }

    private fun render(icon: Icon): BufferedImage =
        BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                icon.paintIcon(null, graphics, 5, 6)
            } finally {
                graphics.dispose()
            }
        }

    private fun BufferedImage.paintedBounds(): Rectangle {
        val bounds = Rectangle()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getRGB(x, y) ushr 24 < 32) continue
                if (bounds.isEmpty) bounds.setBounds(x, y, 1, 1) else bounds.add(Rectangle(x, y, 1, 1))
            }
        }
        return bounds
    }
}
