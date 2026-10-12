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
package io.github.ketraterm.parser

import io.github.ketraterm.parser.fixture.TerminalParserFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OscColorResetTest {
    @Test
    fun `color reset commands survive every split and both string terminators`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            val bytes =
                (
                    "\u001B]104;0;255;0$terminator\u001B]104$terminator" +
                        "\u001B]104;$terminator\u001B]110$terminator\u001B]111;$terminator" +
                        "\u001B]112$terminator\u001B]2;recovered$terminator"
                ).encodeToByteArray()
            for (split in 0..bytes.size) {
                val f = TerminalParserFixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                assertEquals(
                    listOf(
                        "resetPaletteColors:0,255",
                        "resetPaletteColors:",
                        "resetPaletteColors:",
                        "resetDynamicColor:10",
                        "resetDynamicColor:11",
                        "resetDynamicColor:12",
                        "setWindowTitle:recovered",
                    ),
                    f.sink.events,
                    "split $split terminator $terminator",
                )
            }
        }
    }

    @Test
    fun `canceled and incomplete resets dispatch nothing and permit following controls`() {
        for (cancel in listOf("\u0018", "\u001A")) {
            for (payload in listOf("104", "104;0;255", "110;", "111", "112")) {
                val f = TerminalParserFixture()
                f.acceptAscii("\u001B]$payload$cancel\u001B]2;recovered\u0007")
                assertEquals(listOf("setWindowTitle:recovered"), f.sink.events)
            }
        }
        val f = TerminalParserFixture()
        f.acceptAscii("\u001B]104;1")
        f.endOfInput()
        assertTrue(f.sink.events.isEmpty())
    }

    @Test
    fun `non ASCII indices reject the complete reset list before any dispatch`() {
        for (tail in listOf(byteArrayOf(0xc3.toByte()), "é".encodeToByteArray())) {
            val bytes = "\u001B]104;0;".encodeToByteArray() + tail + "\u0007\u001B]2;ok\u0007".encodeToByteArray()
            for (split in 0..bytes.size) {
                val f = TerminalParserFixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                assertEquals(listOf("setWindowTitle:ok"), f.sink.events)
            }
        }
    }

    @Test
    fun `reset payload ceiling rejects an oversized list atomically and recovers`() {
        for (size in intArrayOf(4095, 4096, 4097)) {
            val payload = "104;1;".padEnd(size, ';')
            val bytes = "\u001B]$payload\u001B\\\u001B]110\u0007".encodeToByteArray()
            for (split in listOf(0, 1, 4, 4095, 4096, bytes.size - 1, bytes.size)) {
                val f = TerminalParserFixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                assertEquals(
                    if (size <= 4096) listOf("resetPaletteColors:1", "resetDynamicColor:10") else listOf("resetDynamicColor:10"),
                    f.sink.events,
                    "size $size split $split",
                )
            }
        }
    }
}
