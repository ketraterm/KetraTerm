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
package io.github.jvterm.core.buffer.impl

import io.github.jvterm.core.api.TerminalWriter
import io.github.jvterm.core.engine.CursorEngine
import io.github.jvterm.core.engine.MutationEngine
import io.github.jvterm.core.model.AttributeColor
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.core.state.TerminalState
import io.github.jvterm.core.util.UnicodeWidth

internal class TerminalWriterImpl(
    private val state: TerminalState,
    private val mutationEngine: MutationEngine,
    private val cursorEngine: CursorEngine,
) : TerminalWriter {
    override fun writeCodepoint(codepoint: Int) {
        val charWidth = UnicodeWidth.calculate(codepoint, state.modes.treatAmbiguousAsWide)
        mutationEngine.printCodepoint(codepoint, charWidth)
    }

    override fun writeText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charWidth = UnicodeWidth.calculate(cp, state.modes.treatAmbiguousAsWide)
            mutationEngine.printCodepoint(cp, charWidth)
            i += Character.charCount(cp)
        }
    }

    override fun writeCluster(
        codepoints: IntArray,
        length: Int,
    ) {
        require(length in 1..codepoints.size) { "length must be in 1..${codepoints.size}, was $length" }

        val charWidth = UnicodeWidth.calculateCluster(codepoints, length, state.modes.treatAmbiguousAsWide)

        if (length == 1) {
            mutationEngine.printCodepoint(codepoints[0], charWidth)
        } else {
            mutationEngine.printCluster(codepoints, length, charWidth)
        }
    }

    override fun appendToPreviousCluster(codepoint: Int) {
        require(codepoint in 0..0x10ffff) { "invalid codepoint: $codepoint" }
        mutationEngine.appendToPreviousCluster(codepoint)
    }

    override fun newLine() = mutationEngine.newLine()

    override fun reverseLineFeed() = mutationEngine.reverseLineFeed()

    override fun carriageReturn() = cursorEngine.carriageReturn()

    override fun setScrollRegion(
        top: Int,
        bottom: Int,
    ) {
        val oldCol = state.cursor.col
        val oldRow = state.cursor.row
        state.activeBuffer.setScrollRegion(
            top,
            bottom,
            state.modes.isOriginMode,
            state.dimensions.height,
            state.effectiveLeftMargin,
        )
        if (state.cursor.col != oldCol || state.cursor.row != oldRow) {
            state.markCursorChanged()
        }
    }

    override fun setLeftRightMargins(
        left: Int,
        right: Int,
    ) {
        if (!state.modes.isLeftRightMarginMode) return
        if (state.activeBuffer.setLeftRightMargins(left, right, state.dimensions.width)) {
            cursorEngine.homeCursor()
        }
    }

    override fun resetScrollRegion() {
        state.activeBuffer.resetScrollRegion(state.dimensions.height)
        cursorEngine.homeCursor()
    }

    override fun scrollUp() = mutationEngine.scrollUp()

    override fun scrollDown() = mutationEngine.scrollDown()

    override fun insertLines(count: Int) = mutationEngine.insertLines(count)

    override fun deleteLines(count: Int) = mutationEngine.deleteLines(count)

    override fun insertBlankCharacters(count: Int) = mutationEngine.insertBlankCharacters(count)

    override fun deleteCharacters(count: Int) = mutationEngine.deleteCharacters(count)

    override fun eraseCharacters(count: Int) = mutationEngine.eraseCharacters(count)

    override fun eraseLineToEnd() = mutationEngine.eraseLineToEnd()

    override fun eraseLineToCursor() = mutationEngine.eraseLineToCursor()

    override fun eraseCurrentLine() = mutationEngine.eraseCurrentLine()

    override fun selectiveEraseLineToEnd() = mutationEngine.selectiveEraseLineToEnd()

    override fun selectiveEraseLineToCursor() = mutationEngine.selectiveEraseLineToCursor()

    override fun selectiveEraseCurrentLine() = mutationEngine.selectiveEraseCurrentLine()

    override fun eraseScreenToEnd() = mutationEngine.eraseScreenToEnd()

    override fun eraseScreenToCursor() = mutationEngine.eraseScreenToCursor()

    override fun selectiveEraseScreenToEnd() = mutationEngine.selectiveEraseScreenToEnd()

    override fun selectiveEraseScreenToCursor() = mutationEngine.selectiveEraseScreenToCursor()

    override fun selectiveEraseEntireScreen() = mutationEngine.selectiveEraseEntireScreen()

    override fun eraseEntireScreen() = mutationEngine.clearViewport()

    override fun eraseScreenAndHistory() = mutationEngine.eraseScreenAndHistory()

    override fun clearScreen() {
        mutationEngine.clearViewport()
        cursorEngine.setCursorAbsolute(0, 0)
    }

    override fun decaln() {
        mutationEngine.decaln()
    }

    override fun clearAll() {
        state.pen.reset()
        mutationEngine.clearAllHistory()
        cursorEngine.setCursorAbsolute(0, 0)
        state.savedCursor.clear()
        state.tabStops.resetToDefault()
    }

    override fun setPenAttributes(
        fg: Int,
        bg: Int,
        bold: Boolean,
        faint: Boolean,
        italic: Boolean,
        underlineStyle: UnderlineStyle,
        strikethrough: Boolean,
        overline: Boolean,
        blink: Boolean,
        inverse: Boolean,
        conceal: Boolean,
        underlineColor: Int,
    ) {
        state.pen.setAttributes(
            fg = fg,
            bg = bg,
            bold = bold,
            faint = faint,
            italic = italic,
            underlineStyle = underlineStyle,
            strikethrough = strikethrough,
            overline = overline,
            blink = blink,
            inverse = inverse,
            conceal = conceal,
            underlineColor = underlineColor,
        )
    }

    override fun setPenColors(
        foreground: AttributeColor,
        background: AttributeColor,
        underlineColor: AttributeColor,
        bold: Boolean,
        faint: Boolean,
        italic: Boolean,
        underlineStyle: UnderlineStyle,
        strikethrough: Boolean,
        overline: Boolean,
        blink: Boolean,
        inverse: Boolean,
        conceal: Boolean,
    ) {
        state.pen.setColors(
            foreground = foreground,
            background = background,
            underlineColor = underlineColor,
            bold = bold,
            faint = faint,
            italic = italic,
            underlineStyle = underlineStyle,
            strikethrough = strikethrough,
            overline = overline,
            blink = blink,
            inverse = inverse,
            conceal = conceal,
        )
    }

    override fun setHyperlinkId(hyperlinkId: Int) {
        state.pen.setHyperlinkId(hyperlinkId)
    }

    override fun setWindowTitle(title: String) {
        state.windowTitle = title
    }

    override fun setIconTitle(title: String) {
        state.iconTitle = title
    }

    override fun setSelectiveEraseProtection(enabled: Boolean) {
        state.pen.setSelectiveEraseProtection(enabled)
    }

    override fun resetPen() {
        state.pen.resetSgr()
    }
}
