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
package io.github.ketraterm.protocol

/**
 * Common DEC private modes used in terminal emulation.
 *
 * Toggled via CSI ? Pn h (DECSET) and CSI ? Pn l (DECRST).
 */
object DecPrivateMode {
    /** Application cursor keys mode (DECCKM). */
    const val APPLICATION_CURSOR_KEYS: Int = 1

    /** 132 column mode (DECCOLM). */
    const val DECCOLM: Int = 3

    /** Reverse video mode (DECSCNM). */
    const val REVERSE_VIDEO: Int = 5

    /** Origin mode (DECOM). */
    const val ORIGIN: Int = 6

    /** Auto-wrap mode (DECAWM). */
    const val AUTO_WRAP: Int = 7

    /** Cursor blink mode (ATT610). */
    const val CURSOR_BLINK: Int = 12

    /** Cursor visible mode (DECTCEM). */
    const val CURSOR_VISIBLE: Int = 25

    /** Application keypad mode (DECKPAM). */
    const val APPLICATION_KEYPAD: Int = 66

    /** Left/right margin mode (DECSLRM). */
    const val LEFT_RIGHT_MARGIN: Int = 69

    /** Alternate screen buffer mode (DECSET/DECRST 47). */
    const val ALT_SCREEN: Int = 47

    /** Alternate screen buffer mode (DECSET/DECRST 1047). */
    const val ALT_SCREEN_BUFFER: Int = 1047

    /** Save/restore cursor mode (DECSET/DECRST 1048). */
    const val SAVE_RESTORE_CURSOR: Int = 1048

    /** Alternate screen buffer mode with save/restore cursor (DECSET/DECRST 1049). */
    const val ALT_SCREEN_SAVE_CURSOR: Int = 1049

    /** X10 mouse tracking mode. */
    const val MOUSE_X10: Int = 9

    /** Normal mouse tracking mode. */
    const val MOUSE_NORMAL: Int = 1000

    /** Button event mouse tracking mode. */
    const val MOUSE_BUTTON_EVENT: Int = 1002

    /** Any event mouse tracking mode. */
    const val MOUSE_ANY_EVENT: Int = 1003

    /** Focus reporting mode. */
    const val FOCUS_REPORTING: Int = 1004

    /** UTF-8 mouse tracking encoding. */
    const val MOUSE_UTF8: Int = 1005

    /** SGR decimal mouse tracking encoding. */
    const val MOUSE_SGR: Int = 1006

    /** urxvt mouse tracking encoding. */
    const val MOUSE_URXVT: Int = 1015

    /** SGR pixel mouse tracking encoding. */
    const val MOUSE_SGR_PIXELS: Int = 1016

    /** Bracketed paste mode. */
    const val BRACKETED_PASTE: Int = 2004

    /** Urgent bell mode, DECSET/DECRST `?1042`. */
    const val BELL_IS_URGENT: Int = 1042

    /** Pop on bell mode, DECSET/DECRST `?1043`. */
    const val POP_ON_BELL: Int = 1043

    /** Synchronized output mode, DECSET/DECRST `?2026`. */
    const val SYNCHRONIZED_OUTPUT: Int = 2026
}
