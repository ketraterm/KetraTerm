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
package io.github.jvterm.ui.swing.settings

import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.api.TerminalRenderCursorShape
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.RenderingHints
import java.util.*

/**
 * Immutable Swing terminal UI settings.
 *
 * Hosts can replace this value and call
 * [TerminalSwingTerminal.reloadSettings] to rebuild metrics and repaint.
 *
 * @property font primary terminal font.
 * @property fallbackFonts ordered fonts used by the complex-text renderer when
 * [font] cannot display a Unicode scalar cell or grapheme cluster.
 * @property useSystemFallbackFonts whether the complex-text renderer may use
 * installed system fonts after [fallbackFonts] fail. System font discovery is
 * asynchronous and disabled by default to keep Swing startup and painting
 * responsive.
 * @property palette resolved terminal color palette.
 * @property columns initial preferred column count.
 * @property rows initial preferred row count.
 * @property treatAmbiguousAsWide whether future East Asian Ambiguous
 * codepoints should occupy two terminal cells in core width policy.
 * @property cursorBlinkMillis cursor blink period in milliseconds. A value of
 * zero disables cursor blinking and keeps the cursor visible.
 * @property textAntialiasing text antialiasing hint used during painting.
 * @property fractionalMetrics fractional font metrics hint used during painting.
 * @property clipboardShortcuts platform clipboard key bindings.
 * @property hyperlinkActivationForeground packed ARGB foreground used for the
 * linked span currently under Ctrl-hover.
 * @property selectionBackground packed ARGB overlay used for visible terminal
 * selection ranges.
 * @property searchMatchBackground packed ARGB overlay used for non-active
 * search result ranges.
 * @property searchActiveMatchBackground packed ARGB overlay used for the active
 * search result.
 * @property padding internal margins around the terminal grid in pixels.
 * @property pasteOnMiddleClick whether middle mouse button click triggers a clipboard paste.
 * @property cursorShape default cursor shape configured for the session.
 * @property scrollbackLines maximum scrollback lines retained by the terminal.
 * @property lineHeight vertical line height scaling factor.
 */
