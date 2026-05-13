package com.gagik.terminal.ui.swing.render

import com.gagik.terminal.render.api.*
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.settings.TerminalColorPalette
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Point2D

/**
 * Java2D renderer for cached primitive terminal frames.
 *
 * The painter is component-owned and reuses its color, font, and text buffers
 * across paint calls. The ASCII path groups contiguous compatible cells and
 * draws directly from a reusable `CharArray`.
 */
internal class TerminalGridPainter {
    private val colorCache = AwtColorCache()
    private val fontCache = TerminalFontCache()
    private val complexTextLayouts = TerminalComplexTextLayoutCache()
    private val textRun = TerminalTextRunBuffer(INITIAL_TEXT_RUN_CAPACITY)
    private val glyphPosition = Point2D.Float()

    /**
     * Clears [width] x [height] with the terminal default background.
     */
    fun clear(
        g: Graphics2D,
        palette: TerminalColorPalette,
        width: Int,
        height: Int,
    ) {
        fill(g, 0, 0, width, height, palette.defaultBackground)
    }

    /**
     * Paints [cache] into the supplied graphics context.
     */
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        settings: TerminalSwingSettings,
        metrics: TerminalSwingMetrics,
        width: Int,
        height: Int,
        cursorBlinkVisible: Boolean,
    ) {
        val palette = settings.palette
        if (fontCache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts)) {
            complexTextLayouts.clear()
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
        g.font = fontCache.font(Font.PLAIN)
        val fontRenderContext = g.fontRenderContext

        val rows = minOf(cache.rows, height / metrics.cellHeight + 1)
        fill(g, 0, 0, width, height, palette.defaultBackground)

        var row = 0
        while (row < rows) {
            paintRowBackgrounds(g, cache, palette, metrics, row)
            paintRowText(g, cache, palette, metrics, row, fontRenderContext)
            row++
        }

        paintCursor(g, cache, palette, metrics, cursorBlinkVisible, fontRenderContext)
    }

    private fun paintRowBackgrounds(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
    ) {
        val attrRow = cache.attrWords[row]
        val y = row * metrics.cellHeight
        var column = 0
        while (column < cache.columns) {
            val background = palette.background(attrRow[column])
            val start = column

            column++
            while (column < cache.columns && palette.background(attrRow[column]) == background) {
                column++
            }

            fill(
                g = g,
                x = start * metrics.cellWidth,
                y = y,
                width = (column - start) * metrics.cellWidth,
                height = metrics.cellHeight,
                argb = background,
            )
        }
    }

    private fun paintRowText(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val flagsRow = cache.flags[row]
        val attrRow = cache.attrWords[row]
        val codeWordRow = cache.codeWords[row]
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var column = 0

        while (column < cache.columns) {
            val flags = flagsRow[column]
            if (!hasDrawableText(flags)) {
                column++
                continue
            }

            val codeWord = codeWordRow[column]
            if (!isFastAsciiCell(flags, codeWord)) {
                column = paintComplexCell(
                    g = g,
                    cache = cache,
                    palette = palette,
                    metrics = metrics,
                    row = row,
                    column = column,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                )
                continue
            }

            column = paintAsciiRun(
                g = g,
                cache = cache,
                palette = palette,
                metrics = metrics,
                    row = row,
                    startColumn = column,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                )
        }
    }

    private fun paintAsciiRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        startColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        val flagsRow = cache.flags[row]
        val attrRow = cache.attrWords[row]
        val extraAttrRow = cache.extraAttrWords[row]
        val codeWordRow = cache.codeWords[row]
        val attr = attrRow[startColumn]
        val extraAttr = extraAttrRow[startColumn]
        val foreground = palette.foreground(attr)
        val fontStyle = fontStyle(attr)
        val decoration = decorationKey(attr, extraAttr)
        var column = startColumn

        textRun.clear()
        while (column < cache.columns) {
            val flags = flagsRow[column]
            val codeWord = codeWordRow[column]
            val currentAttr = attrRow[column]
            val currentExtraAttr = extraAttrRow[column]
            if (
                !isFastAsciiCell(flags, codeWord) ||
                palette.foreground(currentAttr) != foreground ||
                fontStyle(currentAttr) != fontStyle ||
                decorationKey(currentAttr, currentExtraAttr) != decoration
            ) {
                break
            }

            textRun.appendAscii(codeWord)
            column++
        }

        g.font = fontCache.font(fontStyle)
        g.color = colorCache.color(foreground)
        drawAsciiRun(g, metrics, startColumn, baselineY, fontRenderContext)
        paintDecorations(g, palette, attr, extraAttr, foreground, startColumn, column, row, metrics)
        return column
    }

    private fun paintComplexCell(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        column: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        val flags = cache.flags[row][column]
        val attr = cache.attrWords[row][column]
        val extraAttr = cache.extraAttrWords[row][column]
        val foreground = palette.foreground(attr)
        val fontStyle = fontStyle(attr)
        val endColumn = minOf(cache.columns, column + cellSpan(flags))

        g.font = fontCache.font(fontStyle)
        g.color = colorCache.color(foreground)

        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val cluster = cache.clusters[row][column]
            if (cluster != null) {
                drawComplexCluster(g, cluster, fontStyle, column * metrics.cellWidth, baselineY, fontRenderContext)
            }
        } else {
            drawComplexCodePoint(
                g = g,
                codePoint = cache.codeWords[row][column],
                fontStyle = fontStyle,
                x = column * metrics.cellWidth,
                baselineY = baselineY,
                fontRenderContext = fontRenderContext,
            )
        }

        paintDecorations(g, palette, attr, extraAttr, foreground, column, endColumn, row, metrics)
        return endColumn
    }

    private fun paintDecorations(
        g: Graphics2D,
        palette: TerminalColorPalette,
        attr: Long,
        extraAttr: Long,
        foreground: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: TerminalSwingMetrics,
    ) {
        val underline = TerminalRenderAttrs.underlineStyle(attr)
        val strikethrough = TerminalRenderAttrs.isStrikethrough(attr)
        val overline = TerminalRenderExtraAttrs.isOverline(extraAttr)
        if (underline == TerminalRenderUnderline.NONE && !strikethrough && !overline) return

        val x = startColumn * metrics.cellWidth
        val width = (endColumn - startColumn) * metrics.cellWidth
        val rowY = row * metrics.cellHeight

        if (underline != TerminalRenderUnderline.NONE) {
            g.color = colorCache.color(underlineColor(palette, extraAttr, foreground))
            val y = rowY + metrics.underlineY
            g.drawLine(x, y, x + width, y)
            if (underline == TerminalRenderUnderline.DOUBLE) {
                val secondY = minOf(rowY + metrics.cellHeight - 1, y + 2)
                g.drawLine(x, secondY, x + width, secondY)
            }
        }

        if (strikethrough) {
            g.color = colorCache.color(foreground)
            val y = rowY + metrics.strikethroughY
            g.drawLine(x, y, x + width, y)
        }

        if (overline) {
            g.color = colorCache.color(foreground)
            val y = rowY + metrics.overlineY
            g.drawLine(x, y, x + width, y)
        }
    }

    private fun paintCursor(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        cursorBlinkVisible: Boolean,
        fontRenderContext: FontRenderContext,
    ) {
        val cursor = cache.cursor ?: return
        if (!cursor.visible || (cursor.blinking && !cursorBlinkVisible)) return
        if (cursor.column !in 0 until cache.columns || cursor.row !in 0 until cache.rows) return

        val x = cursor.column * metrics.cellWidth
        val y = cursor.row * metrics.cellHeight
        g.color = colorCache.color(palette.cursorBackground)

        when (cursor.shape) {
            TerminalRenderCursorShape.BLOCK -> g.fillRect(x, y, metrics.cellWidth, metrics.cellHeight)
            TerminalRenderCursorShape.UNDERLINE -> {
                g.fillRect(
                    x,
                    y + metrics.cellHeight - metrics.cursorStrokeWidth,
                    metrics.cellWidth,
                    metrics.cursorStrokeWidth,
                )
            }
            TerminalRenderCursorShape.BAR -> {
                g.fillRect(x, y, metrics.cursorStrokeWidth, metrics.cellHeight)
            }
        }

        if (cursor.shape == TerminalRenderCursorShape.BLOCK) {
            paintCursorForeground(g, cache, palette, metrics, cursor.column, cursor.row, fontRenderContext)
        }
    }

    private fun paintCursorForeground(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        column: Int,
        row: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val flags = cache.flags[row][column]
        if (!hasDrawableText(flags)) return

        val attr = cache.attrWords[row][column]
        val oldClip = g.clip
        try {
            g.clipRect(column * metrics.cellWidth, row * metrics.cellHeight, metrics.cellWidth, metrics.cellHeight)
            g.font = fontCache.font(fontStyle(attr))
            g.color = colorCache.color(palette.cursorForeground)

            val baselineY = row * metrics.cellHeight + metrics.baseline
            if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                val cluster = cache.clusters[row][column]
                if (cluster != null) {
                    drawComplexCluster(
                        g,
                        cluster,
                        fontStyle(attr),
                        column * metrics.cellWidth,
                        baselineY,
                        fontRenderContext,
                    )
                }
            } else {
                drawComplexCodePoint(
                    g = g,
                    codePoint = cache.codeWords[row][column],
                    fontStyle = fontStyle(attr),
                    x = column * metrics.cellWidth,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                )
            }
        } finally {
            g.clip = oldClip
        }
    }

    private fun drawAsciiRun(
        g: Graphics2D,
        metrics: TerminalSwingMetrics,
        startColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val expectedWidth = textRun.length * metrics.cellWidth
        val measuredWidth = g.fontMetrics.charsWidth(textRun.chars, 0, textRun.length)
        if (measuredWidth == expectedWidth) {
            g.drawChars(textRun.chars, 0, textRun.length, startColumn * metrics.cellWidth, baselineY)
            return
        }

        val glyphVector = g.font.layoutGlyphVector(
            fontRenderContext,
            textRun.chars,
            0,
            textRun.length,
            Font.LAYOUT_LEFT_TO_RIGHT,
        )
        val glyphCount = glyphVector.numGlyphs
        var glyph = 0
        while (glyph <= glyphCount) {
            glyphPosition.x = glyph * metrics.cellWidth.toFloat()
            glyphPosition.y = 0f
            glyphVector.setGlyphPosition(glyph, glyphPosition)
            glyph++
        }
        g.drawGlyphVector(glyphVector, (startColumn * metrics.cellWidth).toFloat(), baselineY.toFloat())
    }

    private fun drawComplexCluster(
        g: Graphics2D,
        text: String,
        fontStyle: Int,
        x: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        complexTextLayouts
            .clusterLayout(text, fontStyle, fontRenderContext, fontCache)
            .draw(g, x.toFloat(), baselineY.toFloat())
    }

    private fun drawComplexCodePoint(
        g: Graphics2D,
        codePoint: Int,
        fontStyle: Int,
        x: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        complexTextLayouts
            .codePointLayout(codePoint, fontStyle, fontRenderContext, fontCache)
            .draw(g, x.toFloat(), baselineY.toFloat())
    }

    private fun hasDrawableText(flags: Int): Boolean {
        return flags and TerminalRenderCellFlags.CODEPOINT != 0 ||
            flags and TerminalRenderCellFlags.CLUSTER != 0
    }

    private fun isFastAsciiCell(flags: Int, codeWord: Int): Boolean {
        return flags == TerminalRenderCellFlags.CODEPOINT && codeWord in 0x20..0x7e
    }

    private fun cellSpan(flags: Int): Int {
        return if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
    }

    private fun fontStyle(attr: Long): Int {
        var style = Font.PLAIN
        if (TerminalRenderAttrs.isBold(attr)) style = style or Font.BOLD
        if (TerminalRenderAttrs.isItalic(attr)) style = style or Font.ITALIC
        return style
    }

    private fun decorationKey(attr: Long, extraAttr: Long): Long {
        return TerminalRenderAttrs.underlineStyle(attr).toLong() or
            (if (TerminalRenderAttrs.isStrikethrough(attr)) STRIKETHROUGH_KEY else 0L) or
            (extraAttr shl EXTRA_ATTR_KEY_SHIFT)
    }

    private fun underlineColor(
        palette: TerminalColorPalette,
        extraAttr: Long,
        defaultColor: Int,
    ): Int {
        val value = TerminalRenderExtraAttrs.underlineColorValue(extraAttr)
        return when (TerminalRenderExtraAttrs.underlineColorKind(extraAttr)) {
            TerminalRenderColorKind.DEFAULT -> defaultColor
            TerminalRenderColorKind.INDEXED -> palette.indexedColor(value)
            TerminalRenderColorKind.RGB -> 0xFF000000.toInt() or value
            else -> defaultColor
        }
    }

    private fun fill(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, argb: Int) {
        g.color = colorCache.color(argb)
        g.fillRect(x, y, width, height)
    }

    private companion object {
        private const val INITIAL_TEXT_RUN_CAPACITY = 256
        private const val STRIKETHROUGH_KEY = 1L shl 8
        private const val EXTRA_ATTR_KEY_SHIFT = 9
    }
}
