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
package com.gagik.parser.spi

import com.gagik.terminal.protocol.AnsiMode
import com.gagik.terminal.protocol.DecPrivateMode

/**
 * Parser-facing terminal command sink.
 *
 * This is the narrow semantic handoff boundary from :terminal-parser to :terminal-core.
 *
 * Rules:
 * - The parser emits terminal operations.
 * - The sink/core owns grid physics, bounds clamping, wrapping, margins, storage, and mode persistence.
 * - The parser must not know terminal width, height, cursor bounds, or rendering details.
 */
interface TerminalCommandSink {
    // -------------------------------------------------------------------------
    // Printable ingress
    // -------------------------------------------------------------------------

    fun writeCodepoint(codepoint: Int)

    fun writeCluster(
        codepoints: IntArray,
        length: Int,
    )

    /**
     * Appends one grapheme-continuation codepoint to the most recently written
     * printable cell without moving the cursor.
     *
     * The parser uses this when a host read boundary forced an already complete
     * printable prefix to be published for interactive latency, and a later
     * byte proves that the grapheme continues with a combining mark, variation
     * selector, ZWJ sequence member, or similar continuation. The sink/core owns
     * locating and mutating the previous cell.
     */
    fun appendToPreviousCluster(codepoint: Int)

    // -------------------------------------------------------------------------
    // C0 / ESC structural controls
    // -------------------------------------------------------------------------

    fun bell()

    fun backspace()

    fun tab()

    fun lineFeed()

    fun carriageReturn()

    fun reverseIndex()

    fun nextLine()

    /**
     * DECSTR soft terminal reset: CSI ! p.
     *
     * The parser identifies the sequence; the core owns the actual reset semantics.
     */
    fun softReset()

    /**
     * RIS full terminal reset: ESC c.
     *
     * The parser identifies the sequence; the core owns the actual reset semantics.
     */
    fun resetTerminal()

    /**
     * DEC Screen Alignment Test (DECALN): ESC # 8.
     *
     * The parser identifies the sequence; the core owns the actual alignment test semantics.
     */
    fun decaln()

    fun saveCursor()

    fun restoreCursor()

    fun setCursorStyle(style: Int)

    // -------------------------------------------------------------------------
    // Cursor navigation
    // -------------------------------------------------------------------------

    fun cursorUp(n: Int)

    fun cursorDown(n: Int)

    fun cursorForward(n: Int)

    fun cursorBackward(n: Int)

    fun cursorNextLine(n: Int)

    fun cursorPreviousLine(n: Int)

    fun cursorForwardTabs(n: Int)

    fun cursorBackwardTabs(n: Int)

    /**
     * Column is parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     */
    fun setCursorColumn(col: Int)

    /**
     * Row is parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     */
    fun setCursorRow(row: Int)

    /**
     * Row and column are parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     */
    fun setCursorAbsolute(
        row: Int,
        col: Int,
    )

    /**
     * DECSTBM scroll region.
     *
     * Top and bottom are parser-translated to zero-origin before handoff.
     * A bottom value of -1 means the sequence omitted the bottom margin, so the
     * core should use the terminal's current last row.
     */
    fun setScrollRegion(
        top: Int,
        bottom: Int,
    )

    /**
     * DECSLRM left/right margins.
     *
     * Left and right are parser-translated to zero-origin before handoff.
     * A right value of -1 means the sequence omitted the right margin, so the
     * core should use the terminal's current last column.
     */
    fun setLeftRightMargins(
        left: Int,
        right: Int,
    )

    // -------------------------------------------------------------------------
    // Erase / edit / scroll
    // -------------------------------------------------------------------------

    fun eraseInDisplay(
        mode: Int,
        selective: Boolean,
    )

    fun eraseInLine(
        mode: Int,
        selective: Boolean,
    )

    fun insertLines(n: Int)

    fun deleteLines(n: Int)

    fun insertCharacters(n: Int)

    fun deleteCharacters(n: Int)

    fun eraseCharacters(n: Int)

