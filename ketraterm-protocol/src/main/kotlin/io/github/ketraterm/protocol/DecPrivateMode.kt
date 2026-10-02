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
public object DecPrivateMode {
    /** Application cursor keys mode (DECCKM). */
    public const val APPLICATION_CURSOR_KEYS: Int = 1

    /** 132 column mode (DECCOLM). */
    public const val DECCOLM: Int = 3

    /** Reverse video mode (DECSCNM). */
    public const val REVERSE_VIDEO: Int = 5

    /** Origin mode (DECOM). */
    public const val ORIGIN: Int = 6

    /** Auto-wrap mode (DECAWM). */
    public const val AUTO_WRAP: Int = 7

    /** Cursor blink mode (ATT610). */
    public const val CURSOR_BLINK: Int = 12

    /** Cursor visible mode (DECTCEM). */
    public const val CURSOR_VISIBLE: Int = 25

    /** Application keypad mode (DECKPAM). */
    public const val APPLICATION_KEYPAD: Int = 66

    /** Backarrow key mode (DECBKM): Backspace sends BS when set and DEL when reset. */
    public const val BACKARROW_KEY: Int = 67

    /** Left/right margin mode (DECSLRM). */
    public const val LEFT_RIGHT_MARGIN: Int = 69

    /** Alternate screen buffer mode (DECSET/DECRST 47). */
    public const val ALT_SCREEN: Int = 47

    /** Alternate screen buffer mode (DECSET/DECRST 1047). */
    public const val ALT_SCREEN_BUFFER: Int = 1047

    /** Save/restore cursor mode (DECSET/DECRST 1048). */
    public const val SAVE_RESTORE_CURSOR: Int = 1048

    /** Alternate screen buffer mode with save/restore cursor (DECSET/DECRST 1049). */
    public const val ALT_SCREEN_SAVE_CURSOR: Int = 1049

    /** X10 mouse tracking mode. */
    public const val MOUSE_X10: Int = 9

    /** Normal mouse tracking mode. */
    public const val MOUSE_NORMAL: Int = 1000

    /** Button event mouse tracking mode. */
    public const val MOUSE_BUTTON_EVENT: Int = 1002

    /** Any event mouse tracking mode. */
    public const val MOUSE_ANY_EVENT: Int = 1003

    /** Focus reporting mode. */
    public const val FOCUS_REPORTING: Int = 1004

    /** UTF-8 mouse tracking encoding. */
    public const val MOUSE_UTF8: Int = 1005

    /** SGR decimal mouse tracking encoding. */
    public const val MOUSE_SGR: Int = 1006

    /** urxvt mouse tracking encoding. */
    public const val MOUSE_URXVT: Int = 1015

    /** SGR pixel mouse tracking encoding. */
    public const val MOUSE_SGR_PIXELS: Int = 1016

    /** Bracketed paste mode. */
    public const val BRACKETED_PASTE: Int = 2004

    /** Urgent bell mode, DECSET/DECRST `?1042`. */
    public const val BELL_IS_URGENT: Int = 1042

    /** Pop on bell mode, DECSET/DECRST `?1043`. */
    public const val POP_ON_BELL: Int = 1043

    /** Synchronized output mode, DECSET/DECRST `?2026`. */
    public const val SYNCHRONIZED_OUTPUT: Int = 2026
}
