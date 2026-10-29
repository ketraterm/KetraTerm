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
package io.github.ketraterm.input.impl

import io.github.ketraterm.protocol.keyboard.LegacyKeySequence

internal object TerminalSequences {
    val BACK_TAB: ByteArray = ascii(LegacyKeySequence.BACK_TAB)

    val CURSOR_UP_NORMAL: ByteArray = ascii(LegacyKeySequence.CURSOR_UP_NORMAL)
    val CURSOR_DOWN_NORMAL: ByteArray = ascii(LegacyKeySequence.CURSOR_DOWN_NORMAL)
    val CURSOR_RIGHT_NORMAL: ByteArray = ascii(LegacyKeySequence.CURSOR_RIGHT_NORMAL)
    val CURSOR_LEFT_NORMAL: ByteArray = ascii(LegacyKeySequence.CURSOR_LEFT_NORMAL)

    val CURSOR_UP_APPLICATION: ByteArray = ascii(LegacyKeySequence.CURSOR_UP_APPLICATION)
    val CURSOR_DOWN_APPLICATION: ByteArray = ascii(LegacyKeySequence.CURSOR_DOWN_APPLICATION)
    val CURSOR_RIGHT_APPLICATION: ByteArray = ascii(LegacyKeySequence.CURSOR_RIGHT_APPLICATION)
    val CURSOR_LEFT_APPLICATION: ByteArray = ascii(LegacyKeySequence.CURSOR_LEFT_APPLICATION)

    val HOME_NORMAL: ByteArray = ascii(LegacyKeySequence.HOME_NORMAL)
    val END_NORMAL: ByteArray = ascii(LegacyKeySequence.END_NORMAL)
    val HOME_APPLICATION: ByteArray = ascii(LegacyKeySequence.HOME_APPLICATION)
    val END_APPLICATION: ByteArray = ascii(LegacyKeySequence.END_APPLICATION)

    val INSERT: ByteArray = ascii(LegacyKeySequence.INSERT)
    val DELETE: ByteArray = ascii(LegacyKeySequence.DELETE)
    val PAGE_UP: ByteArray = ascii(LegacyKeySequence.PAGE_UP)
    val PAGE_DOWN: ByteArray = ascii(LegacyKeySequence.PAGE_DOWN)

    val F1: ByteArray = ascii(LegacyKeySequence.F1)
    val F2: ByteArray = ascii(LegacyKeySequence.F2)
    val F3: ByteArray = ascii(LegacyKeySequence.F3)
    val F4: ByteArray = ascii(LegacyKeySequence.F4)
    val F5: ByteArray = ascii(LegacyKeySequence.F5)
    val F6: ByteArray = ascii(LegacyKeySequence.F6)
    val F7: ByteArray = ascii(LegacyKeySequence.F7)
    val F8: ByteArray = ascii(LegacyKeySequence.F8)
    val F9: ByteArray = ascii(LegacyKeySequence.F9)
    val F10: ByteArray = ascii(LegacyKeySequence.F10)
    val F11: ByteArray = ascii(LegacyKeySequence.F11)
    val F12: ByteArray = ascii(LegacyKeySequence.F12)

    val BRACKETED_PASTE_START: ByteArray = ascii("\u001b[200~")
    val BRACKETED_PASTE_END: ByteArray = ascii("\u001b[201~")

    val FOCUS_IN: ByteArray = ascii("\u001b[I")
    val FOCUS_OUT: ByteArray = ascii("\u001b[O")

    private fun ascii(text: String): ByteArray {
        val bytes = ByteArray(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i].code
            require(ch in 0..0x7f) { "non-ASCII sequence literal" }
            bytes[i] = ch.toByte()
            i++
        }
        return bytes
    }
}
