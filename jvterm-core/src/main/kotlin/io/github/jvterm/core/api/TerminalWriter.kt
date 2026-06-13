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
package io.github.jvterm.core.api

import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.UnderlineStyle

/**
 * Write-side contract for the terminal buffer.
 *
 * Consumed by the ANSI parser to push character data and control codes
 * into the active screen. All operations target the cursor's current position
 * unless otherwise stated.
 *
 * The core is intentionally spatial rather than temporal. Parser layers are
 * expected to decide grapheme-cluster boundaries and call either the scalar
 * fast path ([writeCodepoint]) or the explicit cluster ingress ([writeCluster]).
 */
interface TerminalWriter {
    /**
     * Writes one Unicode scalar value at the cursor position using the active
     * pen attributes, then advances the cursor.
     *
     * This is the core fast path for simple printable text. It does not perform
     * grapheme segmentation or merge combining marks into a previous cell; a
     * parser/segmenter must dispatch pre-segmented grapheme clusters via
     * [writeCluster].
     *
     * Wrapping, scrolling, and wide-character handling are applied automatically.
     *
     * @param codepoint Unicode codepoint to write.
     */
    fun writeCodepoint(codepoint: Int)

    /**
     * Writes [text] literally to the buffer using the active pen attributes.
     *
     * Control characters (`\n`, `\r`, `\t`, etc.) are not interpreted; they are
     * written as ordinary codepoints. This convenience path is scalar-only: it
     * forwards each decoded codepoint independently and does not perform
     * grapheme segmentation. Use [writeCluster] from a parser/segmenter when a
     * complete grapheme sequence must be written as one cell.
     *
     * @param text Text to write.
     */
    fun writeText(text: String)

    /**
     * Writes one pre-segmented grapheme cluster to the grid.
     *
     * This is the parser-facing ingress for complex printable sequences such as
     * combining-mark clusters, ZWJ emoji, and variation-selector sequences.
     * The core computes the final display width from its active width policy,
     * including East Asian ambiguous-width mode.
     *
     * @param codepoints Codepoints that make up the grapheme cluster.
     * @param length Number of valid codepoints in [codepoints].
     */
    fun writeCluster(
        codepoints: IntArray,
        length: Int = codepoints.size,
    )

    /**
     * Appends one grapheme-continuation codepoint to the most recently written
     * printable cell without moving the cursor.
     *
     * Parser layers call this when a cluster prefix was already published for
     * live rendering and a later byte extends that same grapheme. Core owns the
     * remembered printable-cell target, cluster storage mutation, wide spacer
     * invariants, and cursor preservation.
     *
     * @param codepoint Unicode codepoint to append to the previous grapheme.
     */
    fun appendToPreviousCluster(codepoint: Int)

    /**
     * Executes a line feed (LF, `0x0A`).
     *
     * Moves the cursor down one row without resetting the column. Scrolls the
     * active scroll region up if the cursor is on the bottom margin.
     */
    fun newLine()

    /**
     * Executes Reverse Index (RI, `ESC M`).
     *
     * Moves the cursor up one row without changing the column. Scrolls the
     * active scroll region down if the cursor is on the top margin.
     */
    fun reverseLineFeed()

    /**
     * Executes a carriage return (CR, `0x0D`).
     *
     * Moves the cursor to the active left boundary on the current row. With
     * DECLRMM off that is column 0; with DECLRMM on it is the left margin.
     */
    fun carriageReturn()

    /**
     * Sets the active vertical scroll region (DECSTBM, `CSI top ; bottom r`).
     *
     * [top] and [bottom] are 1-based inclusive row numbers per the DECSTBM
     * convention. Both are clamped to viewport bounds; degenerate ranges are
     * ignored. Homes the cursor per the current DECOM state and active
     * horizontal-margin mode.
     *
     * @param top First row of the scroll region (1-based, inclusive).
     * @param bottom Last row of the scroll region (1-based, inclusive).
     */
    fun setScrollRegion(
        top: Int,
        bottom: Int,
    )

    /**
     * Sets the active horizontal margins (DECSLRM, `CSI left ; right s`).
     *
     * [left] and [right] are 1-based inclusive columns per the DECSLRM
     * convention. The request is ignored unless DECLRMM is active. Degenerate
     * ranges are ignored. A successful margin change homes the cursor.
     *
     * @param left Left margin column (1-based, inclusive).
     * @param right Right margin column (1-based, inclusive).
     */
    fun setLeftRightMargins(
        left: Int,
        right: Int,
    )

