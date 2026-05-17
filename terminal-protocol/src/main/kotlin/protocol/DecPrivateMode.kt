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
package com.gagik.terminal.protocol

/**
 * Common DEC private modes used in terminal emulation.
 *
 * Toggled via CSI ? Pn h (DECSET) and CSI ? Pn l (DECRST).
 */
object DecPrivateMode {
    const val APPLICATION_CURSOR_KEYS: Int = 1
    const val DECCOLM: Int = 3
    const val REVERSE_VIDEO: Int = 5
    const val ORIGIN: Int = 6
    const val AUTO_WRAP: Int = 7
    const val CURSOR_BLINK: Int = 12
    const val CURSOR_VISIBLE: Int = 25
    const val APPLICATION_KEYPAD: Int = 66
    const val LEFT_RIGHT_MARGIN: Int = 69
    const val ALT_SCREEN: Int = 47
    const val ALT_SCREEN_BUFFER: Int = 1047
    const val SAVE_RESTORE_CURSOR: Int = 1048
    const val ALT_SCREEN_SAVE_CURSOR: Int = 1049

    const val MOUSE_X10: Int = 9
    const val MOUSE_NORMAL: Int = 1000
    const val MOUSE_BUTTON_EVENT: Int = 1002
    const val MOUSE_ANY_EVENT: Int = 1003
    const val FOCUS_REPORTING: Int = 1004
    const val MOUSE_UTF8: Int = 1005
    const val MOUSE_SGR: Int = 1006
    const val MOUSE_URXVT: Int = 1015

    const val BRACKETED_PASTE: Int = 2004

    /** Synchronized output mode, DECSET/DECRST `?2026`. */
    const val SYNCHRONIZED_OUTPUT: Int = 2026
}