    fun scrollUp(n: Int)

    fun scrollDown(n: Int)

    // -------------------------------------------------------------------------
    // Tab stops
    // -------------------------------------------------------------------------

    fun setTabStop()

    fun clearTabStop()

    fun clearAllTabStops()

    // -------------------------------------------------------------------------
    // Modes
    // -------------------------------------------------------------------------

    /**
     * ANSI mode set/reset.
     *
     * Mode ids use the shared [AnsiMode] vocabulary.
     */
    fun setAnsiMode(
        mode: Int,
        enable: Boolean,
    )

    /**
     * DEC private mode set/reset.
     *
     * Mode ids use the shared [DecPrivateMode] vocabulary.
     */
    fun setDecMode(
        mode: Int,
        enable: Boolean,
    )

    /**
     * Xterm key modifier option set, `CSI > Pp ; Pv m`.
     *
     * The parser only identifies the resource id and value; the sink owns
     * deciding which resources are supported and how they affect input-facing
     * mode state.
     */
    fun setKeyModifierOption(
        resource: Int,
        value: Int,
    )

    /**
     * Resets one xterm key modifier option, `CSI > Pp m`.
     */
    fun resetKeyModifierOption(resource: Int)

    /**
     * Resets all supported xterm key modifier options, `CSI > m`.
     */
    fun resetKeyModifierOptions()

    /**
     * Xterm key format option set, `CSI > Pp ; Pv f`.
     */
    fun setKeyFormatOption(
        resource: Int,
        value: Int,
    )

    /**
     * Resets one xterm key format option, `CSI > Pp f`.
     */
    fun resetKeyFormatOption(resource: Int)

    /**
     * Resets all supported xterm key format options, `CSI > f`.
     */
    fun resetKeyFormatOptions()

    // -------------------------------------------------------------------------
    // Terminal-to-host responses
    // -------------------------------------------------------------------------

    /**
     * DSR/CPR request: CSI Ps n or CSI ? Ps n.
     */
    fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    )

    /**
     * DA request.
     *
     * Kind values:
     * - 0: primary DA, CSI Ps c
     * - 1: secondary DA, CSI > Ps c
     * - 2: tertiary DA, CSI = Ps c
     */
    fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    )

    /**
     * Safe xterm window report request.
     *
     * Supported modes are owned by the sink/core. Window manipulation requests
     * must not be represented here.
     */
    fun requestWindowReport(mode: Int)

    /**
     * Xterm title stack push/pop scopes:
     * - 0: icon and window title
     * - 1: icon title
     * - 2: window title
     */
    fun pushTitleStack(scope: Int)

    fun popTitleStack(scope: Int)

    // -------------------------------------------------------------------------
    // SGR / pen attributes
    // -------------------------------------------------------------------------

    fun resetAttributes()

    fun setBold(enabled: Boolean)

    fun setFaint(enabled: Boolean)

    fun setItalic(enabled: Boolean)

    fun setUnderlineStyle(style: Int)

    fun setBlink(enabled: Boolean)

    fun setInverse(enabled: Boolean)

    fun setConceal(enabled: Boolean)

    fun setStrikethrough(enabled: Boolean)

    fun setOverline(enabled: Boolean)

    fun setSelectiveEraseProtection(enabled: Boolean)

    fun setForegroundDefault()

    fun setBackgroundDefault()

    fun setUnderlineColorDefault()

    fun setForegroundIndexed(index: Int)

    fun setBackgroundIndexed(index: Int)

    fun setUnderlineColorIndexed(index: Int)

    fun setForegroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    )

    fun setBackgroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    )

    fun setUnderlineColorRgb(
        red: Int,
        green: Int,
        blue: Int,
    )

    // -------------------------------------------------------------------------
    // OSC
    // -------------------------------------------------------------------------

    fun setWindowTitle(title: String)

    fun setIconTitle(title: String)

    fun setIconAndWindowTitle(title: String)

    fun startHyperlink(
        uri: String,
        id: String?,
    )

    fun endHyperlink()
}
