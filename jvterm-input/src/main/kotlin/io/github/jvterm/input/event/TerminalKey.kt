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
package io.github.jvterm.input.event

/**
 * Non-printable terminal keys accepted by the input encoder.
 *
 * Printable text is represented as a Unicode scalar on [TerminalKeyEvent],
 * never as a `TerminalKey` enum value.
 */
enum class TerminalKey {
    /** Cursor-up key. */
    UP,

    /** Cursor-down key. */
    DOWN,

    /** Cursor-left key. */
    LEFT,

    /** Cursor-right key. */
    RIGHT,

    /** Home key. */
    HOME,

    /** End key. */
    END,

    /** Page-up key. */
    PAGE_UP,

    /** Page-down key. */
    PAGE_DOWN,

    /** Insert key. */
    INSERT,

    /** Delete key. */
    DELETE,

    /** Backspace key. */
    BACKSPACE,

    /** Main keyboard enter key. */
    ENTER,

    /** Tab key. */
    TAB,

    /** Escape key. */
    ESCAPE,

    /** Function key F1. */
    F1,

    /** Function key F2. */
    F2,

    /** Function key F3. */
    F3,

    /** Function key F4. */
    F4,

    /** Function key F5. */
    F5,

    /** Function key F6. */
    F6,

    /** Function key F7. */
    F7,

    /** Function key F8. */
    F8,

    /** Function key F9. */
    F9,

    /** Function key F10. */
    F10,

    /** Function key F11. */
    F11,

    /** Function key F12. */
    F12,

    /** Keypad PF1 key. */
    PF1,

    /** Keypad PF2 key. */
    PF2,

    /** Keypad PF3 key. */
    PF3,

    /** Keypad PF4 key. */
    PF4,

    /** Numeric keypad space key. */
    NUMPAD_SPACE,

    /** Numeric keypad tab key. */
    NUMPAD_TAB,

    /** Numeric keypad enter key. */
    NUMPAD_ENTER,

    /** Numeric keypad divide key. */
    NUMPAD_DIVIDE,

    /** Numeric keypad multiply key. */
    NUMPAD_MULTIPLY,

    /** Numeric keypad subtract key. */
    NUMPAD_SUBTRACT,

    /** Numeric keypad add key. */
    NUMPAD_ADD,

    /** Numeric keypad comma key. */
    NUMPAD_COMMA,

    /** Numeric keypad separator key. */
    NUMPAD_SEPARATOR,

    /** Numeric keypad equals key. */
    NUMPAD_EQUALS,

    /** Numeric keypad begin key, usually the keypad 5 navigation function. */
    NUMPAD_BEGIN,

    /** Numeric keypad decimal key. */
    NUMPAD_DECIMAL,

    /** Numeric keypad 0 key. */
    NUMPAD_0,

    /** Numeric keypad 1 key. */
    NUMPAD_1,

    /** Numeric keypad 2 key. */
    NUMPAD_2,

    /** Numeric keypad 3 key. */
    NUMPAD_3,

    /** Numeric keypad 4 key. */
    NUMPAD_4,

    /** Numeric keypad 5 key. */
    NUMPAD_5,

    /** Numeric keypad 6 key. */
    NUMPAD_6,

    /** Numeric keypad 7 key. */
    NUMPAD_7,

    /** Numeric keypad 8 key. */
    NUMPAD_8,

    /** Numeric keypad 9 key. */
    NUMPAD_9,
}
