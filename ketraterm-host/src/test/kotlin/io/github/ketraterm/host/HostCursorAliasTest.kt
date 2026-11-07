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
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HostCursorAliasTest {
    @ParameterizedTest
    @CsvSource("a,6,3", "e,4,5", "j,2,3", "k,4,1")
    fun `relative aliases position subsequent text across every byte split`(
        finalByte: Char,
        column: Int,
        row: Int,
    ) {
        val bytes = "\u001B[4;5H\u001B[2${finalByte}X".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 8)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals('X'.code, terminal.getCodepointAt(column, row))
            assertEquals(column + 1, terminal.cursorCol)
            assertEquals(row, terminal.cursorRow)
        }
    }

    @ParameterizedTest
    @CsvSource("a,5,3", "e,4,4", "j,3,3", "k,4,2")
    fun `omitted and zero counts move exactly one cell`(
        finalByte: Char,
        column: Int,
        row: Int,
    ) {
        for (parameter in listOf("", "0")) {
            val bytes = "\u001B[4;5H\u001B[$parameter$finalByte".encodeToByteArray()
            assertAllByteSplits(bytes) { split ->
                val terminal = TerminalBuffers.create(10, 8)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
                assertEquals(column, terminal.cursorCol)
                assertEquals(row, terminal.cursorRow)
            }
        }
    }

    @ParameterizedTest
    @CsvSource("a,9,3", "e,4,7", "j,0,3", "k,4,0")
    fun `large and overflowing counts clamp to the screen without scrolling`(
        finalByte: Char,
        column: Int,
        row: Int,
    ) {
        for (parameter in listOf("2147483647", "2147483648", "9".repeat(80))) {
            val bytes = "\u001B[4;5H\u001B[$parameter$finalByte".encodeToByteArray()
            assertAllByteSplits(bytes) { split ->
                val terminal = TerminalBuffers.create(10, 8)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
                assertEquals(column, terminal.cursorCol)
                assertEquals(row, terminal.cursorRow)
                assertEquals(0, terminal.historySize)
            }
        }
    }

    @ParameterizedTest
    @CsvSource("a,7,3", "e,4,6", "j,2,3", "k,4,1")
    fun `origin mode and rectangular margins bound alias movement`(
        finalByte: Char,
        column: Int,
        row: Int,
    ) {
        val bytes =
            ("\u001B[?69h\u001B[3;8s\u001B[2;7r\u001B[?6h\u001B[3;3H\u001B[2147483647$finalByte")
                .encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(10, 8)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(column, terminal.cursorCol)
            assertEquals(row, terminal.cursorRow)
            assertEquals(0, terminal.historySize)
        }
    }

    @ParameterizedTest
    @CsvSource("a,3,0,ABCX", "e,3,1,ABCD", "j,2,0,ABXD", "k,3,0,ABCX")
    fun `aliases resolve pending wrap before the next printable character`(
        finalByte: Char,
        column: Int,
        row: Int,
        firstLine: String,
    ) {
        val bytes = "ABCD\u001B[${finalByte}X".encodeToByteArray()
        assertAllByteSplits(bytes) { split ->
            val terminal = TerminalBuffers.create(4, 3)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertEquals(firstLine, terminal.getLineAsString(0))
            assertEquals('X'.code, terminal.getCodepointAt(column, row))
            assertEquals(minOf(column + 1, 3), terminal.cursorCol)
            assertEquals(row, terminal.cursorRow)
        }
    }
}