data class TerminalSwingSettings
    @JvmOverloads
    constructor(
        val font: Font = defaultTerminalFont(),
        val fallbackFonts: List<Font> = defaultFallbackFonts(),
        val useSystemFallbackFonts: Boolean = false,
        val palette: TerminalColorPalette = defaultPalette(),
        val columns: Int = 80,
        val rows: Int = 24,
        val treatAmbiguousAsWide: Boolean = false,
        val cursorBlinkMillis: Int = 600,
        val textAntialiasing: Any = RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
        val fractionalMetrics: Any = RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
        val clipboardShortcuts: TerminalClipboardShortcuts = TerminalClipboardShortcuts.platformDefault(),
        val hyperlinkActivationForeground: Int = DEFAULT_HYPERLINK_ACTIVATION_FOREGROUND,
        val selectionBackground: Int = DEFAULT_SELECTION_BACKGROUND,
        val searchMatchBackground: Int = DEFAULT_SEARCH_MATCH_BACKGROUND,
        val searchActiveMatchBackground: Int = DEFAULT_SEARCH_ACTIVE_MATCH_BACKGROUND,
        val padding: Insets = Insets(12, 12, 12, 12),
        val pasteOnMiddleClick: Boolean = true,
        val cursorShape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK,
        val scrollbackLines: Int = 1000,
        val lineHeight: Float = 1.0f,
        val shellRequestResizeWindow: Boolean = false,
    ) {
        init {
            require(columns > 0) { "columns must be > 0, was $columns" }
            require(rows > 0) { "rows must be > 0, was $rows" }
            require(cursorBlinkMillis >= 0) {
                "cursorBlinkMillis must be >= 0, was $cursorBlinkMillis"
            }
            require(scrollbackLines >= 0) {
                "scrollbackLines must be >= 0, was $scrollbackLines"
            }
            require(lineHeight > 0f) {
                "lineHeight must be > 0, was $lineHeight"
            }
        }

        companion object {
            private const val DEFAULT_FONT_SIZE = 16
            private const val DEFAULT_HYPERLINK_ACTIVATION_FOREGROUND = 0xFF4DA3FF.toInt()
            private const val DEFAULT_SELECTION_BACKGROUND = 0x66FFFFFF
            private const val DEFAULT_SEARCH_MATCH_BACKGROUND = 0x55FFD54F
            private const val DEFAULT_SEARCH_ACTIVE_MATCH_BACKGROUND = 0xAAFF8C00.toInt()
            private val preferredDefaultFontFamilies =
                arrayOf(
                    "Cascadia Mono",
                    "Cascadia Code",
                    "Consolas",
                    Font.MONOSPACED,
                )
            private val resolvedDefaultTerminalFont: Font by lazy(LazyThreadSafetyMode.PUBLICATION) {
                Font(resolveDefaultFontFamily(), Font.PLAIN, DEFAULT_FONT_SIZE)
            }

            /**
             * Returns the default terminal font used when hosts do not provide one.
             *
             * The preferred families match common modern Windows terminal defaults,
             * with the logical monospaced font as a portable fallback.
             */
            @JvmStatic
            fun defaultTerminalFont(): Font = resolvedDefaultTerminalFont

            /**
             * Returns conservative logical and common platform fonts for complex
             * script fallback. Color emoji fonts are preferred ahead of symbol fonts
             * so emoji cells do not degrade to monochrome dingbat glyphs when a
             * native color emoji family is installed.
             *
             * Hosts can replace this list with their own font resolver policy.
             */
            @JvmStatic
            fun defaultFallbackFonts(): List<Font> {
                val installedFamilies =
                    GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .availableFontFamilyNames
                return fallbackFontFamiliesForInstalledFonts(installedFamilies)
                    .map { family -> Font(family, Font.PLAIN, DEFAULT_FONT_SIZE) }
            }

            internal fun fallbackFontFamiliesForInstalledFonts(installedFamilies: Array<String>): List<String> {
                val installedByLowercase = LinkedHashMap<String, String>(installedFamilies.size)
                for (family in installedFamilies) {
                    installedByLowercase.putIfAbsent(family.lowercase(Locale.ROOT), family)
                }

                val result = ArrayList<String>(DEFAULT_FALLBACK_FONT_FAMILY_CAPACITY)

                fun addIfAbsent(family: String) {
                    if (result.none { it.equals(family, ignoreCase = true) }) {
                        result += family
                    }
                }

                fun addInstalled(preferredFamily: String) {
                    val installed = installedByLowercase[preferredFamily.lowercase(Locale.ROOT)]
                    if (installed != null) addIfAbsent(installed)
                }

                for (family in preferredColorEmojiFallbackFamilies) {
                    addInstalled(family)
                }
                addIfAbsent(Font.DIALOG)
                addIfAbsent(Font.SANS_SERIF)
                for (family in preferredTextFallbackFamilies) {
                    addInstalled(family)
                }
                return result
            }

            /**
             * Returns the default Swing terminal palette.
             *
             * Theme colors live in the Swing layer so the dependency-free render
             * API can remain renderer-neutral.
             */
            @JvmStatic
            fun defaultPalette(): TerminalColorPalette = TerminalTheme.CAMPBELL.createPalette()

            private fun resolveDefaultFontFamily(): String {
                val installedFamilies =
                    GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .availableFontFamilyNames
                for (preferredFamily in preferredDefaultFontFamilies) {
                    for (installedFamily in installedFamilies) {
                        if (installedFamily.equals(preferredFamily, ignoreCase = true)) {
                            return installedFamily
                        }
                    }
                }
                return Font.MONOSPACED
            }

            private const val DEFAULT_FALLBACK_FONT_FAMILY_CAPACITY = 16
            private val preferredColorEmojiFallbackFamilies =
                arrayOf(
                    "Segoe UI Emoji",
                    "Apple Color Emoji",
                    "Noto Color Emoji",
                    "Twemoji Mozilla",
                    "EmojiOne Color",
                    "JoyPixels",
                    "Twitter Color Emoji",
                )
            private val preferredTextFallbackFamilies =
                arrayOf(
                    "Nirmala UI",
                    "Segoe UI",
                    "Segoe UI Symbol",
                    "Segoe UI Historic",
                    "Noto Sans Devanagari",
                    "Noto Sans Bengali",
                    "Noto Sans Tamil",
                    "Noto Sans Khmer",
                    "Noto Sans Sinhala",
                    "Noto Serif Devanagari",
                    "Noto Serif Bengali",
                    "Noto Serif Tamil",
                    "Noto Serif Khmer",
                    "Noto Serif Sinhala",
                    "Mangal",
                    "Vrinda",
                    "Latha",
                    "Khmer UI",
                    "Iskoola Pota",
                    "Ebrima",
                    "Leelawadee UI",
                    "Nyala",
                    "Abyssinica SIL",
                    "Noto Sans Thai",
                    "Noto Sans Ethiopic",
                    "Noto Sans Runic",
                    "Noto Sans CJK SC",
                )
        }
    }

