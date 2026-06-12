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
package com.gagik.core.buffer.impl

import com.gagik.core.buffer.TerminalBuffer
import com.gagik.core.engine.CursorEngine
import com.gagik.core.engine.MutationEngine
import com.gagik.core.state.TerminalState
import com.gagik.terminal.protocol.MouseEncodingMode
import com.gagik.terminal.protocol.MouseTrackingMode
import com.gagik.terminal.protocol.keyboard.KittyKeyboardProgressiveFlag
import com.gagik.terminal.render.api.TerminalRenderCursorShape
import com.gagik.terminal.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalModeControllerImplTest {
    private fun withPendingWrap(
        state: TerminalState,
        assertion: () -> Unit,
    ) {
        state.cursor.pendingWrap = true
        assertion()
        assertFalse(state.cursor.pendingWrap)
    }

    @Test
    fun `updates mode flags in the shared state`() {
        val state = TerminalState(4, 3, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        modeController.setInsertMode(true)
        modeController.setApplicationCursorKeys(true)
        modeController.setTreatAmbiguousAsWide(true)

        assertAll(
            { assertEquals(true, state.modes.isInsertMode) },
            { assertEquals(true, state.modes.isApplicationCursorKeys) },
            { assertEquals(true, state.modes.treatAmbiguousAsWide) },
        )
    }

    @Test
    fun `cursor shape updates correctly and resets on full and soft reset`() {
        val state = TerminalState(4, 3, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        assertEquals(TerminalRenderCursorShape.BLOCK, state.cursorShape)

        modeController.setCursorShape(TerminalRenderCursorShape.BAR)
        assertEquals(TerminalRenderCursorShape.BAR, state.cursorShape)

        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 3)
        buffer.setCursorShape(TerminalRenderCursorShape.BAR)
        buffer.reset()

        var shapeAfterReset: TerminalRenderCursorShape? = null
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            shapeAfterReset = frame.cursor.shape
        }
        assertEquals(TerminalRenderCursorShape.BLOCK, shapeAfterReset)

        buffer.setCursorShape(TerminalRenderCursorShape.UNDERLINE)
        buffer.softReset()
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            shapeAfterReset = frame.cursor.shape
        }
        assertEquals(TerminalRenderCursorShape.BLOCK, shapeAfterReset)
    }

    @Test
    fun `default cursor shape can be set and is restored on full and soft reset`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 3)
        buffer.setDefaultCursorShape(TerminalRenderCursorShape.BAR)
        buffer.setCursorShape(TerminalRenderCursorShape.BAR)

        var shape: TerminalRenderCursorShape? = null
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            shape = frame.cursor.shape
        }
        assertEquals(TerminalRenderCursorShape.BAR, shape)

        buffer.reset()
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            shape = frame.cursor.shape
        }
        assertEquals(TerminalRenderCursorShape.BAR, shape)

        buffer.setCursorShape(TerminalRenderCursorShape.UNDERLINE)
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            shape = frame.cursor.shape
        }
        assertEquals(TerminalRenderCursorShape.UNDERLINE, shape)

        buffer.softReset()
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            shape = frame.cursor.shape
        }
        assertEquals(TerminalRenderCursorShape.BAR, shape)
    }

    @Test
    fun `new fields round trip into shared state`() {
        val state = TerminalState(4, 3, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        modeController.setNewLineMode(true)
        modeController.setApplicationKeypad(true)
        modeController.setMouseTrackingMode(MouseTrackingMode.BUTTON_EVENT)
        modeController.setMouseEncodingMode(MouseEncodingMode.SGR)
        modeController.setBracketedPasteEnabled(true)
        modeController.setFocusReportingEnabled(true)
        modeController.setModifyOtherKeysMode(2)
        modeController.setFormatOtherKeysMode(1)
        modeController.setKittyKeyboardFlags(KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES)
        modeController.setReverseVideo(true)
        modeController.setCursorVisible(false)
        modeController.setCursorBlinking(true)

        assertAll(
            { assertTrue(state.modes.isNewLineMode) },
            { assertTrue(state.modes.isApplicationKeypad) },
            { assertEquals(MouseTrackingMode.BUTTON_EVENT, state.modes.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.SGR, state.modes.mouseEncodingMode) },
            { assertTrue(state.modes.isBracketedPasteEnabled) },
            { assertTrue(state.modes.isFocusReportingEnabled) },
            { assertEquals(2, state.modes.modifyOtherKeysMode) },
            { assertEquals(1, state.modes.formatOtherKeysMode) },
            { assertEquals(KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES, state.modes.kittyKeyboardFlags) },
            { assertTrue(state.modes.isReverseVideo) },
            { assertFalse(state.modes.isCursorVisible) },
            { assertTrue(state.modes.isCursorBlinking) },
        )
    }

    @Test
    fun `public mode setters cancel pending wrap`() {
        val state = TerminalState(8, 4, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        withPendingWrap(state) { modeController.setInsertMode(true) }
        withPendingWrap(state) { modeController.setAutoWrap(true) }
        withPendingWrap(state) { modeController.setAutoWrap(false) }
        withPendingWrap(state) { modeController.setOriginMode(true) }
        withPendingWrap(state) { modeController.setApplicationCursorKeys(true) }
        withPendingWrap(state) { modeController.setApplicationKeypad(true) }
        withPendingWrap(state) { modeController.setLeftRightMarginMode(true) }
        withPendingWrap(state) { modeController.setLeftRightMarginMode(true) }
        withPendingWrap(state) { modeController.setLeftRightMarginMode(false) }
        withPendingWrap(state) { modeController.setNewLineMode(true) }
        withPendingWrap(state) { modeController.setMouseTrackingMode(MouseTrackingMode.NORMAL) }
        withPendingWrap(state) { modeController.setMouseEncodingMode(MouseEncodingMode.SGR) }
        withPendingWrap(state) { modeController.setBracketedPasteEnabled(true) }
        withPendingWrap(state) { modeController.setFocusReportingEnabled(true) }
        withPendingWrap(state) { modeController.setModifyOtherKeysMode(2) }
        withPendingWrap(state) { modeController.setFormatOtherKeysMode(1) }
        withPendingWrap(state) {
            modeController.setKittyKeyboardFlags(KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES)
        }
        withPendingWrap(state) { modeController.setReverseVideo(true) }
        withPendingWrap(state) { modeController.setCursorVisible(false) }
        withPendingWrap(state) { modeController.setCursorBlinking(true) }
        withPendingWrap(state) { modeController.setTreatAmbiguousAsWide(true) }
    }

    @Test
    fun `next printable after mode setter does not consume stale pending wrap`() {
        val state = TerminalState(4, 2, 1)
        val cursorEngine = CursorEngine(state)
        val modeController = TerminalModeControllerImpl(state, cursorEngine)
        val writer = TerminalWriterImpl(state, MutationEngine(state), cursorEngine)

        writer.writeText("ABCD")
        assertTrue(state.cursor.pendingWrap)

        modeController.setNewLineMode(true)
        writer.writeCodepoint('X'.code)

        assertAll(
            { assertEquals('A'.code, state.ring[state.resolveRingIndex(0)].getCodepoint(0)) },
            { assertEquals('B'.code, state.ring[state.resolveRingIndex(0)].getCodepoint(1)) },
            { assertEquals('C'.code, state.ring[state.resolveRingIndex(0)].getCodepoint(2)) },
            { assertEquals('X'.code, state.ring[state.resolveRingIndex(0)].getCodepoint(3)) },
            { assertEquals(0, state.ring[state.resolveRingIndex(1)].getCodepoint(0)) },
            { assertEquals(3, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
        )
    }

    @Test
    fun `auto wrap off clears pending wrap and origin mode homes to the scroll region`() {
        val state = TerminalState(5, 4, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        state.cursor.pendingWrap = true
        state.activeBuffer.setScrollRegion(
            top = 2,
            bottom = 3,
            isOriginMode = false,
            viewportHeight = state.dimensions.height,
        )
        modeController.setAutoWrap(false)
        modeController.setOriginMode(true)

        assertAll(
            { assertEquals(false, state.modes.isAutoWrap) },
            { assertEquals(false, state.cursor.pendingWrap) },
            { assertEquals(1, state.cursor.row) },
            { assertEquals(0, state.cursor.col) },
            { assertEquals(true, state.modes.isOriginMode) },
        )
    }

    @Test
    fun `enterAltBufferWithoutCursorSave can reuse existing alt content and cursor`() {
        val state = TerminalState(6, 4, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        state.primaryBuffer.cursor.col = 3
        state.primaryBuffer.cursor.row = 2
        state.primaryBuffer.cursor.pendingWrap = true
        state.altBuffer.cursor.col = 1
        state.altBuffer.cursor.row = 1
        state.altBuffer.ring[1].setCell(0, 'A'.code, state.pen.currentAttr)

        modeController.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)

        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertEquals(1, state.cursor.col) },
            { assertEquals(1, state.cursor.row) },
            { assertEquals("A", state.altBuffer.ring[1].toTextTrimmed()) },
        )

        modeController.exitAltBufferWithoutCursorRestore()

        assertAll(
            { assertFalse(state.isAltScreenActive) },
            { assertEquals(3, state.cursor.col) },
            { assertEquals(2, state.cursor.row) },
            { assertTrue(state.cursor.pendingWrap) },
        )
    }

    @Test
    fun `enterAltBufferWithoutCursorSave can clear alt state before switching`() {
        val state = TerminalState(6, 4, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        state.altBuffer.cursor.col = 4
        state.altBuffer.cursor.row = 2
        state.altBuffer.cursor.pendingWrap = true
        state.altBuffer.savedCursor.isSaved = true
        state.altBuffer.ring[0].setCell(0, 'A'.code, state.pen.currentAttr)

        modeController.enterAltBufferWithoutCursorSave(clearBeforeEnter = true)

        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertEquals(0, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            { assertFalse(state.cursor.pendingWrap) },
            { assertFalse(state.savedCursor.isSaved) },
            { assertEquals("", state.altBuffer.ring[0].toTextTrimmed()) },
        )
    }

    @Test
    fun `enterAltBuffer switches to alt screen and exit restores cursor and pen but NOT global modes`() {
        val state = TerminalState(6, 4, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        // 1. Setup Primary State
        state.cursor.col = 5
        state.cursor.row = 2
        state.cursor.pendingWrap = true
        state.pen.setAttributes(3, 7, bold = true, italic = true)
        val originalAttr = state.pen.currentAttr // Save to verify DECRC later

        state.modes.isInsertMode = true
        state.modes.isAutoWrap = false
        state.primaryBuffer.ring[state.resolveRingIndex(0)].setCell(
            0,
            'P'.code,
            state.pen.currentAttr,
            state.pen.currentExtendedAttr,
        )

        // 2. Enter Alt Screen
        modeController.enterAltBuffer()

        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertEquals(0, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            { assertFalse(state.cursor.pendingWrap) },
            { assertEquals("", state.altBuffer.ring[state.resolveRingIndex(0)].toTextTrimmed()) },
            { assertEquals("P", state.primaryBuffer.ring[0].toTextTrimmed()) },
        )

        // 3. Mutate state while inside Alt Screen
        state.cursor.col = 1
        state.cursor.row = 1
        state.pen.reset()

        // Mutate GLOBAL modes
        state.modes.isInsertMode = false
        state.modes.isAutoWrap = true

        // 4. Exit Alt Screen
        modeController.exitAltBuffer()

        // 5. Verify the strict VT500 DECRC restoration
        assertAll(
            { assertFalse(state.isAltScreenActive) },
            // Cursor geometry MUST be restored
            { assertEquals(5, state.cursor.col) },
            { assertEquals(2, state.cursor.row) },
            { assertTrue(state.cursor.pendingWrap) },
            // Pen MUST be restored
            { assertEquals(originalAttr, state.pen.currentAttr) },
            // Global hardware modes MUST NOT be restored (they keep the mutations from step 3)
            { assertFalse(state.modes.isInsertMode, "IRM is global and should not revert") },
            { assertTrue(state.modes.isAutoWrap, "DECAWM is global and should not revert") },
            { assertEquals('P'.code, state.primaryBuffer.ring[state.resolveRingIndex(0)].getCodepoint(0)) },
        )
    }

    @Test
    fun `enterAltBuffer and exitAltBuffer are no-op when already in target buffer`() {
        val state = TerminalState(5, 3, 1)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        modeController.exitAltBuffer()
        assertFalse(state.isAltScreenActive)

        modeController.enterAltBuffer()
        state.altBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'A'.code, state.pen.currentAttr)
        modeController.enterAltBuffer()

        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertEquals('A'.code, state.altBuffer.ring[state.resolveRingIndex(0)].getCodepoint(0)) },
        )
    }

    @Test
    fun `kitty keyboard push pop via controller updates modes and buffer`() {
        val state = TerminalState(6, 4, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        // Initial state
        assertEquals(0, state.modes.kittyKeyboardFlags)
        assertEquals(0, state.activeBuffer.kittyKeyboardFlags)

        // Set flags directly
        modeController.setKittyKeyboardFlags(3)
        assertEquals(3, state.modes.kittyKeyboardFlags)
        assertEquals(3, state.activeBuffer.kittyKeyboardFlags)

        // Push flags
        modeController.pushKittyKeyboardFlags(12)
        assertEquals(12, state.modes.kittyKeyboardFlags)
        assertEquals(12, state.activeBuffer.kittyKeyboardFlags)

        // Switch to alternate buffer
        modeController.enterAltBuffer()
        assertEquals(0, state.modes.kittyKeyboardFlags)
        assertEquals(0, state.activeBuffer.kittyKeyboardFlags)

        // Push inside alt screen
        modeController.pushKittyKeyboardFlags(5)
        assertEquals(5, state.modes.kittyKeyboardFlags)
        assertEquals(5, state.activeBuffer.kittyKeyboardFlags)

        // Exit alt buffer
        modeController.exitAltBuffer()
        assertEquals(12, state.modes.kittyKeyboardFlags)
        assertEquals(12, state.activeBuffer.kittyKeyboardFlags)

        // Pop on primary
        modeController.popKittyKeyboardFlags(1)
        assertEquals(3, state.modes.kittyKeyboardFlags)
        assertEquals(3, state.activeBuffer.kittyKeyboardFlags)

        // Pop to baseline
        modeController.popKittyKeyboardFlags(1)
        assertEquals(3, state.modes.kittyKeyboardFlags)
        assertEquals(3, state.activeBuffer.kittyKeyboardFlags)
    }

    @Test
    fun `sets theme palette and active colors correctly`() {
        val state = TerminalState(4, 3, 2)
        val modeController = TerminalModeControllerImpl(state, CursorEngine(state))

        val themePalette =
            com.gagik.terminal.render.api.TerminalColorPalette(
                defaultForeground = 0xFF111111.toInt(),
                defaultBackground = 0xFF222222.toInt(),
            )
        modeController.setThemePalette(themePalette)

        assertEquals(themePalette, state.themePalette)
        assertEquals(themePalette, state.palette)

        modeController.setPaletteColor(5, 0xFF555555.toInt())
        assertEquals(0xFF555555.toInt(), state.palette.indexedColor(5))

        modeController.setDynamicColor(10, 0xFFFFFFFF.toInt())
        assertEquals(0xFFFFFFFF.toInt(), state.palette.defaultForeground)

        modeController.setDynamicColor(11, 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), state.palette.defaultBackground)
    }
}
