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
 * Fixed legacy keyboard wire vocabulary shared by capability discovery and input encoding.
 * Strings contain actual control bytes, not terminfo source escapes. They describe
 * unmodified keys except [BACK_TAB]; extended keyboard formats have separate vocabulary.
 */
public object LegacyKeySequence {
    /** Shift-Tab in the legacy keyboard protocol. */
    public const val BACK_TAB: String = "\u001b[Z"

    /** Up in normal cursor mode. */
    public const val CURSOR_UP_NORMAL: String = "\u001b[A"

    /** Down in normal cursor mode. */
    public const val CURSOR_DOWN_NORMAL: String = "\u001b[B"

    /** Right in normal cursor mode. */
    public const val CURSOR_RIGHT_NORMAL: String = "\u001b[C"

    /** Left in normal cursor mode. */
    public const val CURSOR_LEFT_NORMAL: String = "\u001b[D"

    /** Up in application cursor mode. */
    public const val CURSOR_UP_APPLICATION: String = "\u001bOA"

    /** Down in application cursor mode. */
    public const val CURSOR_DOWN_APPLICATION: String = "\u001bOB"

    /** Right in application cursor mode. */
    public const val CURSOR_RIGHT_APPLICATION: String = "\u001bOC"

    /** Left in application cursor mode. */
    public const val CURSOR_LEFT_APPLICATION: String = "\u001bOD"

    /** Home in normal cursor mode. */
    public const val HOME_NORMAL: String = "\u001b[H"

    /** End in normal cursor mode. */
    public const val END_NORMAL: String = "\u001b[F"

    /** Home in application cursor mode. */
    public const val HOME_APPLICATION: String = "\u001bOH"

    /** End in application cursor mode. */
    public const val END_APPLICATION: String = "\u001bOF"

    /** Insert in the legacy keyboard protocol. */
    public const val INSERT: String = "\u001b[2~"

    /** Delete in the legacy keyboard protocol. */
    public const val DELETE: String = "\u001b[3~"

    /** Page Up in the legacy keyboard protocol. */
    public const val PAGE_UP: String = "\u001b[5~"

    /** Page Down in the legacy keyboard protocol. */
    public const val PAGE_DOWN: String = "\u001b[6~"

    /** Numeric keypad 7 (upper-left corner) in application keypad mode. */
    public const val KEYPAD_7_APPLICATION: String = "\u001bOw"

    /** Numeric keypad 9 (upper-right corner) in application keypad mode. */
    public const val KEYPAD_9_APPLICATION: String = "\u001bOy"

    /** Numeric keypad 1 (lower-left corner) in application keypad mode. */
    public const val KEYPAD_1_APPLICATION: String = "\u001bOq"

    /** Numeric keypad 3 (lower-right corner) in application keypad mode. */
    public const val KEYPAD_3_APPLICATION: String = "\u001bOs"

    /** F1 in the legacy keyboard protocol. */
    public const val F1: String = "\u001bOP"

    /** F2 in the legacy keyboard protocol. */
    public const val F2: String = "\u001bOQ"

    /** F3 in the legacy keyboard protocol. */
    public const val F3: String = "\u001bOR"

    /** F4 in the legacy keyboard protocol. */
    public const val F4: String = "\u001bOS"

    /** F5 in the legacy keyboard protocol. */
    public const val F5: String = "\u001b[15~"

    /** F6 in the legacy keyboard protocol. */
    public const val F6: String = "\u001b[17~"

    /** F7 in the legacy keyboard protocol. */
    public const val F7: String = "\u001b[18~"

    /** F8 in the legacy keyboard protocol. */
    public const val F8: String = "\u001b[19~"

    /** F9 in the legacy keyboard protocol. */
    public const val F9: String = "\u001b[20~"

    /** F10 in the legacy keyboard protocol. */
    public const val F10: String = "\u001b[21~"

    /** F11 in the legacy keyboard protocol. */
    public const val F11: String = "\u001b[23~"

    /** F12 in the legacy keyboard protocol. */
    public const val F12: String = "\u001b[24~"
}
