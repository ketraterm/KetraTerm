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

import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.fixture.TerminalParserFixture
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.BACKGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.CURSOR
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.CURSOR_TEXT
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.DYNAMIC
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.FOREGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.QUERY
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.RESET
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.SELECTION_BACKGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.SELECTION_FOREGROUND
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.SET
import io.github.ketraterm.protocol.TerminalColorRequest.Companion.UNKNOWN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OscKittyColorTest {
    @Test
    fun `ordered sets queries resets and dynamic roles survive every split and both terminators`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            val bytes =
                (
                    "\u001B]21;foreground=#3a7;0=rgb:3/a/7;0255=red@.5;foreground=?;background;selection_foreground=;" +
                        "selection_background=blue;cursor=;cursor_text=;0;255=?;foreground=?$terminator\u001B]2;ok\u0007"
                ).encodeToByteArray()
            assertEverySplit(
                bytes,
                listOf(
                    color(FOREGROUND, SET, "foreground", 0xff30a070),
                    color(0, SET, "0", 0xff33aa77),
                    color(255, SET, "0255", 0xffff0000),
                    color(FOREGROUND, QUERY, "foreground"),
                    color(BACKGROUND, RESET, "background"),
                    color(SELECTION_FOREGROUND, DYNAMIC, "selection_foreground"),
                    color(SELECTION_BACKGROUND, SET, "selection_background", 0xff0000ff),
                    color(CURSOR, DYNAMIC, "cursor"),
                    color(CURSOR_TEXT, DYNAMIC, "cursor_text"),
                    color(0, RESET, "0"),
                    color(255, QUERY, "255"),
                    color(FOREGROUND, QUERY, "foreground"),
                    "setWindowTitle:ok",
                ),
            )
        }
    }

    @Test
    fun `unknown keys retain their original bytes and operation even with malformed values`() {
        assertEverySplit(
            "\u001B]21;future=?;Future;é=;256=not-a-color;999999999999999999999999999999=red;foreground=red; future=bad\u0007"
                .encodeToByteArray(),
            listOf(
                color(UNKNOWN, QUERY, "future"),
                color(UNKNOWN, RESET, "Future"),
                color(UNKNOWN, DYNAMIC, "é"),
                color(UNKNOWN, SET, "256"),
                color(UNKNOWN, SET, "999999999999999999999999999999"),
                color(FOREGROUND, SET, "foreground", 0xffff0000),
                color(UNKNOWN, SET, " future"),
            ),
        )
    }

    @Test
    fun `invalid known values skip only their field and empty fields have no effects`() {
        assertEverySplit(
            (
                "\u001B]21;;foreground=#1234;=red;foreground=red;0=rgb:1/2/3/4;1=rgbi:NaN/0/0;" +
                    "2=red@invalid;3=unknown-color;cursor_text=?;background=blue;;\u001B\\\u001B]21;foreground=?\u0007"
            ).encodeToByteArray(),
            listOf(
                color(FOREGROUND, SET, "foreground", 0xffff0000),
                color(CURSOR_TEXT, QUERY, "cursor_text"),
                color(BACKGROUND, SET, "background", 0xff0000ff),
                color(FOREGROUND, QUERY, "foreground"),
            ),
        )
    }

    @Test
    fun `malformed UTF8 rejects the entire batch before any preceding valid mutation`() {
        for (invalid in listOf(
            byteArrayOf(0xc3.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        )) {
            for (terminator in listOf("\u0007", "\u001B\\")) {
                val bytes =
                    "\u001B]21;foreground=red;future=".encodeToByteArray() + invalid +
                        "$terminator\u001B]21;foreground=?\u0007".encodeToByteArray()
                assertEverySplit(bytes, listOf(color(FOREGROUND, QUERY, "foreground")))
            }
        }
    }

    @Test
    fun `cancellation and incomplete input discard all collected fields and recover`() {
        for (cancel in listOf("\u0018", "\u001A")) {
            assertEverySplit(
                "\u001B]21;foreground=red;0=blue$cancel\u001B]21;cursor=?\u0007".encodeToByteArray(),
                listOf(color(CURSOR, QUERY, "cursor")),
            )
        }
        for (suffix in listOf("", "\u001B")) {
            val fixture = TerminalParserFixture()
            fixture.acceptAscii("\u001B]21;foreground=red;0=blue$suffix")
            fixture.endOfInput()
            assertEquals(emptyList<String>(), fixture.sink.events)
            fixture.acceptAscii("\u001B]21;cursor=?\u0007")
            assertEquals(listOf(color(CURSOR, QUERY, "cursor")), fixture.sink.events)
        }
    }

    @Test
    fun `4096 byte ceiling rejects an oversized batch atomically and preserves following input`() {
        for (size in intArrayOf(4095, 4096, 4097)) {
            val payload = "21;foreground=red;".padEnd(size, ';')
            for (terminator in listOf("\u0007", "\u001B\\")) {
                val bytes = "\u001B]$payload$terminator\u001B]21;foreground=?\u0007".encodeToByteArray()
                for (split in intArrayOf(0, 1, 4, 4095, 4096, 4097, bytes.size - 1, bytes.size)) {
                    val fixture = TerminalParserFixture()
                    fixture.parser.accept(bytes, 0, split)
                    fixture.parser.accept(bytes, split, bytes.size - split)
                    val expected =
                        if (size <= 4096) {
                            listOf(color(FOREGROUND, SET, "foreground", 0xffff0000), color(FOREGROUND, QUERY, "foreground"))
                        } else {
                            listOf(color(FOREGROUND, QUERY, "foreground"))
                        }
                    assertEquals(expected, fixture.sink.events, "size=$size split=$split terminator=$terminator")
                }
            }
        }
    }

    @Test
    fun `maximum field density fits bounded request storage without losing duplicates`() {
        val fixture = TerminalParserFixture()
        fixture.acceptAscii("\u001B]21;${"0;".repeat(2046)}0\u0007")
        assertEquals(List(2047) { color(0, RESET, "0") }, fixture.sink.events)
    }

    @Test
    fun `built in OSC21 is never delivered to a custom OSC handler`() {
        val fixture = TerminalParserFixture()
        val parser =
            TerminalParsers.create(fixture.sink, { 0 }) { command, _, _, _ ->
                fixture.sink.events += "custom:$command"
            }
        parser.accept(
            "\u001B]21\u0007\u001B]21;\u0007\u001B]21;;;\u0007\u001B]21;foreground=red\u0007\u001B]1341;ok\u0007".encodeToByteArray(),
        )
        assertEquals(listOf(color(FOREGROUND, SET, "foreground", 0xffff0000), "custom:1341"), fixture.sink.events)
    }

    private fun assertEverySplit(
        bytes: ByteArray,
        expected: List<String>,
    ) {
        for (split in 0..bytes.size) {
            val fixture = TerminalParserFixture()
            fixture.parser.accept(bytes, 0, split)
            fixture.parser.accept(bytes, split, bytes.size - split)
            assertEquals(expected, fixture.sink.events, "split=$split")
        }
    }

    private fun color(
        target: Int,
        operation: Int,
        key: String,
        argb: Long = 0,
    ): String = "color:$target:$operation:${argb.toInt()}:$key"
}
