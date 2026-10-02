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
package io.github.ketraterm.protocol.keyboard

/**
 * Kitty keyboard numeric codes for functional keys in the initial input slice.
 *
 * Printable Unicode scalar keys use their Unicode codepoint directly. These
 * constants cover the non-printable control-equivalent keys planned for the
 * first Kitty keyboard encoder milestone.
 */
public object KittyKeyboardFunctionalKeyCode {
    /** Tab key. */
    public const val TAB: Int = 0x09

    /** Main Enter key. */
    public const val ENTER: Int = 0x0d

    /** Escape key. */
    public const val ESCAPE: Int = 0x1b

    /** Backspace key. */
    public const val BACKSPACE: Int = 0x7f

    /** First extended function-key code; F13 through F35 are contiguous. */
    public const val F13: Int = 57376

    /** Final extended function-key code, corresponding to F35. */
    public const val F35: Int = 57398

    /** First lock/system-key code; Caps Lock through Menu are contiguous. */
    public const val CAPS_LOCK: Int = 57358

    /** First keypad-navigation code; KP Left through KP Delete are contiguous. */
    public const val KP_LEFT: Int = 57417

    /** First media/volume code; Media Play through Volume Mute are contiguous. */
    public const val MEDIA_PLAY: Int = 57428

    /** First physical modifier-key code; Left Shift through ISO Level 5 Shift are contiguous. */
    public const val LEFT_SHIFT: Int = 57441

    // Keypad keys (mapped to Private Use Area)

    /** Keypad 0. */
    public const val KP_0: Int = 57399

    /** Keypad 1. */
    public const val KP_1: Int = 57400

    /** Keypad 2. */
    public const val KP_2: Int = 57401

    /** Keypad 3. */
    public const val KP_3: Int = 57402

    /** Keypad 4. */
    public const val KP_4: Int = 57403

    /** Keypad 5. */
    public const val KP_5: Int = 57404

    /** Keypad 6. */
    public const val KP_6: Int = 57405

    /** Keypad 7. */
    public const val KP_7: Int = 57406

    /** Keypad 8. */
    public const val KP_8: Int = 57407

    /** Keypad 9. */
    public const val KP_9: Int = 57408

    /** Keypad decimal point. */
    public const val KP_DECIMAL: Int = 57409

    /** Keypad division sign. */
    public const val KP_DIVIDE: Int = 57410

    /** Keypad multiplication sign. */
    public const val KP_MULTIPLY: Int = 57411

    /** Keypad subtraction sign. */
    public const val KP_SUBTRACT: Int = 57412

    /** Keypad addition sign. */
    public const val KP_ADD: Int = 57413

    /** Keypad enter key. */
    public const val KP_ENTER: Int = 57414

    /** Keypad equal sign. */
    public const val KP_EQUAL: Int = 57415

    /** Keypad separator. */
    public const val KP_SEPARATOR: Int = 57416

    /** Keypad begin key (center button of 5-keypad). */
    public const val KP_BEGIN: Int = 57427
}
