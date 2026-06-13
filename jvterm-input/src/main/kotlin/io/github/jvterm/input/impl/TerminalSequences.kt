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
package io.github.jvterm.input.impl

internal object TerminalSequences {
    val BACK_TAB: ByteArray = ascii("\u001b[Z")

    val CURSOR_UP_NORMAL: ByteArray = ascii("\u001b[A")
    val CURSOR_DOWN_NORMAL: ByteArray = ascii("\u001b[B")
    val CURSOR_RIGHT_NORMAL: ByteArray = ascii("\u001b[C")
    val CURSOR_LEFT_NORMAL: ByteArray = ascii("\u001b[D")

    val CURSOR_UP_APPLICATION: ByteArray = ascii("\u001bOA")
    val CURSOR_DOWN_APPLICATION: ByteArray = ascii("\u001bOB")
    val CURSOR_RIGHT_APPLICATION: ByteArray = ascii("\u001bOC")
    val CURSOR_LEFT_APPLICATION: ByteArray = ascii("\u001bOD")

    val HOME_NORMAL: ByteArray = ascii("\u001b[H")
    val END_NORMAL: ByteArray = ascii("\u001b[F")
    val HOME_APPLICATION: ByteArray = ascii("\u001bOH")
    val END_APPLICATION: ByteArray = ascii("\u001bOF")

    val INSERT: ByteArray = ascii("\u001b[2~")
    val DELETE: ByteArray = ascii("\u001b[3~")
    val PAGE_UP: ByteArray = ascii("\u001b[5~")
    val PAGE_DOWN: ByteArray = ascii("\u001b[6~")

    val F1: ByteArray = ascii("\u001bOP")
    val F2: ByteArray = ascii("\u001bOQ")
    val F3: ByteArray = ascii("\u001bOR")
    val F4: ByteArray = ascii("\u001bOS")
    val F5: ByteArray = ascii("\u001b[15~")
    val F6: ByteArray = ascii("\u001b[17~")
    val F7: ByteArray = ascii("\u001b[18~")
    val F8: ByteArray = ascii("\u001b[19~")
    val F9: ByteArray = ascii("\u001b[20~")
    val F10: ByteArray = ascii("\u001b[21~")
    val F11: ByteArray = ascii("\u001b[23~")
    val F12: ByteArray = ascii("\u001b[24~")

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
