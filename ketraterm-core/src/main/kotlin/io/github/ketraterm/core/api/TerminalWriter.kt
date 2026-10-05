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
package io.github.ketraterm.core.api

import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle

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
public interface TerminalWriter {
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
     * @throws IllegalArgumentException if [codepoint] is outside `0..0x10FFFF` or a surrogate.
     * Invalid input is rejected before any state mutation.
     */
    public fun writeCodepoint(codepoint: Int)

    /**
     * Writes [text] literally to the buffer using the active pen attributes.
     *
     * Control characters (`\n`, `\r`, `\t`, etc.) are not interpreted; they are
     * written as ordinary codepoints. This convenience path is scalar-only: it
     * forwards each decoded codepoint independently and does not perform
     * grapheme segmentation. Use [writeCluster] from a parser/segmenter when a
     * complete grapheme sequence must be written as one cell.
     * Each unpaired UTF-16 surrogate is replaced with U+FFFD, whose width follows
     * the active ambiguous-width mode like an explicitly supplied U+FFFD.
     *
     * @param text Text to write.
     */
    public fun writeText(text: String)

    /**
     * Writes one pre-segmented grapheme cluster to the grid.
     *
     * This is the parser-facing ingress for complex printable sequences such as
     * combining-mark clusters, ZWJ emoji, and variation-selector sequences.
     * The core computes the final display width from its active width policy,
     * including East Asian ambiguous-width mode.
     * The used prefix is copied into core-owned storage before returning; the caller
     * may immediately reuse the array.
     *
     * @param codepoints Codepoints that make up the grapheme cluster.
     * @param length Number of valid codepoints in [codepoints].
     * @throws IllegalArgumentException if [length] is outside `1..codepoints.size`
     * or any value in the used prefix is not a Unicode scalar. The entire prefix
     * is checked before mutation; unused array entries are ignored.
     */
    public fun writeCluster(
        codepoints: IntArray,
        length: Int = codepoints.size,
    )

    /**
     * Updates the most recently written printable cell with a complete grapheme sequence.
     *
     * The caller owns segmentation and supplies the entire retained sequence, including
     * the previously published prefix. Core preserves the target's attributes, recalculates
     * width, and adjusts its occupied span and following cursor. This does not insert a new
     * cell or advance past a second grapheme. In-row width changes recompute the following
     * cursor and pending wrap; completed wraps, scrolls, overwrites and insert shifts are
     * not reversed. Narrowing blanks the released spacer; widening overwrites the next
     * cell only if it fits inside the right margin, otherwise the cluster stays in one
     * cell. Updates never shift cells, including in insert mode. A write rejected by
     * geometry or disabled autowrap clears the target; invalid input leaves it unchanged.
     * If no remembered printable target remains, valid input is ignored.
     * This operation does not validate grapheme boundaries or
     * compare the supplied prefix with stored text.
     *
     * The used prefix is copied into core-owned storage before returning; the caller may
     * immediately reuse the array. No parser retention limit is imposed on direct callers.
     *
     * @param codepoints Complete retained codepoints of the same grapheme.
     * @param length Number of valid codepoints in [codepoints].
     * @throws IllegalArgumentException if [length] is outside `1..codepoints.size` or any
     * used entry is not a Unicode scalar, even without a target. Validation precedes mutation.
     */
    public fun updatePreviousCluster(
        codepoints: IntArray,
        length: Int = codepoints.size,
    )

    /**
     * Executes a line feed (LF, `0x0A`).
     *
     * Moves the cursor down one row without resetting the column. Scrolls the
     * active scroll region up if the cursor is on the bottom margin.
     */
    public fun newLine()

    /**
     * Executes Reverse Index (RI, `ESC M`).
     *
     * Moves the cursor up one row without changing the column. Scrolls the
     * active scroll region down if the cursor is on the top margin.
     */
    public fun reverseLineFeed()

