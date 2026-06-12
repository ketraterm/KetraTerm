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
package com.gagik.terminal.input.impl

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class TerminalSequencesTest {
    @Test
    fun `stores cursor key sequences`() {
        assertAll(
            { assertSequence("\u001b[A", TerminalSequences.CURSOR_UP_NORMAL) },
            { assertSequence("\u001b[B", TerminalSequences.CURSOR_DOWN_NORMAL) },
            { assertSequence("\u001b[C", TerminalSequences.CURSOR_RIGHT_NORMAL) },
            { assertSequence("\u001b[D", TerminalSequences.CURSOR_LEFT_NORMAL) },
            { assertSequence("\u001bOA", TerminalSequences.CURSOR_UP_APPLICATION) },
            { assertSequence("\u001bOB", TerminalSequences.CURSOR_DOWN_APPLICATION) },
            { assertSequence("\u001bOC", TerminalSequences.CURSOR_RIGHT_APPLICATION) },
            { assertSequence("\u001bOD", TerminalSequences.CURSOR_LEFT_APPLICATION) },
        )
    }

    @Test
    fun `stores navigation key sequences`() {
        assertAll(
            { assertSequence("\u001b[Z", TerminalSequences.BACK_TAB) },
            { assertSequence("\u001b[H", TerminalSequences.HOME_NORMAL) },
            { assertSequence("\u001b[F", TerminalSequences.END_NORMAL) },
            { assertSequence("\u001bOH", TerminalSequences.HOME_APPLICATION) },
            { assertSequence("\u001bOF", TerminalSequences.END_APPLICATION) },
            { assertSequence("\u001b[2~", TerminalSequences.INSERT) },
            { assertSequence("\u001b[3~", TerminalSequences.DELETE) },
            { assertSequence("\u001b[5~", TerminalSequences.PAGE_UP) },
            { assertSequence("\u001b[6~", TerminalSequences.PAGE_DOWN) },
        )
    }

    @Test
    fun `stores function key sequences`() {
        assertAll(
            { assertSequence("\u001bOP", TerminalSequences.F1) },
            { assertSequence("\u001bOQ", TerminalSequences.F2) },
            { assertSequence("\u001bOR", TerminalSequences.F3) },
            { assertSequence("\u001bOS", TerminalSequences.F4) },
            { assertSequence("\u001b[15~", TerminalSequences.F5) },
            { assertSequence("\u001b[17~", TerminalSequences.F6) },
            { assertSequence("\u001b[18~", TerminalSequences.F7) },
            { assertSequence("\u001b[19~", TerminalSequences.F8) },
            { assertSequence("\u001b[20~", TerminalSequences.F9) },
            { assertSequence("\u001b[21~", TerminalSequences.F10) },
            { assertSequence("\u001b[23~", TerminalSequences.F11) },
            { assertSequence("\u001b[24~", TerminalSequences.F12) },
        )
    }

    @Test
    fun `stores paste and focus sequences`() {
        assertAll(
            { assertSequence("\u001b[200~", TerminalSequences.BRACKETED_PASTE_START) },
            { assertSequence("\u001b[201~", TerminalSequences.BRACKETED_PASTE_END) },
            { assertSequence("\u001b[I", TerminalSequences.FOCUS_IN) },
            { assertSequence("\u001b[O", TerminalSequences.FOCUS_OUT) },
        )
    }

    private fun assertSequence(
        expected: String,
        actual: ByteArray,
    ) {
        val expectedBytes = ByteArray(expected.length)
        var i = 0
        while (i < expected.length) {
            expectedBytes[i] = expected[i].code.toByte()
            i++
        }
        assertArrayEquals(expectedBytes, actual)
    }
}
