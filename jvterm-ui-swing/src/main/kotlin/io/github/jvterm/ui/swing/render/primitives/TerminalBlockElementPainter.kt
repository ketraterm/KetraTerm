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

import io.github.jvterm.ui.swing.render.primitives.TerminalBlockElementGlyphs.LOWER_LEFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBlockElementGlyphs.LOWER_RIGHT
import io.github.jvterm.ui.swing.render.primitives.TerminalBlockElementGlyphs.UPPER_LEFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBlockElementGlyphs.UPPER_RIGHT
import java.awt.Graphics2D
import java.awt.Paint

/**
 * Paints Unicode block element glyphs.
 */
internal class TerminalBlockElementPainter {
    private val shadeTextures = TerminalShadeTextureCache()

    fun paint(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        when (codePoint) {
            0x2580 -> paintUpperBlock(g, x, y, width, height, 4)
            in 0x2581..0x2587 -> paintLowerBlock(g, x, y, width, height, codePoint - 0x2580)
            0x2588 -> g.fillRect(x, y, width, height)
            in 0x2589..0x258F -> paintLeftBlock(g, x, y, width, height, 8 - (codePoint - 0x2588))
            0x2590 -> paintRightBlock(g, x, y, width, height, 4)
            0x2594 -> paintUpperBlock(g, x, y, width, height, 1)
            0x2595 -> paintRightBlock(g, x, y, width, height, 1)
            else -> {
                val shadeKind = TerminalBlockElementGlyphs.shadeKind(codePoint)
                if (shadeKind != 0) {
                    paintShade(g, x, y, width, height, shadeKind)
                } else {
                    val mask = TerminalBlockElementGlyphs.quadrantMask(codePoint)
                    if (mask != 0) paintQuadrants(g, x, y, width, height, mask)
                }
            }
        }
    }

    private fun paintLowerBlock(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        eighths: Int,
    ) {
        val blockHeight = maxOf(1, height * eighths / 8)
        g.fillRect(x, y + height - blockHeight, width, blockHeight)
    }

    private fun paintUpperBlock(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        eighths: Int,
    ) {
        g.fillRect(x, y, width, maxOf(1, height * eighths / 8))
    }

    private fun paintLeftBlock(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        eighths: Int,
    ) {
        g.fillRect(x, y, maxOf(1, width * eighths / 8), height)
    }

    private fun paintRightBlock(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        eighths: Int,
    ) {
        val blockWidth = maxOf(1, width * eighths / 8)
        g.fillRect(x + width - blockWidth, y, blockWidth, height)
    }

    private fun paintQuadrants(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: Int,
    ) {
        val halfWidth = width / 2
        val halfHeight = height / 2
        if (mask and UPPER_LEFT != 0) g.fillRect(x, y, halfWidth, halfHeight)
        if (mask and UPPER_RIGHT != 0) g.fillRect(x + halfWidth, y, width - halfWidth, halfHeight)
        if (mask and LOWER_LEFT != 0) g.fillRect(x, y + halfHeight, halfWidth, height - halfHeight)
        if (mask and LOWER_RIGHT != 0) {
            g.fillRect(x + halfWidth, y + halfHeight, width - halfWidth, height - halfHeight)
        }
    }

    private fun paintShade(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        kind: Int,
    ) {
        val previousPaint: Paint = g.paint
        try {
            g.paint = shadeTextures.texture(kind, g.color.rgb)
            g.fillRect(x, y, width, height)
        } finally {
            g.paint = previousPaint
        }
    }
}
