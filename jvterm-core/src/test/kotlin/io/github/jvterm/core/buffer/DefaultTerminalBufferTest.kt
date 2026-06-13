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
package io.github.jvterm.core.buffer

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.core.api.TerminalBuffer
import io.github.jvterm.core.model.CellAttributes
import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.core.state.TerminalState
import io.github.jvterm.protocol.MouseEncodingMode
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("DefaultTerminalBuffer Integration Test Suite")
class DefaultTerminalBufferTest {
    private fun stateOf(api: TerminalBuffer): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    private fun newBuffer(
        width: Int = 4,
        height: Int = 3,
        maxHistory: Int = 5,
    ): TerminalBuffer = DefaultTerminalBuffer(width, height, maxHistory)

    private fun newApiBuffer(
        width: Int = 4,
        height: Int = 3,
        maxHistory: Int = 5,
    ): TerminalBuffer = TerminalBuffers.create(width, height, maxHistory)

    private fun blankScreen(height: Int): String = List(height) { "" }.joinToString("\n")

    @Test
    fun `constructs a blank buffer and factory returns a working api surface`() {
        val buffer = newBuffer(width = 3, height = 2, maxHistory = 4)
        val api = newApiBuffer(width = 3, height = 2, maxHistory = 4)

        assertAll(
            { assertEquals(3, buffer.width) },
            { assertEquals(2, buffer.height) },
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
            { assertEquals(0, buffer.historySize) },
            { assertEquals(blankScreen(2), buffer.getScreenAsString()) },
            { assertEquals(blankScreen(2), buffer.getAllAsString()) },
            { assertEquals(3, api.width) },
            { assertEquals(2, api.height) },
            { assertEquals(blankScreen(2), api.getScreenAsString()) },
        )
    }

    @Test
    fun `constructor and factory reject invalid dimensions`() {
        assertThrows<IllegalArgumentException> { DefaultTerminalBuffer(0, 1) }
        assertThrows<IllegalArgumentException> { TerminalBuffers.create(1, 0) }
    }

