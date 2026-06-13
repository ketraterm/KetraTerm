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
package io.github.jvterm.ui.swing.render.primitives

import io.github.jvterm.ui.swing.render.TEST_RED
import io.github.jvterm.ui.swing.render.containsColor
import io.github.jvterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPlatformEmojiPainterTest {
    @Test
    fun `default emoji code point is rasterized through platform hook`() {
        val rasterizer = FakeEmojiRasterizer()
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            assertTrue(painter.paintCodePoint(g, 0x1F600, 0, 0, 1, METRICS))
        } finally {
            g.dispose()
        }

        assertEquals(listOf("\uD83D\uDE00"), rasterizer.texts)
        assertTrue(image.containsColor(TEST_RED))
    }

    @Test
    fun `variation selector 16 cluster is rasterized through platform hook`() {
        val rasterizer = FakeEmojiRasterizer()
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val codepoints = intArrayOf(0x2764, 0xFE0F)
        try {
            assertTrue(painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
        } finally {
            g.dispose()
        }

        assertEquals(listOf("\u2764\uFE0F"), rasterizer.texts)
        assertTrue(image.containsColor(TEST_RED))
    }

    @Test
    fun `text presentation cluster stays on Java2D fallback path`() {
        val rasterizer = FakeEmojiRasterizer()
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val codepoints = intArrayOf(0x2764, 0xFE0E)
        try {
            assertFalse(painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
        } finally {
            g.dispose()
        }

        assertTrue(rasterizer.texts.isEmpty())
    }

    private class FakeEmojiRasterizer : TerminalPlatformEmojiRasterizer {
        val texts = mutableListOf<String>()
        override val available: Boolean = true

        override fun rasterize(
            text: String,
            pixelSize: Int,
        ): BufferedImage {
            texts += text
            return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).also { image ->
                image.setRGB(pixelSize / 2, pixelSize / 2, TEST_RED)
            }
        }
    }

    private companion object {
        private val METRICS =
            TerminalSwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 14,
                underlineY = 15,
                strikethroughY = 9,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
    }
}
