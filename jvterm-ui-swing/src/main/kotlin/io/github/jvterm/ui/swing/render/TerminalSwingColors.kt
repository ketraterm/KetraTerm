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
package io.github.jvterm.ui.swing.render

import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.api.TerminalRenderAttrs

/**
 * Swing-specific visual interpretation of render colors.
 */
internal object TerminalSwingColors {
    /**
     * Resolves the foreground color Swing should paint for [attrWord].
     */
    fun foreground(
        palette: TerminalColorPalette,
        attrWord: Long,
    ): Int {
        if (TerminalRenderAttrs.isInvisible(attrWord)) {
            return background(palette, attrWord)
        }

        val color = palette.foreground(attrWord)
        return if (TerminalRenderAttrs.isFaint(attrWord)) dim(color) else color
    }

    /**
     * Resolves the background color Swing should paint for [attrWord].
     */
    fun background(
        palette: TerminalColorPalette,
        attrWord: Long,
    ): Int = palette.background(attrWord)

    /**
     * Applies Swing's current faint rendering policy to a packed ARGB color.
     */
    fun dim(color: Int): Int {
        val alpha = color and 0xFF000000.toInt()
        val red = ((color ushr 16) and 0xFF) / 2
        val green = ((color ushr 8) and 0xFF) / 2
        val blue = (color and 0xFF) / 2
        return alpha or (red shl 16) or (green shl 8) or blue
    }
}
