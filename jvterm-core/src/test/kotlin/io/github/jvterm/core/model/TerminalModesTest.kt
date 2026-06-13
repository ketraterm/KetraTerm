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
package io.github.jvterm.core.model

import io.github.jvterm.protocol.MouseEncodingMode
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalModesTest {
    @Test
    fun `defaults reflect current shared terminal state contract`() {
        val modes = TerminalModes()

        assertAll(
            { assertFalse(modes.isInsertMode) },
            { assertTrue(modes.isAutoWrap) },
            { assertFalse(modes.isApplicationCursorKeys) },
            { assertFalse(modes.isApplicationKeypad) },
            { assertFalse(modes.isOriginMode) },
            { assertFalse(modes.isNewLineMode) },
            { assertFalse(modes.isLeftRightMarginMode) },
            { assertFalse(modes.isReverseVideo) },
            { assertTrue(modes.isCursorVisible) },
            { assertTrue(modes.isCursorBlinking) },
            { assertFalse(modes.isBracketedPasteEnabled) },
            { assertFalse(modes.isFocusReportingEnabled) },
            { assertFalse(modes.treatAmbiguousAsWide) },
            { assertEquals(MouseTrackingMode.OFF, modes.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.DEFAULT, modes.mouseEncodingMode) },
            { assertEquals(0, modes.modifyOtherKeysMode) },
            { assertEquals(0, modes.formatOtherKeysMode) },
            { assertEquals(0, modes.kittyKeyboardFlags) },
            { assertFalse(modes.isSynchronizedOutput) },
            { assertFalse(modes.isBellIsUrgent) },
            { assertFalse(modes.isPopOnBell) },
        )
    }

    @Test
    fun `reset restores all shared mode defaults`() {
        val modes = TerminalModes()
        modes.isInsertMode = true
        modes.isAutoWrap = false
        modes.isApplicationCursorKeys = true
        modes.isApplicationKeypad = true
        modes.isOriginMode = true
        modes.isNewLineMode = true
        modes.isLeftRightMarginMode = true
        modes.isReverseVideo = true
        modes.isCursorVisible = false
        modes.isCursorBlinking = false
        modes.isBracketedPasteEnabled = true
        modes.isFocusReportingEnabled = true
        modes.treatAmbiguousAsWide = true
        modes.mouseTrackingMode = MouseTrackingMode.ANY_EVENT
        modes.mouseEncodingMode = MouseEncodingMode.SGR
        modes.modifyOtherKeysMode = 2
        modes.formatOtherKeysMode = 1
        modes.kittyKeyboardFlags = KittyKeyboardProgressiveFlag.SUPPORTED_MASK
        modes.isSynchronizedOutput = true
        modes.isBellIsUrgent = true
        modes.isPopOnBell = true

        modes.reset()

        assertAll(
            { assertFalse(modes.isInsertMode) },
            { assertTrue(modes.isAutoWrap) },
            { assertFalse(modes.isApplicationCursorKeys) },
            { assertFalse(modes.isApplicationKeypad) },
            { assertFalse(modes.isOriginMode) },
            { assertFalse(modes.isNewLineMode) },
            { assertFalse(modes.isLeftRightMarginMode) },
            { assertFalse(modes.isReverseVideo) },
            { assertTrue(modes.isCursorVisible) },
            { assertTrue(modes.isCursorBlinking) },
            { assertFalse(modes.isBracketedPasteEnabled) },
            { assertFalse(modes.isFocusReportingEnabled) },
            { assertFalse(modes.treatAmbiguousAsWide) },
            { assertEquals(MouseTrackingMode.OFF, modes.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.DEFAULT, modes.mouseEncodingMode) },
            { assertEquals(0, modes.modifyOtherKeysMode) },
            { assertEquals(0, modes.formatOtherKeysMode) },
            { assertEquals(0, modes.kittyKeyboardFlags) },
            { assertFalse(modes.isSynchronizedOutput) },
            { assertFalse(modes.isBellIsUrgent) },
            { assertFalse(modes.isPopOnBell) },
        )
    }

    @Test
    fun `typed snapshot is decoded from current packed mode word`() {
        val modes = TerminalModes()

        modes.isApplicationCursorKeys = true
        modes.isApplicationKeypad = true
        modes.isFocusReportingEnabled = true
        modes.isBracketedPasteEnabled = true
        modes.mouseTrackingMode = MouseTrackingMode.BUTTON_EVENT
        modes.mouseEncodingMode = MouseEncodingMode.SGR
        modes.modifyOtherKeysMode = 2
        modes.formatOtherKeysMode = 1
        modes.kittyKeyboardFlags =
            KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
            KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES
        modes.isSynchronizedOutput = true
        modes.isBellIsUrgent = true
        modes.isPopOnBell = true

        val bits = modes.getModeBitsSnapshot()
        val snapshot = modes.getModeSnapshot()

        assertAll(
            { assertTrue(bits != 0L) },
            { assertTrue(snapshot.isApplicationCursorKeys) },
            { assertTrue(snapshot.isApplicationKeypad) },
            { assertTrue(snapshot.isFocusReportingEnabled) },
            { assertTrue(snapshot.isBracketedPasteEnabled) },
            { assertEquals(MouseTrackingMode.BUTTON_EVENT, snapshot.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.SGR, snapshot.mouseEncodingMode) },
            { assertEquals(2, snapshot.modifyOtherKeysMode) },
            { assertEquals(1, snapshot.formatOtherKeysMode) },
            {
                assertEquals(
                    KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
                        KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES,
                    snapshot.kittyKeyboardFlags,
                )
            },
            { assertTrue(snapshot.isSynchronizedOutput) },
            { assertTrue(snapshot.isBellIsUrgent) },
            { assertTrue(snapshot.isPopOnBell) },
        )
    }

    @Test
    fun `kitty keyboard flags mask unsupported bits`() {
        val modes = TerminalModes()

        modes.kittyKeyboardFlags = Int.MAX_VALUE

        assertEquals(KittyKeyboardProgressiveFlag.SUPPORTED_MASK, modes.kittyKeyboardFlags)
    }

    @Test
    fun `soft reset applies DECSTR defaults and preserves width policy and mouse encoding`() {
        val modes = TerminalModes()
        modes.isInsertMode = true
        modes.isAutoWrap = false
        modes.isApplicationCursorKeys = true
        modes.isApplicationKeypad = true
        modes.isOriginMode = true
        modes.isNewLineMode = true
        modes.isLeftRightMarginMode = true
        modes.isReverseVideo = true
        modes.isCursorVisible = false
        modes.isCursorBlinking = false
        modes.isBracketedPasteEnabled = true
        modes.isFocusReportingEnabled = true
        modes.treatAmbiguousAsWide = true
        modes.mouseTrackingMode = MouseTrackingMode.ANY_EVENT
        modes.mouseEncodingMode = MouseEncodingMode.URXVT
        modes.modifyOtherKeysMode = 2
        modes.formatOtherKeysMode = 1
        modes.kittyKeyboardFlags = KittyKeyboardProgressiveFlag.SUPPORTED_MASK
        modes.isSynchronizedOutput = true
        modes.isBellIsUrgent = true
        modes.isPopOnBell = true

        modes.softReset()

        assertAll(
            { assertFalse(modes.isInsertMode) },
            { assertTrue(modes.isAutoWrap) },
            { assertFalse(modes.isApplicationCursorKeys) },
            { assertFalse(modes.isApplicationKeypad) },
            { assertFalse(modes.isOriginMode) },
            { assertFalse(modes.isNewLineMode) },
            { assertFalse(modes.isLeftRightMarginMode) },
            { assertFalse(modes.isReverseVideo) },
            { assertTrue(modes.isCursorVisible) },
            { assertTrue(modes.isCursorBlinking) },
            { assertFalse(modes.isBracketedPasteEnabled) },
            { assertFalse(modes.isFocusReportingEnabled) },
            { assertTrue(modes.treatAmbiguousAsWide) },
            { assertEquals(MouseTrackingMode.OFF, modes.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.URXVT, modes.mouseEncodingMode) },
            { assertEquals(0, modes.modifyOtherKeysMode) },
            { assertEquals(0, modes.formatOtherKeysMode) },
            { assertEquals(0, modes.kittyKeyboardFlags) },
            { assertFalse(modes.isSynchronizedOutput) },
            { assertFalse(modes.isBellIsUrgent) },
            { assertFalse(modes.isPopOnBell) },
        )
    }
}
