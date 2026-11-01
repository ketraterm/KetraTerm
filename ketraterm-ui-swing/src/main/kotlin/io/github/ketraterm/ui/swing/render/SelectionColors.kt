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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalColorBehavior
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.ui.swing.settings.SwingSelectionStyle

/** One precedence rule shared by selection fills and text-run resolution. */
internal object SelectionColors {
    /** The historical palette-aware selection fill, resolved once per paint for untouched settings. */
    fun defaultBackground(palette: TerminalColorPalette): Int {
        val configured = palette.selectionBackground
        val fill = if (configured ushr 24 == 0xFF) (configured and 0x00FFFFFF) or 0x66000000 else configured
        val background = palette.defaultBackground
        if (SwingColors.contrastRatio(SwingColors.blendOver(fill, background), background) >= 1.15) return fill
        return if (SwingColors.relativeLuminance(background) < 0.5) 0x66FFFFFF else 0x33000000
    }

    fun dynamicBackground(
        palette: TerminalColorPalette,
        style: SwingSelectionStyle,
    ): Boolean =
        if (palette.colorBehavior and TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND != 0) {
            palette.colorBehavior and TerminalColorBehavior.DYNAMIC_SELECTION_BACKGROUND != 0
        } else {
            style.reverseVideo
        }

    fun background(
        palette: TerminalColorPalette,
        style: SwingSelectionStyle,
        attr: Long,
        fixedBackground: Int = style.background,
    ): Int =
        when {
            dynamicBackground(palette, style) -> palette.foreground(attr)
            palette.colorBehavior and TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND != 0 -> palette.selectionBackground
            else -> fixedBackground
        }

    fun foreground(
        palette: TerminalColorPalette,
        style: SwingSelectionStyle,
        attr: Long,
        inherited: Int,
    ): Int =
        when {
            palette.colorBehavior and TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND != 0 ->
                if (palette.colorBehavior and TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND != 0) {
                    palette.background(attr)
                } else {
                    palette.selectionForeground
                }
            style.reverseVideo -> palette.background(attr)
            else -> style.foreground ?: inherited
        }
}
