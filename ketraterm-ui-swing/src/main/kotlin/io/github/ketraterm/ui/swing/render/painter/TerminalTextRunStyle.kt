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
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.api.SwingHyperlinkPresentation
import io.github.ketraterm.ui.swing.api.SwingHyperlinkStyle
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkHover
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.TextContrastCache
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingSelectionStyle
import io.github.ketraterm.ui.swing.settings.SwingSettings

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
    private var hyperlinkHover: TerminalHyperlinkHover? = null
    private var row = 0
    private var activationHover = false
    private var defaultPresentation = SwingHyperlinkPresentation.DEFAULT
    private var osc8Presentation = SwingHyperlinkPresentation.DEFAULT
    private var hyperlinkPresentations: Array<SwingHyperlinkPresentation?>? = null
    private var followedHyperlinkId = 0
    private var hyperlinkStyle: SwingHyperlinkStyle? = null
    private var selectionStart = 0
    private var selectionEnd = 0
    private var selectionBidi: TerminalBidiLayout.Row? = null
    private var selectionStyle = SwingSelectionStyle()
    private val contrast = TextContrastCache()
    private var faintOpacity = 0.5
    private var searchBackgrounds = IntArray(0)
    private var searchForegrounds = IntArray(0)
    private var hasSearchBackgrounds = false
    private var hasSearchForegrounds = false

    /** Reserve row scratch when a frame is installed, before the component paints it. */
    fun prepareColumns(columns: Int) {
        if (searchBackgrounds.size < columns) {
            searchBackgrounds = IntArray(columns)
            searchForegrounds = IntArray(columns)
        }
    }

    fun configureRow(
        textBlinkVisible: Boolean,
        hyperlinkIds: IntArray,
        hyperlinkHover: TerminalHyperlinkHover?,
        settings: SwingSettings,
        hyperlinkPresentations: Array<SwingHyperlinkPresentation?>? = null,
        followedHyperlinkId: Int = 0,
        row: Int = 0,
        selectionRange: Long = CellSelection.NO_RANGE,
        selectionBidi: TerminalBidiLayout.Row? = null,
        selectionStyle: SwingSelectionStyle = settings.selectionStyle,
        searchHighlights: TerminalSearchViewportHighlights? = null,
        columns: Int = 0,
    ) {
        this.textBlinkVisible = textBlinkVisible
        this.hyperlinkIds = hyperlinkIds
        this.hyperlinkHover = hyperlinkHover
        this.row = row
        activationHover = hyperlinkHover?.activation == true
        defaultPresentation = settings.defaultHyperlinkPresentation
        osc8Presentation = settings.resolvedOsc8HyperlinkPresentation
        this.hyperlinkPresentations = hyperlinkPresentations
        this.followedHyperlinkId = followedHyperlinkId
        selectionStart = if (selectionRange == CellSelection.NO_RANGE) 0 else CellSelection.rangeStart(selectionRange)
        selectionEnd = if (selectionRange == CellSelection.NO_RANGE) 0 else CellSelection.rangeEnd(selectionRange)
        this.selectionBidi = selectionBidi
        this.selectionStyle = selectionStyle
        faintOpacity = settings.faintOpacity
        contrast.configure(settings.minimumContrastRatio)
        val segmentCount = searchHighlights?.segmentCountForRow(row) ?: 0
        hasSearchBackgrounds = segmentCount > 0 && columns > 0
        hasSearchForegrounds = hasSearchBackgrounds &&
            (settings.searchMatchStyle.foreground != null || settings.searchActiveMatchStyle.foreground != null)
        if (hasSearchBackgrounds && searchHighlights != null) {
            prepareColumns(columns)
            searchBackgrounds.fill(0, 0, columns)
            if (hasSearchForegrounds) searchForegrounds.fill(0, 0, columns)
            val first = searchHighlights.firstSegmentForRow(row)
            for (segment in first until first + segmentCount) {
                val style = if (searchHighlights.isActive(segment)) settings.searchActiveMatchStyle else settings.searchMatchStyle
                val color = style.background
                val foreground = style.foreground ?: 0
                var column = searchHighlights.startColumn(segment).coerceIn(0, columns)
                val end = searchHighlights.endColumn(segment).coerceIn(0, columns)
                while (column < end) {
                    searchBackgrounds[column] = SwingColors.blendOver(color, searchBackgrounds[column])
                    if (hasSearchForegrounds) searchForegrounds[column] = foreground
                    column++
                }
            }
        }
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
        hovered = hyperlinkHover?.isHovered(hyperlinkId, row, column) == true
        val presentation = if (hyperlinkId > 0) osc8Presentation else hyperlinkPresentations?.get(index)
        hyperlinkStyle =
            when {
                hyperlinkId > 0 -> presentation?.styleFor(hovered, activationHover, hyperlinkId == followedHyperlinkId)
                hyperlinkId < 0 ->
                    presentation?.styleFor(hovered, activationHover, hyperlinkId == followedHyperlinkId)
                        ?: defaultPresentation.styleFor(hovered, activationHover, false)
                else -> null
            }
        foreground = effectiveForeground(palette, attr, cache.codeWords[index], column)
        hyperlinkUnderline = hyperlinkStyle?.underlineStyle ?: TerminalRenderUnderline.NONE
        hyperlinkUnderlineColor = hyperlinkStyle?.underlineArgb ?: foreground
        fontStyle = terminalFontStyle(attr)
        hyperlinkUnderlineThickness = hyperlinkStyle?.underlineThickness ?: 1
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
                hyperlinkIdForCell(hyperlinkIds[index], cache.flags[index]) != hyperlinkId ||
                (hyperlinkHover?.isHovered(hyperlinkId, row, column) == true) != hovered
        ) &&
            effectiveForeground(palette, candidateAttr, cache.codeWords[index], column) == foreground
    }

    private fun effectiveForeground(
        palette: TerminalColorPalette,
        attr: Long,
        codePoint: Int,
        column: Int,
    ): Int {
        val selectionColumn = selectionBidi?.visualColumn(column) ?: column
        val selected = selectionColumn in selectionStart..<selectionEnd
        var background = palette.background(attr)
        val linkBackground = hyperlinkStyle?.backgroundArgb
        if (linkBackground != null && !isTextHidden(attr, textBlinkVisible)) background = SwingColors.blendOver(linkBackground, background)
        if (hasSearchBackgrounds) background = SwingColors.blendOver(searchBackgrounds[column], background)
        if (selected) background = SwingColors.blendOver(SelectionColors.background(palette, selectionStyle, attr), background)
        if (TerminalRenderAttrs.isInvisible(attr)) return background
        val searchForeground = if (hasSearchForegrounds) searchForegrounds[column] else 0
        val inherited =
            if (searchForeground != 0) {
                searchForeground
            } else {
                hyperlinkStyle?.foregroundArgb ?: SwingColors.foreground(
                    palette,
                    attr,
                    codePoint,
                    faintOpacity = faintOpacity,
                    background = background,
                )
            }
        val foreground =
            if (selected) {
                SelectionColors.foreground(palette, selectionStyle, attr, inherited)
            } else {
                inherited
            }
        return if (SwingColors.isGraphicCodePoint(codePoint)) foreground else contrast.resolve(foreground, background)
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