    /**
     * Executes a carriage return (CR, `0x0D`).
     *
     * Moves the cursor to the active left boundary on the current row. With
     * DECLRMM off that is column 0; with DECLRMM on it is the left margin.
     */
    public fun carriageReturn()

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
    public fun setScrollRegion(
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
    public fun setLeftRightMargins(
        left: Int,
        right: Int,
    )

    /** Resets the scroll region to the full viewport and homes the cursor. */
    public fun resetScrollRegion()

    /**
     * Scrolls the active scroll region up by one line (SU, `CSI 1 S`).
     *
     * Only cells within the active horizontal margins move. A full-width region
     * starting at the top of the viewport may admit its top line to scrollback;
     * partial-width scrolling never does. The cursor position is preserved.
     */
    public fun scrollUp()

    /**
     * Scrolls the active region up by [count] lines, capped at its height.
     * Non-positive counts are ignored. Preserves the cursor position and uses
     * the same horizontal-margin and history-admission rules as [scrollUp].
     * Work is bounded by the grid dimensions, not the supplied count.
     */
    public fun scrollUp(count: Int)

    /**
     * Scrolls the active scroll region down by one line (SD, `CSI 1 T`).
     *
     * Only cells within the active horizontal margins move, exposing blank
     * cells at the top of the region. Scrollback is not consumed. The cursor
     * position is preserved.
     */
    public fun scrollDown()

    /**
     * Scrolls the active region down by [count] lines, capped at its height.
     * Non-positive counts are ignored. Preserves the cursor position and uses
     * the same horizontal-margin rules as [scrollDown], without consuming history.
     * Work is bounded by the grid dimensions, not the supplied count.
     */
    public fun scrollDown(count: Int)

    /**
     * Inserts [count] blank lines at the cursor row within the active scroll
     * region (IL, `CSI n L`).
     *
     * Lines shifted past the bottom margin are discarded. Ignored when the
     * cursor is outside the active scroll region.
     *
     * @param count Number of blank lines to insert. Non-positive values are ignored.
     */
    public fun insertLines(count: Int)

    /**
     * Deletes [count] lines starting at the cursor row within the active scroll
     * region (DL, `CSI n M`).
     *
     * Blank lines are exposed at the bottom margin. Ignored when the cursor is
     * outside the active scroll region.
     *
     * @param count Number of lines to delete. Non-positive values are ignored.
     */
    public fun deleteLines(count: Int)

    /**
     * Inserts [count] blank cells at the cursor column, shifting existing cells
     * right (ICH, `CSI n @`). Cells pushed past the right margin are discarded.
     *
     * @param count Number of blank cells to insert. Non-positive values are ignored.
     */
    public fun insertBlankCharacters(count: Int)

    /**
     * Deletes [count] characters at the cursor column, shifting the remainder of
     * the line left and filling the vacated right cells with blanks using the
     * active pen attribute (DCH, `CSI n P`). Cursor position is not changed.
     *
     * @param count Number of characters to delete. Non-positive values are ignored.
     */
    public fun deleteCharacters(count: Int)

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
    public fun eraseCharacters(count: Int)

    /** Erases from the cursor to the end of the current line (EL 0, `CSI 0 K`). */
    public fun eraseLineToEnd()

    /** Erases from the start of the current line through the cursor (EL 1, `CSI 1 K`). */
    public fun eraseLineToCursor()

    /** Erases the entire current line without moving the cursor (EL 2, `CSI 2 K`). */
    public fun eraseCurrentLine()

    /** Selectively erases from the cursor to the end of the current line (DECSEL 0). */
    public fun selectiveEraseLineToEnd()

    /** Selectively erases from the start of the current line through the cursor (DECSEL 1). */
    public fun selectiveEraseLineToCursor()

    /** Selectively erases the entire current line without moving the cursor (DECSEL 2). */
    public fun selectiveEraseCurrentLine()

    /** Erases from the cursor to the end of the visible screen (ED 0, `CSI 0 J`). */
    public fun eraseScreenToEnd()

    /** Erases from the start of the visible screen through the cursor (ED 1, `CSI 1 J`). */
    public fun eraseScreenToCursor()

    /** Selectively erases from the cursor through the end of the visible screen (DECSED 0). */
    public fun selectiveEraseScreenToEnd()

    /** Selectively erases from the start of the visible screen through the cursor (DECSED 1). */
    public fun selectiveEraseScreenToCursor()

    /** Selectively erases the entire visible screen without moving the cursor (DECSED 2). */
    public fun selectiveEraseEntireScreen()

    /**
     * Erases a VT400 rectangular area (DECERA / DECSERA).
     *
     * Coordinates use DEC's one-based inclusive form. Zero values denote omitted parameters and
     * resolve to the corresponding active-viewport edge. When origin mode is enabled, coordinates
     * are relative to the active scrolling margins. Selective erasure preserves protected cells.
     *
     * @param top One-based top row, or `0` when omitted.
     * @param left One-based left column, or `0` when omitted.
     * @param bottom One-based bottom row, or `0` when omitted.
     * @param right One-based right column, or `0` when omitted.
     * @param selective `true` for DECSERA, `false` for DECERA.
     */
    public fun eraseRectangle(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        selective: Boolean,
    )

    /**
     * Fills a VT420 rectangular area (DECFRA) with a one-cell DEC character.
     *
     * Coordinates use DEC's one-based inclusive form. Zero values denote omitted parameters and
     * resolve to the corresponding active-viewport edge. When origin mode is enabled, coordinates
     * are relative to the active scrolling margins.
     *
     * @param codepoint Decimal fill character. Invalid scalar values are ignored.
     * @param top One-based top row, or `0` when omitted.
     * @param left One-based left column, or `0` when omitted.
     * @param bottom One-based bottom row, or `0` when omitted.
     * @param right One-based right column, or `0` when omitted.
     */
    public fun fillRectangle(
        codepoint: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    )

    /**
     * Copies a VT420 rectangular area (DECCRA) without moving the cursor.
     *
     * Coordinates are DEC one-based values. The source rectangle is inclusive; destination
     * coordinates identify its new upper-left cell. This single-page terminal accepts omitted
     * page parameters (`0`) and page `1`; every other page value is ignored. Source data is
     * snapshotted before destination cells are mutated, so overlapping copies have memmove
     * semantics and preserve grapheme-cluster ownership.
     *
     * @param sourceTop One-based source top row, or `0` when omitted.
     * @param sourceLeft One-based source left column, or `0` when omitted.
     * @param sourceBottom One-based source bottom row, or `0` when omitted.
     * @param sourceRight One-based source right column, or `0` when omitted.
     * @param sourcePage Source page (`0` omitted or `1` for the active page).
     * @param destinationTop One-based destination top row, or `0` when omitted.
     * @param destinationLeft One-based destination left column, or `0` when omitted.
     * @param destinationPage Destination page (`0` omitted or `1` for the active page).
     */
    public fun copyRectangle(
        sourceTop: Int,
        sourceLeft: Int,
        sourceBottom: Int,
        sourceRight: Int,
        sourcePage: Int,
        destinationTop: Int,
        destinationLeft: Int,
        destinationPage: Int,
    )

    /**
     * Selects DECSACE extent for future DECCARA and DECRARA operations.
     *
     * @param extent `0` or `1` selects wrapped stream extent; `2` selects exact rectangle.
     * Unsupported values leave the current extent unchanged.
     */
    public fun setAttributeChangeExtent(extent: Int)

    /**
     * Changes DECCARA visual attributes without changing cell values, protection, hyperlinks, or
     * the current SGR pen. Masks use [io.github.ketraterm.protocol.DecRectangleAttribute] bits.
     */
    public fun changeRectangleAttributes(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        setMask: Int,
        clearMask: Int,
    )

    /**
     * Reverses DECRARA visual attributes without changing cell values, protection, hyperlinks,
     * or the current SGR pen. [reverseMask] uses
     * [io.github.ketraterm.protocol.DecRectangleAttribute] bits.
     */
    public fun reverseRectangleAttributes(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        reverseMask: Int,
    )

    /**
     * Inserts DECIC blank columns at the cursor column across the active vertical scroll region.
     *
     * The operation shifts only through the current right margin, does nothing when the cursor is
     * outside either scrolling margin, and leaves the cursor position unchanged.
     *
     * @param count Number of columns to insert. Non-positive values are ignored.
     */
    public fun insertColumns(count: Int)

    /**
     * Deletes DECDC columns at the cursor column across the active vertical scroll region.
     *
     * The operation shifts only through the current right margin, does nothing when the cursor is
     * outside either scrolling margin, and leaves the cursor position unchanged.
     *
     * @param count Number of columns to delete. Non-positive values are ignored.
     */
    public fun deleteColumns(count: Int)

    /** Erases the entire visible screen without moving the cursor (ED 2, `CSI 2 J`). */
    public fun eraseEntireScreen()

    /**
     * Erases all scrollback history while preserving the visible viewport
     * (xterm/VTE ED 3, `CSI 3 J`).
     */
    public fun eraseScreenAndHistory()

    /**
     * Erases the active screen and history with the current erase attributes.
     * Preserves the cursor position, pen, modes, margins, tabs, saved cursor, and inactive buffer.
     * Cancels pending wrap and replaces erased line identities.
     * The caller must serialize access with all other terminal operations.
     */
    public fun eraseBuffer() {
        eraseEntireScreen()
        eraseScreenAndHistory()
    }

    /**
     * Clears the visible screen and homes the cursor (equivalent to ED 2 + CUP).
     *
     * Scrollback history is preserved. This matches what the shell `clear` command sends.
     */
    public fun clearScreen()

    /**
     * Clears all visible content and scrollback history, resets the pen, homes the
     * cursor, clears the DECSC saved-cursor slot, and restores tab stops to the
     * VT100 default spacing.
     *
     * The scroll region is not affected. For a full terminal reset use
     * [TerminalBuffer.reset].
     */
    public fun clearAll()

    /**
     * Executes the DEC Screen Alignment Test (DECALN, `ESC # 8`).
     *
     * Fills the entire visible screen viewport with uppercase 'E' characters, resets all vertical
     * and horizontal scrolling regions to the full viewport limits, homes the cursor to (0, 0), and
     * cancels any pending cursor wrap state.
     */
    public fun decaln()

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
    public fun setPenAttributes(
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
    public fun setPenColors(
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
     * Updates only the supplied SGR pen fields. A null field is unchanged;
     * [CellColor.DEFAULT] explicitly selects the default color and `false` clears a flag.
     * Hyperlink and selective-erase protection are preserved. With all fields null this is a no-op.
     * Consumed synchronously; serialize with other terminal reads and writes. No cell is changed.
     */
    public fun updatePenColors(
        foreground: CellColor? = null,
        background: CellColor? = null,
        underlineColor: CellColor? = null,
        bold: Boolean? = null,
        faint: Boolean? = null,
        italic: Boolean? = null,
        underlineStyle: UnderlineStyle? = null,
        strikethrough: Boolean? = null,
        overline: Boolean? = null,
        blink: Boolean? = null,
        inverse: Boolean? = null,
        conceal: Boolean? = null,
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
    public fun setHyperlinkId(hyperlinkId: Int)

    /**
     * Sets the xterm window title.
     *
     * Changes to the title advance the render frame generation.
     *
     * @param title The window title to set.
     */
    public fun setWindowTitle(title: String)

    /**
     * Sets the xterm icon title.
     *
     * Changes to the title advance the render frame generation.
     *
     * @param title The icon title to set.
     */
    public fun setIconTitle(title: String)

    /**
     * Enables or disables selective-erase protection on future printed cells (DECSCA).
     *
     * This affects DECSEL/DECSED only. Normal writes still overwrite protected cells.
     *
     * @param enabled `true` to enable selective-erase protection, `false` to disable.
     */
    public fun setSelectiveEraseProtection(enabled: Boolean)

    /**
     * Resets the active pen to the terminal default attributes (`SGR 0`, `CSI 0 m`).
     */
    public fun resetPen()
}
