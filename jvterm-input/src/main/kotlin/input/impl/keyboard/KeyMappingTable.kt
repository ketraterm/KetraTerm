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
package com.gagik.terminal.input.impl.keyboard

import com.gagik.terminal.input.event.TerminalKey
import com.gagik.terminal.input.impl.TerminalSequences
import com.gagik.terminal.protocol.ControlCode
import com.gagik.terminal.protocol.keyboard.KittyKeyboardFunctionalKeyCode

/**
 * Data-oriented lookup tables for mapping [TerminalKey] values.
 *
 * Provides $O(1)$ zero-allocation lookups for key properties such as CSI final characters,
 * legacy tilde numbers, Kitty keyboard PUA codes, application keypad codes, and static sequence byte arrays.
 * Using primitive arrays indexed by [TerminalKey.ordinal] avoids map/object allocations on the input hot path.
 */
internal object KeyMappingTable {
    private val entries = TerminalKey.entries

    /**
     * Map of [TerminalKey.ordinal] to CSI letter final byte ASCII codes (e.g., 'A' for Cursor Up).
     * Entries are -1 if the key does not use a simple CSI letter encoding.
     */
    val CSI_LETTERS: IntArray = IntArray(entries.size) { -1 }

    /**
     * Map of [TerminalKey.ordinal] to legacy CSI tilde parameter numbers (e.g., 3 for Delete, 15 for F5).
     * Entries are -1 if the key does not use a tilde sequence.
     */
    val TILDE_NUMBERS: IntArray = IntArray(entries.size) { -1 }

    /**
     * Map of [TerminalKey.ordinal] to Kitty keyboard protocol Unicode Private Use Area (PUA) codes
     * (e.g., KP_0, KP_1, etc.).
     * Entries are -1 if the key does not map to a Kitty functional/keypad PUA code.
     */
    val KITTY_PUA_CODES: IntArray = IntArray(entries.size) { -1 }

    /**
     * Map of [TerminalKey.ordinal] to final characters for application keypad mode sequences
     * (e.g., 'p' for Numpad 0 in application mode, resulting in `ESC O p`).
     * Entries are -1 if the key is not affected by application keypad mode.
     */
    val APPLICATION_KEYPAD_FINALS: IntArray = IntArray(entries.size) { -1 }

    /**
     * Map of [TerminalKey.ordinal] to ASCII values emitted in normal keypad mode
     * (e.g., '0' for Numpad 0, '+' for Numpad Add).
     * Entries are -1 if the key is not affected by normal keypad mode.
     */
    val NORMAL_KEYPAD_ASCII: IntArray = IntArray(entries.size) { -1 }

    /**
     * Map of [TerminalKey.ordinal] to pre-allocated byte arrays for static unmodified key sequences
     * in normal mode. Reduces hot-path writes to copying a constant byte array.
     */
    val STATIC_SEQUENCES: Array<ByteArray?> = Array(entries.size) { null }

    /**
     * Map of [TerminalKey.ordinal] to pre-allocated byte arrays for static unmodified key sequences
     * in application cursor mode (e.g., Cursor keys and Home/End keys).
     */
    val STATIC_SEQUENCES_APP: Array<ByteArray?> = Array(entries.size) { null }

