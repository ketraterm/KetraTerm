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
 * Kitty keyboard progressive-enhancement bit flags used by `CSI = flags ; mode u`.
 *
 * Values mirror kitty's "Comprehensive keyboard handling in terminals"
 * protocol:
 * <https://sw.kovidgoyal.net/kitty/keyboard-protocol/>.
 */
object KittyKeyboardProgressiveFlag {
    /** Disambiguate legacy escape-code collisions. */
    const val DISAMBIGUATE_ESCAPE_CODES: Int = 1

    /** Include press/repeat/release event-type information. */
    const val REPORT_EVENT_TYPES: Int = 1 shl 1

    /** Include alternate key values for shortcut matching. */
    const val REPORT_ALTERNATE_KEYS: Int = 1 shl 2

    /** Report text-producing keys as CSI-u escape codes. */
    const val REPORT_ALL_KEYS_AS_ESCAPE_CODES: Int = 1 shl 3

    /** Include associated text as codepoints. */
    const val REPORT_ASSOCIATED_TEXT: Int = 1 shl 4

    /** Mask containing every supported progressive-enhancement flag. */
    const val SUPPORTED_MASK: Int =
        DISAMBIGUATE_ESCAPE_CODES or
            REPORT_EVENT_TYPES or
            REPORT_ALTERNATE_KEYS or
            REPORT_ALL_KEYS_AS_ESCAPE_CODES or
            REPORT_ASSOCIATED_TEXT
}
