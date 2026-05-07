package com.gagik.terminal.ui.swing.settings

import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import java.awt.Font
import java.awt.RenderingHints

/**
 * Immutable Swing terminal UI settings.
 *
 * Hosts can replace this value and call
 * [TerminalSwingTerminal.reloadSettings] to rebuild metrics and repaint.
 *
 * @property font primary terminal font.
 * @property palette resolved terminal color palette.
 * @property columns initial preferred column count.
 * @property rows initial preferred row count.
 * @property cursorBlinkMillis cursor blink period in milliseconds.
 * @property textAntialiasing text antialiasing hint used during painting.
 */
data class TerminalSwingSettings(
    val font: Font = Font(Font.MONOSPACED, Font.PLAIN, 14),
    val palette: TerminalColorPalette = TerminalColorPalette(),
    val columns: Int = 80,
    val rows: Int = 24,
    val cursorBlinkMillis: Int = 600,
    val textAntialiasing: Any = RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
) {
    init {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(rows > 0) { "rows must be > 0, was $rows" }
        require(cursorBlinkMillis > 0) {
            "cursorBlinkMillis must be > 0, was $cursorBlinkMillis"
        }
    }
}

/**
 * Provides immutable settings snapshots to [TerminalSwingTerminal].
 */
fun interface TerminalSwingSettingsProvider {
    /**
     * Returns the current immutable settings snapshot.
     *
     * @return settings snapshot for metrics, colors, and painting hints.
     */
    fun currentSettings(): TerminalSwingSettings
}