    init {
        // CSI Letters
        CSI_LETTERS[TerminalKey.UP.ordinal] = 'A'.code
        CSI_LETTERS[TerminalKey.DOWN.ordinal] = 'B'.code
        CSI_LETTERS[TerminalKey.RIGHT.ordinal] = 'C'.code
        CSI_LETTERS[TerminalKey.LEFT.ordinal] = 'D'.code
        CSI_LETTERS[TerminalKey.HOME.ordinal] = 'H'.code
        CSI_LETTERS[TerminalKey.END.ordinal] = 'F'.code
        CSI_LETTERS[TerminalKey.F1.ordinal] = 'P'.code
        CSI_LETTERS[TerminalKey.F2.ordinal] = 'Q'.code
        CSI_LETTERS[TerminalKey.F4.ordinal] = 'S'.code
        CSI_LETTERS[TerminalKey.PF1.ordinal] = 'P'.code
        CSI_LETTERS[TerminalKey.PF2.ordinal] = 'Q'.code
        CSI_LETTERS[TerminalKey.PF4.ordinal] = 'S'.code

        // Tilde numbers
        TILDE_NUMBERS[TerminalKey.INSERT.ordinal] = 2
        TILDE_NUMBERS[TerminalKey.DELETE.ordinal] = 3
        TILDE_NUMBERS[TerminalKey.PAGE_UP.ordinal] = 5
        TILDE_NUMBERS[TerminalKey.PAGE_DOWN.ordinal] = 6
        TILDE_NUMBERS[TerminalKey.F3.ordinal] = 13
        TILDE_NUMBERS[TerminalKey.PF3.ordinal] = 13
        TILDE_NUMBERS[TerminalKey.F5.ordinal] = 15
        TILDE_NUMBERS[TerminalKey.F6.ordinal] = 17
        TILDE_NUMBERS[TerminalKey.F7.ordinal] = 18
        TILDE_NUMBERS[TerminalKey.F8.ordinal] = 19
        TILDE_NUMBERS[TerminalKey.F9.ordinal] = 20
        TILDE_NUMBERS[TerminalKey.F10.ordinal] = 21
        TILDE_NUMBERS[TerminalKey.F11.ordinal] = 23
        TILDE_NUMBERS[TerminalKey.F12.ordinal] = 24

        // Kitty PUA codes (Removed TAB, ENTER, ESCAPE, BACKSPACE from here because they are conditionally encoded)
        KITTY_PUA_CODES[TerminalKey.NUMPAD_0.ordinal] = KittyKeyboardFunctionalKeyCode.KP_0
        KITTY_PUA_CODES[TerminalKey.NUMPAD_1.ordinal] = KittyKeyboardFunctionalKeyCode.KP_1
        KITTY_PUA_CODES[TerminalKey.NUMPAD_2.ordinal] = KittyKeyboardFunctionalKeyCode.KP_2
        KITTY_PUA_CODES[TerminalKey.NUMPAD_3.ordinal] = KittyKeyboardFunctionalKeyCode.KP_3
        KITTY_PUA_CODES[TerminalKey.NUMPAD_4.ordinal] = KittyKeyboardFunctionalKeyCode.KP_4
        KITTY_PUA_CODES[TerminalKey.NUMPAD_5.ordinal] = KittyKeyboardFunctionalKeyCode.KP_5
        KITTY_PUA_CODES[TerminalKey.NUMPAD_6.ordinal] = KittyKeyboardFunctionalKeyCode.KP_6
        KITTY_PUA_CODES[TerminalKey.NUMPAD_7.ordinal] = KittyKeyboardFunctionalKeyCode.KP_7
        KITTY_PUA_CODES[TerminalKey.NUMPAD_8.ordinal] = KittyKeyboardFunctionalKeyCode.KP_8
        KITTY_PUA_CODES[TerminalKey.NUMPAD_9.ordinal] = KittyKeyboardFunctionalKeyCode.KP_9
        KITTY_PUA_CODES[TerminalKey.NUMPAD_DECIMAL.ordinal] = KittyKeyboardFunctionalKeyCode.KP_DECIMAL
        KITTY_PUA_CODES[TerminalKey.NUMPAD_DIVIDE.ordinal] = KittyKeyboardFunctionalKeyCode.KP_DIVIDE
        KITTY_PUA_CODES[TerminalKey.NUMPAD_MULTIPLY.ordinal] = KittyKeyboardFunctionalKeyCode.KP_MULTIPLY
        KITTY_PUA_CODES[TerminalKey.NUMPAD_SUBTRACT.ordinal] = KittyKeyboardFunctionalKeyCode.KP_SUBTRACT
        KITTY_PUA_CODES[TerminalKey.NUMPAD_ADD.ordinal] = KittyKeyboardFunctionalKeyCode.KP_ADD
        KITTY_PUA_CODES[TerminalKey.NUMPAD_ENTER.ordinal] = KittyKeyboardFunctionalKeyCode.KP_ENTER
        KITTY_PUA_CODES[TerminalKey.NUMPAD_EQUALS.ordinal] = KittyKeyboardFunctionalKeyCode.KP_EQUAL
        KITTY_PUA_CODES[TerminalKey.NUMPAD_COMMA.ordinal] = KittyKeyboardFunctionalKeyCode.KP_SEPARATOR
        KITTY_PUA_CODES[TerminalKey.NUMPAD_SEPARATOR.ordinal] = KittyKeyboardFunctionalKeyCode.KP_SEPARATOR
        KITTY_PUA_CODES[TerminalKey.NUMPAD_BEGIN.ordinal] = KittyKeyboardFunctionalKeyCode.KP_BEGIN
        KITTY_PUA_CODES[TerminalKey.NUMPAD_SPACE.ordinal] = 32
        KITTY_PUA_CODES[TerminalKey.NUMPAD_TAB.ordinal] = ControlCode.HT

        // Application Keypad Finals
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_SPACE.ordinal] = ' '.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_TAB.ordinal] = 'I'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_ENTER.ordinal] = 'M'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_0.ordinal] = 'p'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_1.ordinal] = 'q'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_2.ordinal] = 'r'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_3.ordinal] = 's'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_4.ordinal] = 't'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_5.ordinal] = 'u'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_6.ordinal] = 'v'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_7.ordinal] = 'w'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_8.ordinal] = 'x'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_9.ordinal] = 'y'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_DECIMAL.ordinal] = 'n'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_DIVIDE.ordinal] = 'o'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_MULTIPLY.ordinal] = 'j'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_SUBTRACT.ordinal] = 'm'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_ADD.ordinal] = 'k'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_COMMA.ordinal] = 'l'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_SEPARATOR.ordinal] = 'l'.code
        APPLICATION_KEYPAD_FINALS[TerminalKey.NUMPAD_EQUALS.ordinal] = 'X'.code

        // Normal Keypad ASCII
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_SPACE.ordinal] = ' '.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_TAB.ordinal] = ControlCode.HT
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_0.ordinal] = '0'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_1.ordinal] = '1'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_2.ordinal] = '2'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_3.ordinal] = '3'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_4.ordinal] = '4'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_5.ordinal] = '5'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_6.ordinal] = '6'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_7.ordinal] = '7'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_8.ordinal] = '8'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_9.ordinal] = '9'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_DECIMAL.ordinal] = '.'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_DIVIDE.ordinal] = '/'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_MULTIPLY.ordinal] = '*'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_SUBTRACT.ordinal] = '-'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_ADD.ordinal] = '+'.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_COMMA.ordinal] = ','.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_SEPARATOR.ordinal] = ','.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_EQUALS.ordinal] = '='.code
        NORMAL_KEYPAD_ASCII[TerminalKey.NUMPAD_BEGIN.ordinal] = '5'.code

        // Static unmodified sequences (zero-allocation)
        STATIC_SEQUENCES[TerminalKey.UP.ordinal] = TerminalSequences.CURSOR_UP_NORMAL
        STATIC_SEQUENCES[TerminalKey.DOWN.ordinal] = TerminalSequences.CURSOR_DOWN_NORMAL
        STATIC_SEQUENCES[TerminalKey.RIGHT.ordinal] = TerminalSequences.CURSOR_RIGHT_NORMAL
        STATIC_SEQUENCES[TerminalKey.LEFT.ordinal] = TerminalSequences.CURSOR_LEFT_NORMAL
        STATIC_SEQUENCES[TerminalKey.HOME.ordinal] = TerminalSequences.HOME_NORMAL
        STATIC_SEQUENCES[TerminalKey.END.ordinal] = TerminalSequences.END_NORMAL
        STATIC_SEQUENCES[TerminalKey.F1.ordinal] = TerminalSequences.F1
        STATIC_SEQUENCES[TerminalKey.F2.ordinal] = TerminalSequences.F2
        STATIC_SEQUENCES[TerminalKey.F3.ordinal] = TerminalSequences.F3
        STATIC_SEQUENCES[TerminalKey.F4.ordinal] = TerminalSequences.F4
        STATIC_SEQUENCES[TerminalKey.F5.ordinal] = TerminalSequences.F5
        STATIC_SEQUENCES[TerminalKey.F6.ordinal] = TerminalSequences.F6
        STATIC_SEQUENCES[TerminalKey.F7.ordinal] = TerminalSequences.F7
        STATIC_SEQUENCES[TerminalKey.F8.ordinal] = TerminalSequences.F8
        STATIC_SEQUENCES[TerminalKey.F9.ordinal] = TerminalSequences.F9
        STATIC_SEQUENCES[TerminalKey.F10.ordinal] = TerminalSequences.F10
        STATIC_SEQUENCES[TerminalKey.F11.ordinal] = TerminalSequences.F11
        STATIC_SEQUENCES[TerminalKey.F12.ordinal] = TerminalSequences.F12
        STATIC_SEQUENCES[TerminalKey.INSERT.ordinal] = TerminalSequences.INSERT
        STATIC_SEQUENCES[TerminalKey.DELETE.ordinal] = TerminalSequences.DELETE
        STATIC_SEQUENCES[TerminalKey.PAGE_UP.ordinal] = TerminalSequences.PAGE_UP
        STATIC_SEQUENCES[TerminalKey.PAGE_DOWN.ordinal] = TerminalSequences.PAGE_DOWN
        // PF map to the same F1-F4
        STATIC_SEQUENCES[TerminalKey.PF1.ordinal] = TerminalSequences.F1
        STATIC_SEQUENCES[TerminalKey.PF2.ordinal] = TerminalSequences.F2
        STATIC_SEQUENCES[TerminalKey.PF3.ordinal] = TerminalSequences.F3
        STATIC_SEQUENCES[TerminalKey.PF4.ordinal] = TerminalSequences.F4

        // App sequences
        STATIC_SEQUENCES_APP[TerminalKey.UP.ordinal] = TerminalSequences.CURSOR_UP_APPLICATION
        STATIC_SEQUENCES_APP[TerminalKey.DOWN.ordinal] = TerminalSequences.CURSOR_DOWN_APPLICATION
        STATIC_SEQUENCES_APP[TerminalKey.RIGHT.ordinal] = TerminalSequences.CURSOR_RIGHT_APPLICATION
        STATIC_SEQUENCES_APP[TerminalKey.LEFT.ordinal] = TerminalSequences.CURSOR_LEFT_APPLICATION
        STATIC_SEQUENCES_APP[TerminalKey.HOME.ordinal] = TerminalSequences.HOME_APPLICATION
        STATIC_SEQUENCES_APP[TerminalKey.END.ordinal] = TerminalSequences.END_APPLICATION
    }
}
