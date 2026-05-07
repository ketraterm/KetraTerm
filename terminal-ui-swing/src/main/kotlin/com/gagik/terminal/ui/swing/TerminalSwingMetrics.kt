package com.gagik.terminal.ui.swing

import java.awt.FontMetrics

/**
 * Frozen terminal cell metrics derived from one settings snapshot.
 *
 * @property cellWidth cell width in pixels.
 * @property cellHeight cell height in pixels.
 * @property baseline text baseline inside a cell in pixels.
 * @property underlineY underline y offset inside a cell in pixels.
 * @property strikethroughY strikethrough y offset inside a cell in pixels.
 * @property overlineY overline y offset inside a cell in pixels.
 * @property cursorStrokeWidth stroke width for bar and underline cursors.
 */
data class TerminalSwingMetrics(
    val cellWidth: Int,
    val cellHeight: Int,
    val baseline: Int,
    val underlineY: Int,
    val strikethroughY: Int,
    val overlineY: Int,
    val cursorStrokeWidth: Int,
) {
    init {
        require(cellWidth > 0) { "cellWidth must be > 0, was $cellWidth" }
        require(cellHeight > 0) { "cellHeight must be > 0, was $cellHeight" }
        require(baseline in 0..cellHeight) {
            "baseline must be within cell height: baseline=$baseline cellHeight=$cellHeight"
        }
        require(cursorStrokeWidth > 0) {
            "cursorStrokeWidth must be > 0, was $cursorStrokeWidth"
        }
    }

    companion object {
        /**
         * Builds frozen cell metrics from AWT font metrics.
         *
         * @param fontMetrics metrics for the configured terminal font.
         * @return stable metrics for terminal grid painting.
         */
        @JvmStatic
        fun from(fontMetrics: FontMetrics): TerminalSwingMetrics {
            val cellWidth = maxOf(1, fontMetrics.charWidth('W'))
            val cellHeight = maxOf(1, fontMetrics.height)
            val baseline = fontMetrics.ascent
            val underlineY = minOf(cellHeight - 1, baseline + 1)
            val strikethroughY = maxOf(0, baseline - fontMetrics.ascent / 3)
            return TerminalSwingMetrics(
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                baseline = baseline,
                underlineY = underlineY,
                strikethroughY = strikethroughY,
                overlineY = 0,
                cursorStrokeWidth = maxOf(1, cellWidth / 8),
            )
        }
    }
}
