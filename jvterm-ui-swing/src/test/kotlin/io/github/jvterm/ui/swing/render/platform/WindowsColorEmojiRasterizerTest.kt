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
package io.github.jvterm.ui.swing.render.platform

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsColorEmojiRasterizerTest {
    @Test
    fun `installed Windows emoji font rasterizes color layers`() {
        assumeTrue(System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("windows"))

        val rasterizer = WindowsColorEmojiRasterizer.create()
        assumeTrue(rasterizer.available)

        val image = rasterizer.rasterize("\uD83D\uDE00", 32)
        assertNotNull(image)
        assertTrue(
            image.hasSaturatedVisibleColor(),
            "Windows emoji rasterizer produced a monochrome or empty image",
        )
    }

    private fun java.awt.image.BufferedImage.hasSaturatedVisibleColor(): Boolean {
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val argb = getRGB(x, y)
                if ((argb ushr 24) > 32) {
                    val red = argb shr 16 and 0xFF
                    val green = argb shr 8 and 0xFF
                    val blue = argb and 0xFF
                    if (maxOf(red, green, blue) - minOf(red, green, blue) > 24) {
                        return true
                    }
                }
                x++
            }
            y++
        }
        return false
    }
}
