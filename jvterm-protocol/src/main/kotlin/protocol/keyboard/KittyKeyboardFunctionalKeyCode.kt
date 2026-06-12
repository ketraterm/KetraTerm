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
package com.gagik.terminal.protocol.keyboard

/**
 * Kitty keyboard numeric codes for functional keys in the initial input slice.
 *
 * Printable Unicode scalar keys use their Unicode codepoint directly. These
 * constants cover the non-printable control-equivalent keys planned for the
 * first Kitty keyboard encoder milestone.
 */
object KittyKeyboardFunctionalKeyCode {
    /** Tab key. */
    const val TAB: Int = 0x09

    /** Main Enter key. */
    const val ENTER: Int = 0x0d

    /** Escape key. */
    const val ESCAPE: Int = 0x1b

    /** Backspace key. */
    const val BACKSPACE: Int = 0x7f

    // Keypad keys (mapped to Private Use Area)
    const val KP_0: Int = 57399
    const val KP_1: Int = 57400
    const val KP_2: Int = 57401
    const val KP_3: Int = 57402
    const val KP_4: Int = 57403
    const val KP_5: Int = 57404
    const val KP_6: Int = 57405
    const val KP_7: Int = 57406
    const val KP_8: Int = 57407
    const val KP_9: Int = 57408
    const val KP_DECIMAL: Int = 57409
    const val KP_DIVIDE: Int = 57410
    const val KP_MULTIPLY: Int = 57411
    const val KP_SUBTRACT: Int = 57412
    const val KP_ADD: Int = 57413
    const val KP_ENTER: Int = 57414
    const val KP_EQUAL: Int = 57415
    const val KP_SEPARATOR: Int = 57416
    const val KP_BEGIN: Int = 57427
}
