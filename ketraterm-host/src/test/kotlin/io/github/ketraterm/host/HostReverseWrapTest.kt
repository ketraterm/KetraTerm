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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class HostReverseWrapTest {
    @ParameterizedTest
    @ValueSource(strings = ["\u0008", "\u001B[D", "\u001B[0D"])
    fun `BS and default or zero CUB cross a soft wrap identically`(motion: String) {
        val bytes = ("ABCDX\u001B[?45h" + motion + motion + "Y").encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals("ABCY", terminal.getLineAsString(0))
            assertEquals("X", terminal.getLineAsString(1))
            assertEquals(0, terminal.cursorRow)
            assertEquals(3, terminal.cursorCol)
        }
    }

    @ParameterizedTest
    @CsvSource("45,0,1", "1045,3,0", "'45;1045',3,0")
    fun `extended mode crosses hard breaks and wins when both flags are set`(
        modes: String,
        column: Int,
        row: Int,
    ) {
        val bytes = ("AB\r\n\u001B[?$modes" + "h\u001B[D").encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(column, terminal.cursorCol)
            assertEquals(row, terminal.cursorRow)
            assertEquals("AB", terminal.getLineAsString(0))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [45, 1045])
    fun `enabling after filling a row preserves pending wrap for BS`(mode: Int) {
        val bytes = ("ABCD\u001B[?$mode" + "h\u0008X").encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals("ABCX", terminal.getLineAsString(0))
            assertEquals("", terminal.getLineAsString(1))
        }
    }

    @Test
    fun `rectangular margins and origin mode constrain extended cycles`() {
        val bytes =
            ("\u001B[?69h\u001B[3;6s\u001B[2;5r\u001B[?6;1045h\u001B[2147483647D").encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(8, 6)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(3, terminal.cursorCol)
            assertEquals(1, terminal.cursorRow)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [45, 1045])
    fun `autowrap gates motion without clearing the selected reverse mode`(mode: Int) {
        val terminal = TerminalBuffers.create(4, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept(("ABCDX\u001B[?$mode" + "h\u001B[?7l\u001B[2D").encodeToByteArray())
        assertEquals(0, terminal.cursorCol)
        assertEquals(1, terminal.cursorRow)
        parser.accept("\u001B[?7h\u001B[2D".encodeToByteArray())
        assertEquals(2, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u001B[!p", "\u001Bc"])
    fun `resets disable both modes and queries report independent stored flags`(reset: String) {
        val bytes =
            (
                "\u001B[?45;1045h\u001B[?7l\u001B[?45\$p\u001B[?1045\$p" +
                    "\u001B[?1045l\u001B[?45\$p\u001B[?1045\$p" +
                    reset + "\u001B[?45\$p\u001B[?1045\$p"
            ).encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(
                "\u001B[?45;1\$y\u001B[?1045;1\$y" +
                    "\u001B[?45;1\$y\u001B[?1045;2\$y" +
                    "\u001B[?45;2\$y\u001B[?1045;2\$y",
                drain(terminal),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [45, 1045])
    fun `mode survives cursor restore resize and alternate screen transitions`(mode: Int) {
        val terminal = TerminalBuffers.create(4, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept(("\u001B7\u001B[?$mode" + "h\u001B8\u001B[?47h").encodeToByteArray())
        terminal.resize(6, 4)
        parser.accept(("\u001B[?$mode" + "\$p\u001B[?47l\u001B[?$mode" + "\$p").encodeToByteArray())
        assertEquals("\u001B[?$mode;1\$y\u001B[?$mode;1\$y", drain(terminal))
        parser.accept("ABCDEFG\u001B[2D".encodeToByteArray())
        assertEquals(5, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
    }

    @Test
    fun `denied replies do not inhibit reverse wrap and ANSI mode namespace stays unsupported`() {
        for (policy in HostControlPolicy.entries) {
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal, hostPolicy = HostPolicy(terminalResponsePolicy = policy)))
            parser.accept("ABCDX\u001B[?45h\u001B[2D\u001B[?45\$p\u001B[45\$p".encodeToByteArray())
            assertEquals(3, terminal.cursorCol)
            assertEquals(0, terminal.cursorRow)
            assertEquals(if (policy == HostControlPolicy.ALLOW) "\u001B[?45;1\$y\u001B[45;0\$y" else "", drain(terminal))
        }
    }

    @Test
    fun `malformed mode sequences recover without enabling reverse wrap`() {
        val bytes = "ABCDX\u001B[?45:1h\u001B[?999999999999h\u001B[2DY".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals("ABCD", terminal.getLineAsString(0))
            assertEquals("Y", terminal.getLineAsString(1))
        }
    }

    private fun drain(terminal: TerminalBuffer): String {
        val bytes = ByteArray(terminal.pendingResponseBytes)
        terminal.readResponseBytes(bytes)
        return bytes.decodeToString()
    }

    @Test
    fun `ordinary reverse wrap uses reflowed wrap provenance`() {
        val terminal = TerminalBuffers.create(4, 3)
        terminal.writeText("ABCDE")
        terminal.setReverseWrap(true)
        terminal.resize(2, 3)
        terminal.cursorLeft(2)
        assertEquals(1, terminal.cursorCol)
        assertEquals(1, terminal.cursorRow)
        assertEquals("AB", terminal.getLineAsString(0))
        assertEquals("CD", terminal.getLineAsString(1))
        assertEquals("E", terminal.getLineAsString(2))
    }

    @Test
    fun `alternate reverse wrap uses its own rows and preserves primary history`() {
        val terminal = TerminalBuffers.create(4, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("ABCDEFGHIJKLMNOP\u001B[?45h".encodeToByteArray())
        val history = terminal.historySize
        parser.accept("\u001B[?1049hABCDE\u001B[2D".encodeToByteArray())
        assertEquals(3, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
        assertEquals("ABCD", terminal.getLineAsString(0))
        assertEquals(0, terminal.historySize)
        parser.accept("\u001B[?1049l\u001B[2147483647D".encodeToByteArray())
        assertEquals(0, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
        assertEquals(history, terminal.historySize)
        assertEquals("EFGH", terminal.getLineAsString(0))
    }
}
