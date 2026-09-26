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
package io.github.ketraterm.input.impl.keyboard

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.protocol.keyboard.XtermKeyResource

/**
 * Xterm extended-key identities and classes, independent of Kitty's PUA vocabulary.
 * Codes follow xterm keysym2ucs: control aliases retain their scalar; otherwise
 * standard X11 keysyms in FD00..FFFF map to E000..E2FF. No X11 runtime is required.
 */
internal object XtermKeyMappingTable {
    val RESOURCES = IntArray(TerminalKey.entries.size) { XtermKeyResource.SPECIAL_KEYS }
    val CODES = IntArray(TerminalKey.entries.size) { -1 }

    init {
        fun map(
            key: TerminalKey,
            resource: Int,
            code: Int,
        ) {
            RESOURCES[key.ordinal] = resource
            CODES[key.ordinal] = code
        }

        fun cursor(
            key: TerminalKey,
            code: Int,
        ) = map(key, XtermKeyResource.CURSOR_KEYS, code)

        fun keypad(
            key: TerminalKey,
            code: Int,
        ) = map(key, XtermKeyResource.KEYPAD_KEYS, code)

        fun modifier(
            key: TerminalKey,
            code: Int,
        ) = map(key, XtermKeyResource.MODIFIER_KEYS, code)

        fun special(
            key: TerminalKey,
            code: Int,
        ) = map(key, XtermKeyResource.SPECIAL_KEYS, code)
        cursor(TerminalKey.HOME, 0xe250)
        cursor(TerminalKey.LEFT, 0xe251)
        cursor(TerminalKey.UP, 0xe252)
        cursor(TerminalKey.RIGHT, 0xe253)
        cursor(TerminalKey.DOWN, 0xe254)
        cursor(TerminalKey.PAGE_UP, 0xe255)
        cursor(TerminalKey.PAGE_DOWN, 0xe256)
        cursor(TerminalKey.END, 0xe257)
        cursor(TerminalKey.INSERT, 0xe263)
        cursor(TerminalKey.DELETE, 127)
        for (ordinal in TerminalKey.F1.ordinal..TerminalKey.F35.ordinal) {
            map(TerminalKey.entries[ordinal], XtermKeyResource.FUNCTION_KEYS, 0xe2be + ordinal - TerminalKey.F1.ordinal)
        }
        map(TerminalKey.PRINT_SCREEN, XtermKeyResource.FUNCTION_KEYS, 0xe261)
        map(TerminalKey.MENU, XtermKeyResource.FUNCTION_KEYS, 0xe267)
        special(TerminalKey.BACKSPACE, 8)
        special(TerminalKey.TAB, 9)
        special(TerminalKey.ENTER, 13)
        special(TerminalKey.ESCAPE, 27)
        special(TerminalKey.PAUSE, 19)
        special(TerminalKey.SCROLL_LOCK, 20)
        modifier(TerminalKey.CAPS_LOCK, 0xe2e5)
        modifier(TerminalKey.NUM_LOCK, 0xe27f)
        modifier(TerminalKey.LEFT_SHIFT, 0xe2e1)
        modifier(TerminalKey.RIGHT_SHIFT, 0xe2e2)
        modifier(TerminalKey.LEFT_CONTROL, 0xe2e3)
        modifier(TerminalKey.RIGHT_CONTROL, 0xe2e4)
        modifier(TerminalKey.LEFT_META, 0xe2e7)
        modifier(TerminalKey.RIGHT_META, 0xe2e8)
        modifier(TerminalKey.LEFT_ALT, 0xe2e9)
        modifier(TerminalKey.RIGHT_ALT, 0xe2ea)
        modifier(TerminalKey.LEFT_SUPER, 0xe2eb)
        modifier(TerminalKey.RIGHT_SUPER, 0xe2ec)
        modifier(TerminalKey.LEFT_HYPER, 0xe2ed)
        modifier(TerminalKey.RIGHT_HYPER, 0xe2ee)
        modifier(TerminalKey.ISO_LEVEL3_SHIFT, 0xe103)
        modifier(TerminalKey.ISO_LEVEL5_SHIFT, 0xe111)
        keypad(TerminalKey.NUMPAD_SPACE, 0xe280)
        keypad(TerminalKey.NUMPAD_TAB, 0xe289)
        keypad(TerminalKey.NUMPAD_ENTER, 0xe28d)
        for (ordinal in TerminalKey.PF1.ordinal..TerminalKey.PF4.ordinal) {
            keypad(TerminalKey.entries[ordinal], 0xe291 + ordinal - TerminalKey.PF1.ordinal)
        }
        keypad(TerminalKey.NUMPAD_HOME, 0xe295)
        keypad(TerminalKey.NUMPAD_LEFT, 0xe296)
        keypad(TerminalKey.NUMPAD_UP, 0xe297)
        keypad(TerminalKey.NUMPAD_RIGHT, 0xe298)
        keypad(TerminalKey.NUMPAD_DOWN, 0xe299)
        keypad(TerminalKey.NUMPAD_PAGE_UP, 0xe29a)
        keypad(TerminalKey.NUMPAD_PAGE_DOWN, 0xe29b)
        keypad(TerminalKey.NUMPAD_END, 0xe29c)
        keypad(TerminalKey.NUMPAD_BEGIN, 0xe29d)
        keypad(TerminalKey.NUMPAD_INSERT, 0xe29e)
        keypad(TerminalKey.NUMPAD_DELETE, 0xe29f)
        keypad(TerminalKey.NUMPAD_MULTIPLY, 0xe2aa)
        keypad(TerminalKey.NUMPAD_ADD, 0xe2ab)
        keypad(TerminalKey.NUMPAD_COMMA, 0xe2ac)
        keypad(TerminalKey.NUMPAD_SEPARATOR, 0xe2ac)
        keypad(TerminalKey.NUMPAD_SUBTRACT, 0xe2ad)
        keypad(TerminalKey.NUMPAD_DECIMAL, 0xe2ae)
        keypad(TerminalKey.NUMPAD_DIVIDE, 0xe2af)
        for (ordinal in TerminalKey.NUMPAD_0.ordinal..TerminalKey.NUMPAD_9.ordinal) {
            keypad(TerminalKey.entries[ordinal], 0xe2b0 + ordinal - TerminalKey.NUMPAD_0.ordinal)
        }
        keypad(TerminalKey.NUMPAD_EQUALS, 0xe2bd)
        // Media events retain the existing Kitty-only contract; no X11 identity is inferred.
    }

    /** DEC function-key numbers, extended sequentially after F20 as in xterm. */
    fun functionNumber(number: Int): Int =
        when (number) {
            in 1..5 -> number + 10
            in 6..10 -> number + 11
            in 11..14 -> number + 12
            in 15..16 -> number + 13
            in 17..20 -> number + 14
            else -> number + 21
        }
}
