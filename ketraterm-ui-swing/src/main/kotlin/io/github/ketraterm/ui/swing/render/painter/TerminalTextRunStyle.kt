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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkActions
import io.github.ketraterm.ui.swing.render.SwingColors
import io.github.ketraterm.ui.swing.render.hyperlinkIdForCell
import io.github.ketraterm.ui.swing.render.isTextHidden
import io.github.ketraterm.ui.swing.render.terminalFontStyle

/**
 * Reusable resolved style for one text run, shared by ASCII, complex-cell and shaped painters.
 *
 * The owning text painter configures the hover identity and blink phase once per row, then
 * calls [begin] before scanning or painting each run. [matches] leaves that run's style intact.
 * All state is painter-local; no style objects or hover ranges are allocated during painting.
 * Like the painter's other scratch buffers, this instance is confined to one paint invocation
 * at a time.
 */
internal class TerminalTextRunStyle {
    var attr: Long = 0L
        private set
    var extraAttr: Long = 0L
        private set
    var hyperlinkId: Int = 0
        private set
    var hovered: Boolean = false
        private set
    var foreground: Int = 0
        private set
    var fontStyle: Int = 0
        private set
    var textHidden: Boolean = false
        private set
    var hyperlinkUnderline: Int = TerminalRenderUnderline.NONE
        private set
    var hyperlinkUnderlineColor: Int = 0
        private set
    var hyperlinkUnderlineThickness: Int = 1
        private set

    private var decoration = 0L
    private var textBlinkVisible = true
    private var hyperlinkIds = IntArray(0)
    private var hoveredHyperlinkId = 0
    private var activationHover = false
    private var activationForeground = 0
    private var hyperlinkStyles: TerminalHyperlinkActions? = null
    private var styleOffset = -1
    private var styleFlags = 0

    fun configureRow(
        textBlinkVisible: Boolean,
        hyperlinkIds: IntArray,
        hoveredHyperlinkId: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
        hyperlinkStyles: TerminalHyperlinkActions? = null,
    ) {
        this.textBlinkVisible = textBlinkVisible
        this.hyperlinkIds = hyperlinkIds
        this.hoveredHyperlinkId = hoveredHyperlinkId
        activationHover = hyperlinkActivationHover
        activationForeground = hyperlinkActivationForeground
        this.hyperlinkStyles = hyperlinkStyles
    }

    fun begin(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        rowOffset: Int,
        column: Int,
    ) {
        val index = rowOffset + column
        attr = cache.attrWords[index]
        extraAttr = cache.extraAttrWords[index]
        hyperlinkId = hyperlinkIdForCell(hyperlinkIds[index], cache.flags[index])
        hovered = isHovered(hyperlinkId)
        styleOffset = hyperlinkStyles?.styleOffset(hyperlinkId, hovered, activationHover) ?: -1
        styleFlags = if (styleOffset < 0) 0 else checkNotNull(hyperlinkStyles).flags(styleOffset)
        foreground = effectiveForeground(palette, attr, cache.codeWords[index], hovered)
        hyperlinkUnderline =
            if (styleFlags and TerminalHyperlinkActions.UNDERLINE_STYLE != 0) {
                styleFlags ushr 8
            } else {
                if (hovered) {
                    TerminalRenderUnderline.SINGLE
                } else if (hyperlinkId >
                    0
                ) {
                    TerminalRenderUnderline.DOTTED
                } else {
                    TerminalRenderUnderline.NONE
                }
            }
        hyperlinkUnderlineColor =
            if (styleFlags and TerminalHyperlinkActions.UNDERLINE_COLOR !=
                0
            ) {
                checkNotNull(hyperlinkStyles).underlineColor(styleOffset)
            } else {
                foreground
            }
        fontStyle = terminalFontStyle(attr)
        hyperlinkUnderlineThickness =
            if (styleFlags and TerminalHyperlinkActions.UNDERLINE_STYLE == 0) {
                if (hovered) 2 else 1
            } else if (styleFlags and TerminalHyperlinkActions.BOLD_UNDERLINE != 0) {
                2
            } else {
                1
            }
        decoration = decorationKey(attr, extraAttr)
        textHidden = isTextHidden(attr, textBlinkVisible)
    }

    fun matches(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        rowOffset: Int,
        column: Int,
    ): Boolean {
        val index = rowOffset + column
        val candidateAttr = cache.attrWords[index]
        return !(
            isTextHidden(candidateAttr, textBlinkVisible) != textHidden ||
                terminalFontStyle(candidateAttr) != fontStyle ||
                decorationKey(candidateAttr, cache.extraAttrWords[index]) != decoration ||
                hyperlinkIdForCell(hyperlinkIds[index], cache.flags[index]) != hyperlinkId
        ) &&
            effectiveForeground(palette, candidateAttr, cache.codeWords[index], hovered) == foreground
    }

    private fun isHovered(hyperlinkId: Int): Boolean = hyperlinkId != 0 && hyperlinkId == hoveredHyperlinkId

    private fun effectiveForeground(
        palette: TerminalColorPalette,
        attr: Long,
        codePoint: Int,
        hovered: Boolean,
    ): Int =
        when {
            styleFlags and TerminalHyperlinkActions.FOREGROUND != 0 -> checkNotNull(hyperlinkStyles).foreground(styleOffset)
            hovered && activationHover && styleFlags == 0 -> activationForeground
            else -> SwingColors.foreground(palette, attr, codePoint)
        }

    private fun decorationKey(
        attr: Long,
        extraAttr: Long,
    ): Long =
        TerminalRenderAttrs.underlineStyle(attr).toLong() or
            (if (TerminalRenderAttrs.isStrikethrough(attr)) STRIKETHROUGH_KEY else 0L) or
            (extraAttr shl EXTRA_ATTR_KEY_SHIFT)

    private companion object {
        private const val STRIKETHROUGH_KEY = 1L shl 8
        private const val EXTRA_ATTR_KEY_SHIFT = 9
    }
}
