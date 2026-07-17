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
package io.github.ketraterm.input.event

/**
 * Non-printable terminal keys accepted by the input encoder.
 *
 * Printable text is represented as a Unicode scalar on [io.github.ketraterm.input.event.TerminalKeyEvent],
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

    /** Function key F13. */
    F13,

    /** Function key F14. */
    F14,

    /** Function key F15. */
    F15,

    /** Function key F16. */
    F16,

    /** Function key F17. */
    F17,

    /** Function key F18. */
    F18,

    /** Function key F19. */
    F19,

    /** Function key F20. */
    F20,

    /** Function key F21. */
    F21,

    /** Function key F22. */
    F22,

    /** Function key F23. */
    F23,

    /** Function key F24. */
    F24,

    /** Function key F25. */
    F25,

    /** Function key F26. */
    F26,

    /** Function key F27. */
    F27,

    /** Function key F28. */
    F28,

    /** Function key F29. */
    F29,

    /** Function key F30. */
    F30,

    /** Function key F31. */
    F31,

    /** Function key F32. */
    F32,

    /** Function key F33. */
    F33,

    /** Function key F34. */
    F34,

    /** Function key F35. */
    F35,

    /** Caps Lock key. */
    CAPS_LOCK,

    /** Scroll Lock key. */
    SCROLL_LOCK,

    /** Num Lock key. */
    NUM_LOCK,

    /** Print Screen key. */
    PRINT_SCREEN,

    /** Pause key. */
    PAUSE,

    /** Context-menu key. */
    MENU,

    /** Numeric keypad Left key. */
    NUMPAD_LEFT,

    /** Numeric keypad Right key. */
    NUMPAD_RIGHT,

    /** Numeric keypad Up key. */
    NUMPAD_UP,

    /** Numeric keypad Down key. */
    NUMPAD_DOWN,

    /** Numeric keypad Page Up key. */
    NUMPAD_PAGE_UP,

    /** Numeric keypad Page Down key. */
    NUMPAD_PAGE_DOWN,

    /** Numeric keypad Home key. */
    NUMPAD_HOME,

    /** Numeric keypad End key. */
    NUMPAD_END,

    /** Numeric keypad Insert key. */
    NUMPAD_INSERT,

    /** Numeric keypad Delete key. */
    NUMPAD_DELETE,

    /** Media play key. */
    MEDIA_PLAY,

    /** Media pause key. */
    MEDIA_PAUSE,

    /** Media play/pause key. */
    MEDIA_PLAY_PAUSE,

    /** Media reverse key. */
    MEDIA_REVERSE,

    /** Media stop key. */
    MEDIA_STOP,

    /** Media fast-forward key. */
    MEDIA_FAST_FORWARD,

    /** Media rewind key. */
    MEDIA_REWIND,

    /** Media next-track key. */
    MEDIA_TRACK_NEXT,

    /** Media previous-track key. */
    MEDIA_TRACK_PREVIOUS,

    /** Media record key. */
    MEDIA_RECORD,

    /** Volume-down key. */
    VOLUME_DOWN,

    /** Volume-up key. */
    VOLUME_UP,

    /** Volume-mute key. */
    VOLUME_MUTE,

    /** Left Shift key. */
    LEFT_SHIFT,

    /** Left Control key. */
    LEFT_CONTROL,

    /** Left Alt key. */
    LEFT_ALT,

    /** Left Super key. */
    LEFT_SUPER,

    /** Left Hyper key. */
    LEFT_HYPER,

    /** Left Meta key. */
    LEFT_META,

    /** Right Shift key. */
    RIGHT_SHIFT,

    /** Right Control key. */
    RIGHT_CONTROL,

    /** Right Alt key. */
    RIGHT_ALT,

    /** Right Super key. */
    RIGHT_SUPER,

    /** Right Hyper key. */
    RIGHT_HYPER,

    /** Right Meta key. */
    RIGHT_META,

    /** ISO Level 3 Shift key. */
    ISO_LEVEL3_SHIFT,

    /** ISO Level 5 Shift key. */
    ISO_LEVEL5_SHIFT,

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
