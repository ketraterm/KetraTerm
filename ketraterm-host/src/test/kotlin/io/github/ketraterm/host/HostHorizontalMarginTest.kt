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

class HostHorizontalMarginTest {
    @ParameterizedTest
    @ValueSource(strings = ["H", "f"])
    fun `CUP and HVP address the whole page with origin reset and margins with origin set`(command: String) {
        for (origin in listOf(false, true)) {
            for ((parameters, column, row) in listOf(
                Triple("", if (origin) 2 else 0, if (origin) 2 else 0),
                Triple("0;0", if (origin) 2 else 0, if (origin) 2 else 0),
                Triple("2;2", if (origin) 3 else 1, if (origin) 3 else 1),
                Triple("2147483648;2147483648", if (origin) 7 else 9, if (origin) 6 else 7),
            )) {
                val mode = if (origin) 'h' else 'l'
                assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[?6$mode\u001B[$parameters$command") { terminal ->
                    assertEquals(column to row, terminal.cursorCol to terminal.cursorRow)
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("G,true", "d,false")
    fun `CHA and VPA preserve the untouched axis without applying its origin twice`(
        command: Char,
        horizontal: Boolean,
    ) {
        for (origin in listOf(false, true)) {
            for (parameter in listOf("", "0", "2", "2147483648")) {
                val mode = if (origin) 'h' else 'l'
                val start = if (origin) 5 else 3
                val target =
                    when (parameter) {
                        "2" -> if (origin) 3 else 1
                        "2147483648" -> if (horizontal) (if (origin) 7 else 9) else (if (origin) 6 else 7)
                        else -> if (origin) 2 else 0
                    }
                assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[?6$mode\u001B[4;4H\u001B[$parameter$command") { terminal ->
                    assertEquals(if (horizontal) target to start else start to target, terminal.cursorCol to terminal.cursorRow)
                }
            }
        }
    }

    @Test
    fun `CHA and VPA cancel pending wrap even when the addressed coordinate is unchanged`() {
        for (origin in listOf(false, true)) {
            val mode = if (origin) 'h' else 'l'
            val position = if (origin) "1;6" else "3;10"
            for (command in if (origin) listOf("6G", "1d") else listOf("10G", "3d")) {
                assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[?6$mode\u001B[${position}HA\u001B[${command}BC") { terminal ->
                    assertEquals('B'.code, terminal.getCodepointAt(if (origin) 7 else 9, 2))
                    assertEquals('C'.code, terminal.getCodepointAt(2, 3))
                    assertEquals(3 to 3, terminal.cursorCol to terminal.cursorRow)
                }
            }
        }
    }

    @Test
    fun `margin and origin transitions home according to the resulting origin`() {
        val setup = "\u001B[?69h\u001B[3;8s\u001B[3;7r"
        for ((suffix, column, row) in listOf(
            Triple("", 0, 0),
            Triple("\u001B[?6h", 2, 2),
            Triple("\u001B[?6h\u001B[4;9s", 3, 2),
            Triple("\u001B[?6h\u001B[4;6r", 2, 3),
            Triple("\u001B[?6h\u001B[?6l", 0, 0),
            Triple("\u001B[?6h\u001B[?69l", 0, 2),
            Triple("\u001B[?6h\u001B[?69l\u001B[?69h", 0, 2),
        )) {
            assertStream(setup + suffix) { terminal ->
                assertEquals(column to row, terminal.cursorCol to terminal.cursorRow)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "'1;10','2147483647C',9,0",
        "'1;1','2147483647D',0,0",
        "'1;5','2147483647B',4,6",
        "'8;5','2147483647A',4,2",
        "'5;5','2147483647C',7,4",
        "'5;5','2147483647D',2,4",
        "'4;10','E',2,4",
        "'4;10','F',2,2",
    )
    fun `relative motion approaches margins without reversing direction outside them`(
        position: String,
        movement: String,
        column: Int,
        row: Int,
    ) {
        assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[${position}H\u001B[$movement") { terminal ->
            assertEquals(column to row, terminal.cursorCol to terminal.cursorRow)
            assertEquals(0, terminal.historySize)
        }
    }

    @Test
    fun `outside narrow wide and clustered writes wrap from the page edge to the left margin`() {
        assertStream("\u001B[?69h\u001B[3;8sLR\u001B[1;9H👩‍💻\u001B7\u001B[?6h\u001B8X") { terminal ->
            assertEquals("LR      👩‍💻", terminal.getLineAsString(0))
            assertEquals(-1, terminal.getCodepointAt(9, 0), "wide spacer")
            assertEquals('X'.code, terminal.getCodepointAt(2, 1))
            assertEquals(3 to 1, terminal.cursorCol to terminal.cursorRow)
        }
        assertStream("\u001B[?69h\u001B[3;8s\u001B[1;9H❤️X") { terminal ->
            assertEquals("        ❤️", terminal.getLineAsString(0))
            assertEquals(-1, terminal.getCodepointAt(9, 0), "wide spacer")
            assertEquals('X'.code, terminal.getCodepointAt(2, 1))
        }
    }

    @Test
    fun `outside writes respect disabled autowrap and insert mode`() {
        assertStream("0123456789\u001B[?69h\u001B[3;8s\u001B[4hA\u001B[1;10HB") { terminal ->
            assertEquals("A12345678B", terminal.getLineAsString(0))
        }
        assertStream("\u001B[?69h\u001B[3;8s\u001B[?7l\u001B[1;9H界界XY") { terminal ->
            assertEquals("         Y", terminal.getLineAsString(0))
            assertEquals(9 to 0, terminal.cursorCol to terminal.cursorRow)
            assertEquals(0, terminal.getCodepointAt(8, 0))
        }
    }

    @Test
    fun `outside right edge wrap leaves the scrolling region intact`() {
        for (text in listOf("AX", "界")) {
            assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[7;3HR\u001B[7;10H$text") { terminal ->
                assertEquals('R'.code, terminal.getCodepointAt(2, 6))
                assertEquals(if (text == "界") '界'.code else 'X'.code, terminal.getCodepointAt(2, 7))
                assertEquals(0, terminal.historySize)
            }
        }
    }

    @Test
    fun `index and reverse index outside horizontal margins preserve region contents`() {
        for (column in listOf(1, 10)) {
            assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[7;3HR\u001B[7;${column}H\u001BD") { terminal ->
                assertEquals(column - 1 to 7, terminal.cursorCol to terminal.cursorRow)
                assertEquals('R'.code, terminal.getCodepointAt(2, 6))
                assertEquals(0, terminal.historySize)
            }
            assertStream("\u001B[?69h\u001B[3;8s\u001B[3;7r\u001B[3;3HR\u001B[3;${column}H\u001BM") { terminal ->
                assertEquals(column - 1 to 1, terminal.cursorCol to terminal.cursorRow)
                assertEquals('R'.code, terminal.getCodepointAt(2, 2))
                assertEquals(0, terminal.historySize)
            }
        }
    }

    private fun assertStream(
        stream: String,
        assertion: (TerminalBuffer) -> Unit,
    ) {
        val bytes = stream.encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 8, maxHistory = 20)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            parser.endOfInput()
            assertion(terminal)
        }
    }
}
