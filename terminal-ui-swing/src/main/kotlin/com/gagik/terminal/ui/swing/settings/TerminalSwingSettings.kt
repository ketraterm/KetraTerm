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
 * @property fractionalMetrics fractional font metrics hint used during painting.
 * @property fallbackFonts ordered fonts used by the complex-text renderer when
 * [font] cannot display a non-ASCII cluster.
 * @property useSystemFallbackFonts whether the complex-text renderer may scan
 * installed system fonts after [fallbackFonts] fail.
 */
data class TerminalSwingSettings(
    val font: Font = Font(Font.MONOSPACED, Font.PLAIN, 14),
    val fallbackFonts: List<Font> = defaultFallbackFonts(),
    val useSystemFallbackFonts: Boolean = true,
    val palette: TerminalColorPalette = TerminalColorPalette(),
    val columns: Int = 80,
    val rows: Int = 24,
    val cursorBlinkMillis: Int = 600,
    val textAntialiasing: Any = RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
    val fractionalMetrics: Any = RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
) {
    init {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(rows > 0) { "rows must be > 0, was $rows" }
        require(cursorBlinkMillis > 0) {
            "cursorBlinkMillis must be > 0, was $cursorBlinkMillis"
        }
    }

    companion object {
        /**
         * Returns conservative logical and common platform fonts for complex
         * script fallback. Hosts can replace this list with their own font
         * resolver policy.
         */
        @JvmStatic
        fun defaultFallbackFonts(): List<Font> = listOf(
            Font("Dialog", Font.PLAIN, 14),
            Font(Font.SANS_SERIF, Font.PLAIN, 14),
            Font("Segoe UI", Font.PLAIN, 14),
            Font("Segoe UI Symbol", Font.PLAIN, 14),
            Font("Segoe UI Historic", Font.PLAIN, 14),
            Font("Ebrima", Font.PLAIN, 14),
            Font("Leelawadee UI", Font.PLAIN, 14),
            Font("Nyala", Font.PLAIN, 14),
            Font("Abyssinica SIL", Font.PLAIN, 14),
            Font("Noto Sans Thai", Font.PLAIN, 14),
            Font("Noto Sans Ethiopic", Font.PLAIN, 14),
            Font("Noto Sans Runic", Font.PLAIN, 14),
            Font("Noto Sans CJK SC", Font.PLAIN, 14),
            Font("Noto Color Emoji", Font.PLAIN, 14),
        )
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