/**
 * Built-in terminal color themes with verified correct ANSI color mappings.
 */
enum class TerminalTheme {
    CAMPBELL,
    ONE_DARK,
    NORD,
    TOKYO_NIGHT,
    EVERFOREST,
    ;

    fun createPalette(): TerminalColorPalette =
        when (this) {
            CAMPBELL ->
                TerminalColorPalette(
                    defaultForeground = 0xFFF2F2F2.toInt(),
                    defaultBackground = 0xFF0C0C0C.toInt(),
                    selectionForeground = 0xFFFFFFFF.toInt(),
                    selectionBackground = 0xFF3B78FF.toInt(),
                    cursorForeground = 0xFF0C0C0C.toInt(),
                    cursorBackground = 0xFFF2F2F2.toInt(),
                    indexedColors =
                        createIndexedColors(
                            intArrayOf(
                                0xFF0C0C0C.toInt(), // 0: Black
                                0xFFC50F1F.toInt(), // 1: Red
                                0xFF13A10E.toInt(), // 2: Green
                                0xFFC19C00.toInt(), // 3: Yellow
                                0xFF0037DA.toInt(), // 4: Blue
                                0xFF881798.toInt(), // 5: Magenta
                                0xFF3A96DD.toInt(), // 6: Cyan
                                0xFFCCCCCC.toInt(), // 7: White
                                0xFF767676.toInt(), // 8: Bright Black
                                0xFFE74856.toInt(), // 9: Bright Red
                                0xFF16C60C.toInt(), // 10: Bright Green
                                0xFFF9F1A5.toInt(), // 11: Bright Yellow
                                0xFF3B78FF.toInt(), // 12: Bright Blue
                                0xFFB4009E.toInt(), // 13: Bright Magenta
                                0xFF61D6D6.toInt(), // 14: Bright Cyan
                                0xFFF2F2F2.toInt(), // 15: Bright White
                            ),
                        ),
                    boldAsBright = true,
                )
            ONE_DARK ->
                TerminalColorPalette(
                    defaultForeground = 0xFFABB2BF.toInt(),
                    defaultBackground = 0xFF1E2127.toInt(),
                    selectionForeground = 0xFFFFFFFF.toInt(),
                    selectionBackground = 0xFF404859.toInt(),
                    cursorForeground = 0xFF1E2127.toInt(),
                    cursorBackground = 0xFFABB2BF.toInt(),
                    indexedColors =
                        createIndexedColors(
                            intArrayOf(
                                0xFF1E2127.toInt(), // 0: Black
                                0xFFE06C75.toInt(), // 1: Red
                                0xFF98C379.toInt(), // 2: Green
                                0xFFD19A66.toInt(), // 3: Yellow
                                0xFF61AFEF.toInt(), // 4: Blue
                                0xFFC678DD.toInt(), // 5: Magenta
                                0xFF56B6C2.toInt(), // 6: Cyan
                                0xFFABB2BF.toInt(), // 7: White
                                0xFF5C6370.toInt(), // 8: Bright Black
                                0xFFF48C96.toInt(), // 9: Bright Red
                                0xFFABE188.toInt(), // 10: Bright Green
                                0xFFF3D89D.toInt(), // 11: Bright Yellow
                                0xFF7EC2FC.toInt(), // 12: Bright Blue
                                0xFFDE9BF2.toInt(), // 13: Bright Magenta
                                0xFF70E1EC.toInt(), // 14: Bright Cyan
                                0xFFFFFFFF.toInt(), // 15: Bright White
                            ),
                        ),
                    boldAsBright = true,
                )
            NORD ->
                TerminalColorPalette(
                    defaultForeground = 0xFFD8DEE9.toInt(),
                    defaultBackground = 0xFF2E3440.toInt(),
                    selectionForeground = 0xFFECEFF4.toInt(),
                    selectionBackground = 0xFF434C5E.toInt(),
                    cursorForeground = 0xFF2E3440.toInt(),
                    cursorBackground = 0xFFD8DEE9.toInt(),
                    indexedColors =
                        createIndexedColors(
                            intArrayOf(
                                0xFF3B4252.toInt(), // 0: Black
                                0xFFBF616A.toInt(), // 1: Red
                                0xFFA3BE8C.toInt(), // 2: Green
                                0xFFEBCB8B.toInt(), // 3: Yellow
                                0xFF81A1C1.toInt(), // 4: Blue
                                0xFFB48EAD.toInt(), // 5: Magenta
                                0xFF88C0D0.toInt(), // 6: Cyan
                                0xFFE5E9F0.toInt(), // 7: White
                                0xFF4C566A.toInt(), // 8: Bright Black
                                0xFFD08770.toInt(), // 9: Bright Red
                                0xFFA3BE8C.toInt(), // 10: Bright Green
                                0xFFEBCB8B.toInt(), // 11: Bright Yellow
                                0xFF88C0D0.toInt(), // 12: Bright Blue
                                0xFFB48EAD.toInt(), // 13: Bright Magenta
                                0xFF8FBCBB.toInt(), // 14: Bright Cyan
                                0xFFECEFF4.toInt(), // 15: Bright White
                            ),
                        ),
                    boldAsBright = true,
                )
            TOKYO_NIGHT ->
                TerminalColorPalette(
                    defaultForeground = 0xFFA9B1D6.toInt(),
                    defaultBackground = 0xFF1A1B26.toInt(),
                    selectionForeground = 0xFFC0CAF5.toInt(),
                    selectionBackground = 0xFF33467C.toInt(),
                    cursorForeground = 0xFF1A1B26.toInt(),
                    cursorBackground = 0xFFA9B1D6.toInt(),
                    indexedColors =
                        createIndexedColors(
                            intArrayOf(
                                0xFF15161E.toInt(), // 0: Black
                                0xFFF7768E.toInt(), // 1: Red
                                0xFF9ECE6A.toInt(), // 2: Green
                                0xFFE0AF68.toInt(), // 3: Yellow
                                0xFF7AA2F7.toInt(), // 4: Blue
                                0xFFBB9AF7.toInt(), // 5: Magenta
                                0xFF7DCFFF.toInt(), // 6: Cyan
                                0xFFA9B1D6.toInt(), // 7: White
                                0xFF414868.toInt(), // 8: Bright Black
                                0xFFFF9E64.toInt(), // 9: Bright Red
                                0xFF9ECE6A.toInt(), // 10: Bright Green
                                0xFFE0AF68.toInt(), // 11: Bright Yellow
                                0xFF7AA2F7.toInt(), // 12: Bright Blue
                                0xFFBB9AF7.toInt(), // 13: Bright Magenta
                                0xFF7DCFFF.toInt(), // 14: Bright Cyan
                                0xFFC0CAF5.toInt(), // 15: Bright White
                            ),
                        ),
                    boldAsBright = true,
                )
            EVERFOREST ->
                TerminalColorPalette(
                    defaultForeground = 0xFFD3C6AA.toInt(),
                    defaultBackground = 0xFF2D353B.toInt(),
                    selectionForeground = 0xFFE6E2CC.toInt(),
                    selectionBackground = 0xFF475258.toInt(),
                    cursorForeground = 0xFF2D353B.toInt(),
                    cursorBackground = 0xFFD3C6AA.toInt(),
                    indexedColors =
                        createIndexedColors(
                            intArrayOf(
                                0xFF343F44.toInt(), // 0: Black
                                0xFFE67E80.toInt(), // 1: Red
                                0xFFA7C080.toInt(), // 2: Green
                                0xFFDBBC7F.toInt(), // 3: Yellow
                                0xFF7FBBB3.toInt(), // 4: Blue
                                0xFFD699B6.toInt(), // 5: Magenta
                                0xFF83C092.toInt(), // 6: Cyan
                                0xFFD3C6AA.toInt(), // 7: White
                                0xFF475258.toInt(), // 8: Bright Black
                                0xFFE67E80.toInt(), // 9: Bright Red
                                0xFFA7C080.toInt(), // 10: Bright Green
                                0xFFDBBC7F.toInt(), // 11: Bright Yellow
                                0xFF7FBBB3.toInt(), // 12: Bright Blue
                                0xFFD699B6.toInt(), // 13: Bright Magenta
                                0xFF83C092.toInt(), // 14: Bright Cyan
                                0xFFE6E2CC.toInt(), // 15: Bright White
                            ),
                        ),
                    boldAsBright = true,
                )
        }

    private fun createIndexedColors(ansi16: IntArray): IntArray {
        val colors = TerminalColorPalette.defaultIndexedColors()
        ansi16.copyInto(colors)
        return colors
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