    /** Resets the scroll region to the full viewport and homes the cursor. */
    fun resetScrollRegion()

    /**
     * Scrolls the active scroll region up by one line (SU, `CSI 1 S`).
     *
     * The top line may enter scrollback when the region spans the full viewport.
     * The cursor position is preserved.
     */
    fun scrollUp()

    /**
     * Scrolls the active scroll region down by one line (SD, `CSI 1 T`).
     *
     * A blank line is exposed at the top of the region. Scrollback is not
     * consumed. The cursor position is preserved.
     */
    fun scrollDown()

    /**
     * Inserts [count] blank lines at the cursor row within the active scroll
     * region (IL, `CSI n L`).
     *
     * Lines shifted past the bottom margin are discarded. Ignored when the
     * cursor is outside the active scroll region.
     *
     * @param count Number of blank lines to insert. Non-positive values are ignored.
     */
    fun insertLines(count: Int)

    /**
     * Deletes [count] lines starting at the cursor row within the active scroll
     * region (DL, `CSI n M`).
     *
     * Blank lines are exposed at the bottom margin. Ignored when the cursor is
     * outside the active scroll region.
     *
     * @param count Number of lines to delete. Non-positive values are ignored.
     */
    fun deleteLines(count: Int)

    /**
     * Inserts [count] blank cells at the cursor column, shifting existing cells
     * right (ICH, `CSI n @`). Cells pushed past the right margin are discarded.
     *
     * @param count Number of blank cells to insert. Non-positive values are ignored.
     */
    fun insertBlankCharacters(count: Int)

    /**
     * Deletes [count] characters at the cursor column, shifting the remainder of
     * the line left and filling the vacated right cells with blanks using the
     * active pen attribute (DCH, `CSI n P`). Cursor position is not changed.
     *
     * @param count Number of characters to delete. Non-positive values are ignored.
     */
    fun deleteCharacters(count: Int)

    /**
     * Erases [count] characters starting at the cursor column without shifting
     * the remainder of the line (ECH, `CSI n X`).
     *
     * A count of `0` follows VT semantics and erases one character. Negative
     * values are ignored. With DECLRMM active, erasure is clamped to the active
     * horizontal right margin.
     *
     * @param count Number of characters to erase; `0` means `1`.
     */
    fun eraseCharacters(count: Int)

    /** Erases from the cursor to the end of the current line (EL 0, `CSI 0 K`). */
    fun eraseLineToEnd()

    /** Erases from the start of the current line through the cursor (EL 1, `CSI 1 K`). */
    fun eraseLineToCursor()

    /** Erases the entire current line without moving the cursor (EL 2, `CSI 2 K`). */
    fun eraseCurrentLine()

    /** Selectively erases from the cursor to the end of the current line (DECSEL 0). */
    fun selectiveEraseLineToEnd()

    /** Selectively erases from the start of the current line through the cursor (DECSEL 1). */
    fun selectiveEraseLineToCursor()

    /** Selectively erases the entire current line without moving the cursor (DECSEL 2). */
    fun selectiveEraseCurrentLine()

    /** Erases from the cursor to the end of the visible screen (ED 0, `CSI 0 J`). */
    fun eraseScreenToEnd()

    /** Erases from the start of the visible screen through the cursor (ED 1, `CSI 1 J`). */
    fun eraseScreenToCursor()

    /** Selectively erases from the cursor through the end of the visible screen (DECSED 0). */
    fun selectiveEraseScreenToEnd()

    /** Selectively erases from the start of the visible screen through the cursor (DECSED 1). */
    fun selectiveEraseScreenToCursor()

    /** Selectively erases the entire visible screen without moving the cursor (DECSED 2). */
    fun selectiveEraseEntireScreen()

    /** Erases the entire visible screen without moving the cursor (ED 2, `CSI 2 J`). */
    fun eraseEntireScreen()

    /**
     * Erases all scrollback history while preserving the visible viewport
     * (xterm/VTE ED 3, `CSI 3 J`).
     */
    fun eraseScreenAndHistory()

    /**
     * Clears the visible screen and homes the cursor (equivalent to ED 2 + CUP).
     *
     * Scrollback history is preserved. This matches what the shell `clear` command sends.
     */
    fun clearScreen()

