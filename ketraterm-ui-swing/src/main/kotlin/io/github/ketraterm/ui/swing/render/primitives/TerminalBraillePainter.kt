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
package io.github.ketraterm.ui.swing.render.primitives

import java.awt.Graphics2D
import kotlin.math.roundToInt

/** Paints Unicode Braille patterns on the terminal's two-column, four-row dot grid. */
internal object TerminalBraillePainter {
    fun canPaint(codePoint: Int): Boolean = codePoint in 0x2800..0x28FF

    /** Receives device-pixel cell bounds and nominal dimensions from the primitive router. */
    fun paint(
        g: Graphics2D,
        codePoint: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        nominalCellWidth: Int = width,
        nominalCellHeight: Int = height,
    ) {
        if (width <= 0 || height <= 0) return
        val pattern = codePoint - 0x2800
        val side = maxOf(1, minOf(nominalCellWidth / 4.0, nominalCellHeight / 8.0).roundToInt()).coerceAtMost(minOf(width, height))
        // Unicode dots are numbered down the left column (1,2,3,7), then right (4,5,6,8).
        for (dot in 0 until 8) {
            if (pattern and (1 shl dot) == 0) continue
            val column = if (dot in 3..5 || dot == 7) 1 else 0
            val row = if (dot >= 6) 3 else dot % 3
            val left = ((2 * column + 1) * width / 4 - side / 2).coerceIn(0, width - side)
            val top = ((2 * row + 1) * height / 8 - side / 2).coerceIn(0, height - side)
            g.fillRect(x + left, y + top, side, side)
        }
    }
}
