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

import com.gagik.core.TerminalBuffers
import com.gagik.terminal.protocol.MouseEncodingMode
import com.gagik.terminal.protocol.MouseTrackingMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalModeReaderTest {
    @Test
    fun `exposes stable snapshot`() {
        val buffer = TerminalBuffers.create(width = 4, height = 3, maxHistory = 2)

        val snapshot = buffer.getModeSnapshot()

        assertAll(
            { assertFalse(snapshot.isInsertMode) },
            { assertTrue(snapshot.isAutoWrap) },
            { assertFalse(snapshot.isApplicationCursorKeys) },
            { assertFalse(snapshot.isApplicationKeypad) },
            { assertFalse(snapshot.isOriginMode) },
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
        )
    }

    @Test
    fun `reflects changes without leaking mutability`() {
        val buffer = TerminalBuffers.create(width = 4, height = 3, maxHistory = 2)
        val beforeBits = buffer.getModeBitsSnapshot()
        val before = buffer.getModeSnapshot()

        buffer.setNewLineMode(true)
        buffer.setApplicationKeypad(true)
        buffer.setMouseTrackingMode(MouseTrackingMode.ANY_EVENT)
        buffer.setMouseEncodingMode(MouseEncodingMode.URXVT)
        buffer.setBracketedPasteEnabled(true)
        buffer.setFocusReportingEnabled(true)
        buffer.setModifyOtherKeysMode(2)
        buffer.setFormatOtherKeysMode(1)
        buffer.setReverseVideo(true)
        buffer.setCursorVisible(false)
        buffer.setCursorBlinking(false)

        val afterBits = buffer.getModeBitsSnapshot()
        val inputBits = buffer.getInputModeBits()
        val after = buffer.getModeSnapshot()

        assertAll(
            { assertNotEquals(beforeBits, afterBits) },
            { assertEquals(afterBits, inputBits) },
            { assertFalse(before.isNewLineMode) },
            { assertFalse(before.isApplicationKeypad) },
            { assertEquals(MouseTrackingMode.OFF, before.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.DEFAULT, before.mouseEncodingMode) },
            { assertFalse(before.isBracketedPasteEnabled) },
            { assertFalse(before.isFocusReportingEnabled) },
            { assertEquals(0, before.modifyOtherKeysMode) },
            { assertEquals(0, before.formatOtherKeysMode) },
            { assertFalse(before.isReverseVideo) },
            { assertTrue(before.isCursorVisible) },
            { assertTrue(before.isCursorBlinking) },
            { assertTrue(after.isNewLineMode) },
            { assertTrue(after.isApplicationKeypad) },
            { assertEquals(MouseTrackingMode.ANY_EVENT, after.mouseTrackingMode) },
            { assertEquals(MouseEncodingMode.URXVT, after.mouseEncodingMode) },
            { assertTrue(after.isBracketedPasteEnabled) },
            { assertTrue(after.isFocusReportingEnabled) },
            { assertEquals(2, after.modifyOtherKeysMode) },
            { assertEquals(1, after.formatOtherKeysMode) },
            { assertTrue(after.isReverseVideo) },
            { assertFalse(after.isCursorVisible) },
            { assertFalse(after.isCursorBlinking) },
        )
    }
}