    @Test
    fun `saveCursor and restoreCursor round-trip cursor and pen state through the facade`() {
        val buffer = newApiBuffer(width = 4, height = 3)
        buffer.positionCursor(2, 1)
        buffer.setPenAttributes(
            3,
            7,
            bold = true,
            italic = true,
            underlineStyle = UnderlineStyle.DASHED,
            underlineColor = 9,
        )

        buffer.saveCursor()
        buffer.positionCursor(0, 0)
        buffer.setPenAttributes(1, 2, bold = false, italic = false, underlineStyle = UnderlineStyle.SINGLE)
        buffer.restoreCursor()

        assertAll(
            { assertEquals(2, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )

        buffer.writeCodepoint('X'.code)

        assertAll(
            { assertEquals(3, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
            { assertEquals('X'.code, buffer.getCodepointAt(2, 1)) },
            {
                assertEquals(
                    CellAttributes(
                        foreground = CellColor.indexed(2),
                        background = CellColor.indexed(6),
                        bold = true,
                        italic = true,
                        underlineStyle = UnderlineStyle.DASHED,
                        underlineColor = CellColor.indexed(8),
                    ),
                    buffer.getAttrAt(2, 1),
                )
            },
        )
    }

    @Test
    fun `resize and reset remain coordinated through the TerminalBuffer facade`() {
        val buffer = newApiBuffer(width = 4, height = 2, maxHistory = 3)
        buffer.writeText("ABCD")
        buffer.resize(newWidth = 2, newHeight = 3)

        assertAll(
            { assertEquals(2, buffer.width) },
            { assertEquals(3, buffer.height) },
            { assertEquals("AB\nCD\n", buffer.getScreenAsString()) },
            { assertEquals(true, buffer.cursorCol in 0 until buffer.width) },
            { assertEquals(true, buffer.cursorRow in 0 until buffer.height) },
        )

        buffer.reset()

        assertAll(
            { assertEquals(2, buffer.width) },
            { assertEquals(3, buffer.height) },
            { assertEquals(blankScreen(3), buffer.getScreenAsString()) },
            { assertEquals(blankScreen(3), buffer.getAllAsString()) },
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `softReset preserves content and cursor while resetting soft terminal state`() {
        val buffer = newApiBuffer(width = 8, height = 4, maxHistory = 2)
        val state = stateOf(buffer)

        buffer.writeText("AB")
        buffer.setScrollRegion(2, 4)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setInsertMode(true)
        buffer.setApplicationCursorKeys(true)
        buffer.setApplicationKeypad(true)
        buffer.setOriginMode(true)
        buffer.setAutoWrap(false)
        buffer.setCursorVisible(false)
        buffer.setCursorBlinking(false)
        buffer.setBracketedPasteEnabled(true)
        buffer.setFocusReportingEnabled(true)
        buffer.setMouseTrackingMode(MouseTrackingMode.BUTTON_EVENT)
        buffer.setMouseEncodingMode(MouseEncodingMode.SGR)
        buffer.setModifyOtherKeysMode(2)
        buffer.setKittyKeyboardFlags(KittyKeyboardProgressiveFlag.SUPPORTED_MASK)
        buffer.setSelectiveEraseProtection(true)
        buffer.setPenColors(
            foreground = CellColor.indexed(196),
            background = CellColor.indexed(17),
            bold = true,
            underlineStyle = UnderlineStyle.CURLY,
        )
        buffer.positionCursor(5, 2)
        buffer.saveCursor()
        buffer.positionCursor(2, 1)

        buffer.softReset()

        val snapshot = buffer.getModeSnapshot()

        assertAll(
            { assertEquals("AB", buffer.getLineAsString(0)) },
            { assertEquals(4, buffer.cursorCol) },
            { assertEquals(2, buffer.cursorRow) },
            { assertFalse(state.cursor.pendingWrap) },
            { assertFalse(snapshot.isInsertMode) },
            { assertFalse(snapshot.isApplicationCursorKeys) },
            { assertFalse(snapshot.isApplicationKeypad) },
            { assertFalse(snapshot.isOriginMode) },
            { assertTrue(snapshot.isAutoWrap) },
            { assertFalse(snapshot.isLeftRightMarginMode) },
            { assertTrue(snapshot.isCursorVisible) },
            { assertTrue(snapshot.isCursorBlinking) },
            { assertFalse(snapshot.isBracketedPasteEnabled) },
            { assertFalse(snapshot.isFocusReportingEnabled) },
            { assertEquals(MouseTrackingMode.OFF, snapshot.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.SGR, snapshot.mouseEncodingMode) },
            { assertEquals(0, snapshot.modifyOtherKeysMode) },
            { assertEquals(0, snapshot.formatOtherKeysMode) },
            { assertEquals(0, snapshot.kittyKeyboardFlags) },
            { assertEquals(0, state.primaryBuffer.scrollTop) },
            { assertEquals(3, state.primaryBuffer.scrollBottom) },
            { assertEquals(0, state.primaryBuffer.leftMargin) },
            { assertEquals(7, state.primaryBuffer.rightMargin) },
        )

        buffer.writeCodepoint('C'.code)

        val attr = buffer.getAttrAt(4, 2)
        assertAll(
            { assertEquals('C'.code, buffer.getCodepointAt(4, 2)) },
            { assertEquals(CellColor.DEFAULT, attr?.foreground) },
            { assertEquals(CellColor.DEFAULT, attr?.background) },
            { assertFalse(attr?.bold == true) },
            { assertFalse(attr?.selectiveEraseProtected == true) },
        )

        buffer.positionCursor(7, 3)
        buffer.restoreCursor()

        assertAll(
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
            { assertFalse(buffer.getModeSnapshot().isOriginMode) },
        )
    }

    @Test
    fun `reset exits alt buffer and restores current core mode defaults`() {
        val buffer = newApiBuffer(width = 5, height = 3, maxHistory = 2)
        val state = stateOf(buffer)
        buffer.setInsertMode(true)
        buffer.setAutoWrap(false)
        buffer.setOriginMode(true)
        buffer.setApplicationCursorKeys(true)
        buffer.setApplicationKeypad(true)
        buffer.setNewLineMode(true)
        buffer.setLeftRightMarginMode(true)
        buffer.setReverseVideo(true)
        buffer.setCursorVisible(false)
        buffer.setCursorBlinking(false)
        buffer.setBracketedPasteEnabled(true)
        buffer.setFocusReportingEnabled(true)
        buffer.setMouseTrackingMode(MouseTrackingMode.BUTTON_EVENT)
        buffer.setMouseEncodingMode(MouseEncodingMode.SGR)
        buffer.setModifyOtherKeysMode(2)
        buffer.setKittyKeyboardFlags(KittyKeyboardProgressiveFlag.SUPPORTED_MASK)
        buffer.setTreatAmbiguousAsWide(true)
        buffer.enterAltBuffer()

        buffer.reset()

        val snapshot = buffer.getModeSnapshot()

        assertAll(
            { assertFalse(state.isAltScreenActive) },
            { assertFalse(snapshot.isInsertMode) },
            { assertTrue(snapshot.isAutoWrap) },
            { assertFalse(snapshot.isOriginMode) },
            { assertFalse(snapshot.isApplicationCursorKeys) },
            { assertFalse(snapshot.isApplicationKeypad) },
            { assertFalse(snapshot.isNewLineMode) },
            { assertFalse(snapshot.isLeftRightMarginMode) },
            { assertFalse(snapshot.isReverseVideo) },
            { assertTrue(snapshot.isCursorVisible) },
            { assertTrue(snapshot.isCursorBlinking) },
            { assertFalse(snapshot.isBracketedPasteEnabled) },
            { assertFalse(snapshot.isFocusReportingEnabled) },
            { assertFalse(snapshot.treatAmbiguousAsWide) },
            { assertEquals(MouseTrackingMode.OFF, snapshot.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.DEFAULT, snapshot.mouseEncodingMode) },
            { assertEquals(0, snapshot.modifyOtherKeysMode) },
            { assertEquals(0, snapshot.formatOtherKeysMode) },
            { assertEquals(0, snapshot.kittyKeyboardFlags) },
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `reset restores default tab stops after custom tab configuration`() {
        val buffer = newApiBuffer(width = 20, height = 2)
        buffer.clearAllTabStops()
        buffer.positionCursor(5, 0)
        buffer.setTabStop()
        buffer.reset()
        buffer.horizontalTab()

        assertEquals(8, buffer.cursorCol)
    }

    @Test
    fun `resize preserves surviving custom tab stops and discards truncated ones`() {
        val buffer = newApiBuffer(width = 20, height = 2)
        val state = stateOf(buffer)

        buffer.clearAllTabStops()
        buffer.positionCursor(5, 0)
        buffer.setTabStop()
        buffer.positionCursor(15, 0)
        buffer.setTabStop()

        buffer.resize(newWidth = 10, newHeight = 2)
        assertAll(
            { assertEquals(5, state.tabStops.getNextStop(0)) },
            { assertEquals(9, state.tabStops.getNextStop(5)) },
        )

        buffer.resize(newWidth = 20, newHeight = 2)
        assertAll(
            { assertEquals(5, state.tabStops.getNextStop(0)) },
            { assertEquals(16, state.tabStops.getNextStop(10)) },
        )
    }

    @Test
    fun `reset and softReset clear kitty keyboard stacks on both buffers`() {
        val buffer = newApiBuffer(width = 8, height = 4, maxHistory = 2)
        val state = stateOf(buffer)

        // 1. Setup primary buffer stack
        buffer.setKittyKeyboardFlags(2)
        buffer.pushKittyKeyboardFlags(4)
        buffer.pushKittyKeyboardFlags(8)

        // 2. Setup alternate buffer stack
        buffer.enterAltBuffer()
        buffer.setKittyKeyboardFlags(1)
        buffer.pushKittyKeyboardFlags(3)
        buffer.pushKittyKeyboardFlags(9)

        // Verify state is active in alt buffer
        assertEquals(9, state.modes.kittyKeyboardFlags)
        assertEquals(9, state.altBuffer.kittyKeyboardFlags)

        // Reset
        buffer.reset()

        // Verify primary stack is cleared
        assertFalse(state.primaryBuffer.hasSavedInitialFlags)
        assertEquals(0, state.primaryBuffer.kittyKeyboardFlags)

        // Verify alt stack is cleared
        assertFalse(state.altBuffer.hasSavedInitialFlags)
        assertEquals(0, state.altBuffer.kittyKeyboardFlags)

        // Verify modes
        assertEquals(0, state.modes.kittyKeyboardFlags)

        // Now setup again to test softReset
        buffer.setKittyKeyboardFlags(2)
        buffer.pushKittyKeyboardFlags(4)
        buffer.pushKittyKeyboardFlags(8)

        buffer.enterAltBuffer()
        buffer.setKittyKeyboardFlags(1)
        buffer.pushKittyKeyboardFlags(3)
        buffer.pushKittyKeyboardFlags(9)

        buffer.softReset()

        // Verify primary stack is cleared
        assertFalse(state.primaryBuffer.hasSavedInitialFlags)
        assertEquals(0, state.primaryBuffer.kittyKeyboardFlags)

        // Verify alt stack is cleared
        assertFalse(state.altBuffer.hasSavedInitialFlags)
        assertEquals(0, state.altBuffer.kittyKeyboardFlags)

        // Verify modes
        assertEquals(0, state.modes.kittyKeyboardFlags)
    }

    @Test
    fun `alternate buffer maintains completely isolated kitty keyboard stack`() {
        val buffer = newApiBuffer(width = 8, height = 4, maxHistory = 2)
        val state = stateOf(buffer)

        // 1. Push on primary screen
        buffer.setKittyKeyboardFlags(3)
        buffer.pushKittyKeyboardFlags(7)

        assertEquals(7, state.modes.kittyKeyboardFlags)
        assertEquals(7, state.primaryBuffer.kittyKeyboardFlags)

        // 2. Enter alt screen
        buffer.enterAltBuffer()

        // Alt screen starts with fresh/cleared stack
        assertEquals(0, state.modes.kittyKeyboardFlags)
        assertEquals(0, state.altBuffer.kittyKeyboardFlags)

        // Push on alt screen
        buffer.pushKittyKeyboardFlags(15)
        assertEquals(15, state.modes.kittyKeyboardFlags)
        assertEquals(15, state.altBuffer.kittyKeyboardFlags)

        // 3. Exit alt screen
        buffer.exitAltBuffer()

        // Primary stack is preserved and restored
        assertEquals(7, state.modes.kittyKeyboardFlags)
        assertEquals(7, state.primaryBuffer.kittyKeyboardFlags)

        // Pop on primary
        buffer.popKittyKeyboardFlags(1)
        assertEquals(3, state.modes.kittyKeyboardFlags)
    }
}
