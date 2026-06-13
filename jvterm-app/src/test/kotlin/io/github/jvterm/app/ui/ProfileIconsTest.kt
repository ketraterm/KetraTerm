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
package io.github.jvterm.app.ui

import io.github.jvterm.workspace.TerminalProfileKind
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileIconsTest {
    @Test
    fun paintsEveryProfileKindFromSvgResources() {
        val icons = ProfileIcons()

        TerminalProfileKind.entries.forEachIndexed { index, kind ->
            val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                icons.paint(
                    g2 = graphics,
                    profileKind = kind,
                    x = 5,
                    y = 6,
                    selected = index % 2 == 0,
                    highlighted = index % 2 != 0,
                )
            } finally {
                graphics.dispose()
            }

            assertTrue(image.hasPaintedPixel(), "$kind icon did not paint any pixels")
        }
    }

    private fun BufferedImage.hasPaintedPixel(): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((getRGB(x, y) ushr 24) != 0) return true
            }
        }
        return false
    }
}
