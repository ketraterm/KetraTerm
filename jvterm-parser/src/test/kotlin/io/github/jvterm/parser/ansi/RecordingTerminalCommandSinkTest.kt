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
package io.github.jvterm.parser.ansi

import io.github.jvterm.protocol.keyboard.FormatOtherKeysMode
import io.github.jvterm.protocol.keyboard.ModifyOtherKeysMode
import io.github.jvterm.protocol.keyboard.XtermKeyFormatResource
import io.github.jvterm.protocol.keyboard.XtermKeyModifierResource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RecordingTerminalCommandSink")
class RecordingTerminalCommandSinkTest {
    @Nested
    @DisplayName("terminal command recording")
    inner class TerminalCommandRecording {
        @Test
        fun `records terminal commands in call order`() {
            val sink = RecordingTerminalCommandSink()

            sink.writeCodepoint('A'.code)
            sink.writeCluster(intArrayOf('A'.code), length = 1)
            sink.appendToPreviousCluster(0x0301)
            sink.bell()
            sink.backspace()
            sink.tab()
            sink.lineFeed()
            sink.carriageReturn()
            sink.reverseIndex()
            sink.nextLine()
            sink.softReset()
            sink.resetTerminal()
            sink.saveCursor()
            sink.restoreCursor()
            sink.cursorUp(1)
            sink.cursorDown(2)
            sink.cursorForward(3)
            sink.cursorBackward(4)
            sink.cursorNextLine(5)
            sink.cursorPreviousLine(6)
            sink.cursorForwardTabs(7)
            sink.cursorBackwardTabs(8)
            sink.setCursorColumn(9)
            sink.setCursorRow(10)
            sink.setCursorAbsolute(11, 12)
            sink.setScrollRegion(13, 14)
            sink.setLeftRightMargins(15, 16)
            sink.eraseInDisplay(0, selective = false)
            sink.eraseInLine(1, selective = true)
            sink.insertLines(2)
            sink.deleteLines(3)
            sink.insertCharacters(4)
            sink.deleteCharacters(5)
            sink.eraseCharacters(6)
            sink.scrollUp(7)
            sink.scrollDown(8)
            sink.setTabStop()
            sink.clearTabStop()
            sink.clearAllTabStops()
            sink.setAnsiMode(4, enable = true)
            sink.setDecMode(25, enable = false)
            sink.setKeyModifierOption(
                XtermKeyModifierResource.MODIFY_OTHER_KEYS,
                ModifyOtherKeysMode.MODE_3,
            )
            sink.resetKeyModifierOption(XtermKeyModifierResource.MODIFY_OTHER_KEYS)
            sink.resetKeyModifierOptions()
            sink.setKeyFormatOption(
                XtermKeyFormatResource.FORMAT_OTHER_KEYS,
                FormatOtherKeysMode.CSI_U,
            )
            sink.resetKeyFormatOption(XtermKeyFormatResource.FORMAT_OTHER_KEYS)
            sink.resetKeyFormatOptions()
            sink.requestDeviceStatusReport(6, decPrivate = true)
            sink.requestDeviceAttributes(kind = 1, parameter = 0)
            sink.requestWindowReport(18)
            sink.pushTitleStack(2)
            sink.popTitleStack(2)
            sink.resetAttributes()
            sink.setBold(true)
            sink.setFaint(false)
            sink.setItalic(true)
            sink.setUnderlineStyle(2)
            sink.setBlink(true)
            sink.setInverse(false)
            sink.setConceal(true)
            sink.setStrikethrough(false)
            sink.setSelectiveEraseProtection(true)
            sink.setForegroundDefault()
            sink.setBackgroundDefault()
            sink.setForegroundIndexed(196)
            sink.setBackgroundIndexed(17)
            sink.setForegroundRgb(10, 20, 30)
            sink.setBackgroundRgb(40, 50, 60)
            sink.setWindowTitle("window")
            sink.setIconTitle("icon")
            sink.setIconAndWindowTitle("both")
            sink.startHyperlink(uri = "https://example.com", id = "abc")
            sink.startHyperlink(uri = "https://example.org", id = null)
            sink.endHyperlink()

            assertEquals(
                listOf(
                    "writeCodepoint:${'A'.code}",
                    "writeCluster:1:65",
                    "appendToPreviousCluster:769",
                    "bell",
                    "backspace",
                    "tab",
                    "lineFeed",
                    "carriageReturn",
                    "reverseIndex",
                    "nextLine",
                    "softReset",
                    "resetTerminal",
                    "saveCursor",
                    "restoreCursor",
                    "cursorUp:1",
                    "cursorDown:2",
                    "cursorForward:3",
                    "cursorBackward:4",
                    "cursorNextLine:5",
                    "cursorPreviousLine:6",
                    "cursorForwardTabs:7",
                    "cursorBackwardTabs:8",
                    "setCursorColumn:9",
                    "setCursorRow:10",
                    "setCursorAbsolute:11:12",
                    "setScrollRegion:13:14",
                    "setLeftRightMargins:15:16",
                    "eraseInDisplay:0:false",
                    "eraseInLine:1:true",
                    "insertLines:2",
                    "deleteLines:3",
                    "insertCharacters:4",
                    "deleteCharacters:5",
                    "eraseCharacters:6",
                    "scrollUp:7",
                    "scrollDown:8",
                    "setTabStop",
                    "clearTabStop",
                    "clearAllTabStops",
                    "setAnsiMode:4:true",
                    "setDecMode:25:false",
                    "setKeyModifierOption:${XtermKeyModifierResource.MODIFY_OTHER_KEYS}:${ModifyOtherKeysMode.MODE_3}",
                    "resetKeyModifierOption:${XtermKeyModifierResource.MODIFY_OTHER_KEYS}",
                    "resetKeyModifierOptions",
                    "setKeyFormatOption:${XtermKeyFormatResource.FORMAT_OTHER_KEYS}:${FormatOtherKeysMode.CSI_U}",
                    "resetKeyFormatOption:${XtermKeyFormatResource.FORMAT_OTHER_KEYS}",
                    "resetKeyFormatOptions",
                    "requestDeviceStatusReport:6:true",
                    "requestDeviceAttributes:1:0",
                    "requestWindowReport:18",
                    "pushTitleStack:2",
                    "popTitleStack:2",
                    "resetAttributes",
                    "setBold:true",
                    "setFaint:false",
                    "setItalic:true",
                    "setUnderlineStyle:2",
                    "setBlink:true",
                    "setInverse:false",
                    "setConceal:true",
                    "setStrikethrough:false",
                    "setSelectiveEraseProtection:true",
                    "setForegroundDefault",
                    "setBackgroundDefault",
                    "setForegroundIndexed:196",
                    "setBackgroundIndexed:17",
                    "setForegroundRgb:10:20:30",
                    "setBackgroundRgb:40:50:60",
                    "setWindowTitle:window",
                    "setIconTitle:icon",
                    "setIconAndWindowTitle:both",
                    "startHyperlink:https://example.com:abc",
                    "startHyperlink:https://example.org:null",
                    "endHyperlink",
                ),
                sink.events,
            )
        }

        @Test
        fun `starts with no recorded terminal commands`() {
            val sink = RecordingTerminalCommandSink()

            assertAll(
                { assertTrue(sink.events.isEmpty()) },
            )
        }
    }
}
