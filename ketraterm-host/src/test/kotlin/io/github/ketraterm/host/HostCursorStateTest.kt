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
import io.github.ketraterm.core.model.CellAttributes
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class HostCursorStateTest {
    @ParameterizedTest
    @ValueSource(strings = ["2147483647", "999999999999999999999999999999"])
    fun `origin relative absolute positioning saturates before adding margins`(count: String) {
        val bytes = "\u001B[2;4r\u001B[?69h\u001B[3;8s\u001B[?6h\u001B[$count;${count}HX".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals('X'.code, terminal.getCodepointAt(7, 3), "split=$split")
            assertEquals(7, terminal.cursorCol)
            assertEquals(3, terminal.cursorRow)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "B,2147483647,false",
        "B,2147483647,true",
        "C,2147483647,false",
        "C,2147483647,true",
        "B,999999999999999999999999999999,false",
        "B,999999999999999999999999999999,true",
        "C,999999999999999999999999999999,false",
        "C,999999999999999999999999999999,true",
    )
    fun `maximum and saturated movement counts clamp before printing and recover normally`(
        command: String,
        count: String,
        margins: Boolean,
    ) {
        val setup = if (margins) "\u001B[2;4r\u001B[?69h\u001B[3;8s\u001B[?6h" else ""
        val bytes = "$setup\u001B[2;2H\u001B[$count${command}X".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            val bottom = if (margins) 3 else 4
            val right = if (margins) 7 else 9
            val row =
                if (command == "B") {
                    bottom
                } else if (margins) {
                    2
                } else {
                    1
                }
            val column =
                if (command == "C") {
                    right
                } else if (margins) {
                    3
                } else {
                    1
                }
            assertAll(
                "command=$command count=$count margins=$margins split=$split",
                { assertEquals(row, terminal.cursorRow) },
                { assertEquals(minOf(column + 1, right), terminal.cursorCol) },
                { assertEquals('X'.code, terminal.getCodepointAt(column, row)) },
                {
                    parser.accept("\u001B[?6l\u001B[?69l\u001B[HY".encodeToByteArray())
                    assertAll(
                        { assertEquals('Y'.code, terminal.getCodepointAt(0, 0)) },
                        { assertEquals('X'.code, terminal.getCodepointAt(column, row)) },
                        { assertEquals(0, terminal.cursorRow) },
                        { assertEquals(1, terminal.cursorCol) },
                    )
                },
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["DEC", "SCO", "1048", "1049"])
    fun `partial SGR after cursor restoration retains every other saved attribute`(form: String) {
        val saved =
            CellAttributes(
                foreground = CellColor.indexed(1),
                background = CellColor.indexed(4),
                underlineColor = CellColor.indexed(123),
                underlineStyle = UnderlineStyle.CURLY,
                strikethrough = true,
                overline = true,
            )
        val (save, restore) =
            when (form) {
                "DEC" -> "\u001B7" to "\u001B8"
                "SCO" -> "\u001B[s" to "\u001B[u"
                else -> "\u001B[?${form}h" to "\u001B[?${form}l"
            }
        val bytes =
            (
                "\u001B[31;44;4:3;58;5;123;9;53m" + save +
                    "\u001B[34;45;24;59;29;55m" + restore + "A\u001B[1mB"
            ).encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            assertAll(
                "form=$form split=$split",
                { assertEquals("AB", terminal.getLineAsString(0)) },
                { assertEquals(saved, terminal.getAttrAt(0, 0)) },
                { assertEquals(saved.copy(bold = true), terminal.getAttrAt(1, 0)) },
            )
        }
    }

    @Test
    fun `partial SGR after restoring an unsaved cursor preserves the default pen`() {
        val terminal = TerminalBuffers.create(10, 5)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001B[34m\u001B8\u001B[1mX".encodeToByteArray())

        assertEquals(CellAttributes(bold = true), terminal.getAttrAt(0, 0))
    }

    @ParameterizedTest
    @CsvSource("47,DEC", "47,SCO", "1047,DEC", "1047,SCO", "1049,DEC", "1049,SCO")
    fun `alternate screen cursor saves cannot replace the primary saved character set`(
        mode: Int,
        form: String,
    ) {
        val (save, restore) = if (form == "DEC") "\u001B7" to "\u001B8" else "\u001B[s" to "\u001B[u"
        val bytes =
            ("\u001B(0" + save + "\u001B[?${mode}h\u001B(B" + save + "\u001B[?${mode}l" + restore + "q")
                .encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 5)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)

            assertAll(
                "mode=$mode save=$save split=$split",
                { assertEquals(0x2500, terminal.getCodepointAt(0, 0)) },
                { assertEquals("\u2500", terminal.getLineAsString(0)) },
                { assertEquals(0, terminal.cursorRow) },
                { assertEquals(1, terminal.cursorCol) },
            )
        }
    }
}