    /**
     * Clears all visible content and scrollback history, resets the pen, homes the
     * cursor, clears the DECSC saved-cursor slot, and restores tab stops to the
     * VT100 default spacing.
     *
     * The scroll region is not affected. For a full terminal reset use
     * [TerminalBuffer.reset].
     */
    fun clearAll()

    /**
     * Executes the DEC Screen Alignment Test (DECALN, `ESC # 8`).
     *
     * Fills the entire visible screen viewport with uppercase 'E' characters, resets all vertical
     * and horizontal scrolling regions to the full viewport limits, homes the cursor to (0, 0), and
     * cancels any pending cursor wrap state.
     */
    fun decaln()

    /**
     * Sets the active pen attributes used by all subsequent write and erase operations.
     *
     * Out-of-range colour codes are clamped to the nearest valid value.
     *
     * @param fg Foreground colour code (0 = default, 1..256 = indexed palette colors).
     * @param bg Background colour code (0 = default, 1..256 = indexed palette colors).
     * @param bold `true` to enable bold weight.
     * @param faint `true` to enable faint/dim intensity.
     * @param italic `true` to enable italic style.
     * @param underlineStyle underline presentation style.
     * @param strikethrough `true` to enable strikethrough decoration.
     * @param overline `true` to enable overline decoration.
     * @param blink `true` to enable blinking text presentation.
     * @param inverse `true` to enable inverse/reverse-video.
     * @param conceal `true` to mark text as concealed/hidden.
     * @param underlineColor Underline colour code (0 = default/foreground,
     * 1..256 = indexed palette colors).
     */
    fun setPenAttributes(
        fg: Int,
        bg: Int,
        bold: Boolean = false,
        faint: Boolean = false,
        italic: Boolean = false,
        underlineStyle: UnderlineStyle = UnderlineStyle.NONE,
        strikethrough: Boolean = false,
        overline: Boolean = false,
        blink: Boolean = false,
        inverse: Boolean = false,
        conceal: Boolean = false,
        underlineColor: Int = 0,
    )

    /**
     * Sets the active pen attributes using explicit default, indexed, or RGB colors.
     *
     * [underlineColor] uses [CellColor.DEFAULT] to mean the renderer should
     * derive the underline color from the effective foreground color.
     *
     * @param foreground Foreground color descriptor.
     * @param background Background color descriptor.
     * @param underlineColor Underline color descriptor.
     * @param bold `true` to enable bold weight.
     * @param faint `true` to enable faint/dim intensity.
     * @param italic `true` to enable italic style.
     * @param underlineStyle underline presentation style.
     * @param strikethrough `true` to enable strikethrough decoration.
     * @param overline `true` to enable overline decoration.
     * @param blink `true` to enable blinking text presentation.
     * @param inverse `true` to enable inverse/reverse-video.
     * @param conceal `true` to mark text as concealed/hidden.
     */
    fun setPenColors(
        foreground: CellColor,
        background: CellColor,
        underlineColor: CellColor = CellColor.DEFAULT,
        bold: Boolean = false,
        faint: Boolean = false,
        italic: Boolean = false,
        underlineStyle: UnderlineStyle = UnderlineStyle.NONE,
        strikethrough: Boolean = false,
        overline: Boolean = false,
        blink: Boolean = false,
        inverse: Boolean = false,
        conceal: Boolean = false,
    )

    /**
     * Sets the active OSC 8 hyperlink id stamped onto future printed cells.
     *
     * Core stores only the numeric id. The host or host layer owns the
     * id-to-URI pool and decides how ids are allocated and retained.
     *
     * @param hyperlinkId `0` for no active hyperlink, or a positive id owned by
     * the host/host layer.
     */
    fun setHyperlinkId(hyperlinkId: Int)

    /**
     * Sets the xterm window title.
     *
     * Changes to the title advance the render frame generation.
     */
    fun setWindowTitle(title: String)

    /**
     * Sets the xterm icon title.
     *
     * Changes to the title advance the render frame generation.
     */
    fun setIconTitle(title: String)

    /**
     * Enables or disables selective-erase protection on future printed cells (DECSCA).
     *
     * This affects DECSEL/DECSED only. Normal writes still overwrite protected cells.
     */
    fun setSelectiveEraseProtection(enabled: Boolean)

    /**
     * Resets the active pen to the terminal default attributes (`SGR 0`, `CSI 0 m`).
     */
    fun resetPen()
}
