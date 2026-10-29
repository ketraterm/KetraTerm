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
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostXtgettcapTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 255])
    fun `Su supports indexed underline colors without changing the active pen or cursor`(index: Int) {
        val f = Fixture()
        f.write("\u001B[1;3;4:2;38;5;2m\u001B[3;4H")
        assertNull(f.queryString("Su"))
        f.write("\u001B[58:5:" + index + "mX")
        f.parser.endOfInput()
        val attr = assertNotNull(f.buffer.getAttrAt(3, 2))
        assertTrue(attr.bold)
        assertTrue(attr.italic)
        assertEquals(UnderlineStyle.DOUBLE, attr.underlineStyle)
        assertEquals(CellColor.indexed(2), attr.foreground)
        assertEquals(CellColor.indexed(index), attr.underlineColor)
        assertEquals(4, f.buffer.cursorCol)
        assertEquals(2, f.buffer.cursorRow)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mixed batches survive every split and obey only response permission`(denied: Boolean) {
        val wire = "\u001BP+q696e646e;7878;5053;5375;536D756C78;5045\u001B\\X".encodeToByteArray()
        val expected =
            if (denied) {
                ""
            } else {
                "\u001BP1+r696e646e=1b5b257031256453\u001B\\" +
                    "\u001BP0+r7878\u001B\\" +
                    "\u001BP1+r5053=1b5b3230307e\u001B\\" +
                    "\u001BP1+r5375\u001B\\" +
                    "\u001BP1+r536D756C78=1b5b343a25703125646d\u001B\\" +
                    "\u001BP1+r5045=1b5b3230317e\u001B\\"
            }
        for (split in -1..wire.size) {
            val f =
                Fixture(
                    HostPolicy(
                        terminalResponsePolicy = if (denied) HostControlPolicy.DENY else HostControlPolicy.ALLOW,
                        windowManipulationPolicy = HostControlPolicy.DENY,
                        palettePolicy = HostControlPolicy.DENY,
                    ),
                )
            if (split < 0) {
                wire.forEach { f.parser.acceptByte(it.toInt() and 255) }
            } else {
                f.parser.accept(wire, 0, split)
                f.parser.accept(wire, split, wire.size - split)
            }
            f.parser.endOfInput()
            assertEquals(expected, f.drain(), "split=" + split)
            assertEquals("X", f.buffer.getLineAsString(0), "split=" + split)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `malformed requests fail without partial results and recover`(denied: Boolean) {
        val policy = HostPolicy(terminalResponsePolicy = if (denied) HostControlPolicy.DENY else HostControlPolicy.ALLOW)
        for (bad in listOf("", "436f;0g", "436f;", "436f;;5053", "４３６ｆ")) {
            val f = Fixture(policy)
            f.write("\u001BP+q" + bad + "\u001B\\\u001BP+q436f\u001B\\X")
            f.parser.endOfInput()
            val expected = if (denied) "" else "\u001BP0+r\u001B\\\u001BP1+r436f=323536\u001B\\"
            assertEquals(expected, f.drain(), bad)
            assertEquals("X", f.buffer.getLineAsString(0), bad)
        }
    }

    @Test
    fun `wire limit includes the family prefix and oversized requests never reply partially`() {
        for (size in listOf(4095, 4096, 4097)) {
            val rawLength = size - 2
            val body = if (rawLength % 2 == 0) "00".repeat(rawLength / 2) else "00;" + "00".repeat((rawLength - 3) / 2)
            val wire = ("\u001BP+q" + body + "\u001B\\\u001BP+q436f\u001B\\X").encodeToByteArray()
            val firstReply = if (size > 4096) "" else body.split(';').joinToString("") { "\u001BP0+r" + it + "\u001B\\" }
            for (split in listOf(0, 1, 2, 3, 4, 4095, wire.size - 1)) {
                val f = Fixture()
                f.parser.accept(wire, 0, split)
                f.parser.accept(wire, split, wire.size - split)
                f.parser.endOfInput()
                assertEquals(firstReply + "\u001BP1+r436f=323536\u001B\\", f.drain(), "size=" + size + " split=" + split)
                assertEquals("X", f.buffer.getLineAsString(0))
            }
        }
    }

    @Test
    fun `cancellation discards capability requests and permits the next query`() {
        for (control in listOf('\u0018', '\u001A')) {
            val f = Fixture()
            f.write("\u001BP+q436f" + control + "\u001BP+q5375\u001B\\X")
            f.parser.endOfInput()
            assertEquals("\u001BP1+r5375\u001B\\", f.drain())
            assertEquals("X", f.buffer.getLineAsString(0))
        }
    }

    @Test
    fun `ncurses expanded underline templates produce each advertised style`() {
        val styles =
            listOf(
                UnderlineStyle.NONE,
                UnderlineStyle.SINGLE,
                UnderlineStyle.DOUBLE,
                UnderlineStyle.CURLY,
                UnderlineStyle.DOTTED,
                UnderlineStyle.DASHED,
            )
        for (case in expansions("Smulx")) {
            val f = Fixture()
            assertEquals(case.template, f.queryString("Smulx"))
            f.write("\u001B[4:5m" + case.sequence + "X")
            f.parser.endOfInput()
            assertEquals(styles[case.parameter], f.buffer.getAttrAt(0, 0)?.underlineStyle, "parameter=" + case.parameter)
        }
    }

    @Test
    fun `ncurses packed underline colors retain all component boundaries`() {
        for (case in expansions("Setulc")) {
            val f = Fixture()
            assertEquals(case.template, f.queryString("Setulc"))
            f.write("\u001B[4:3m" + case.sequence + "X\u001B[59mY")
            f.parser.endOfInput()
            assertEquals(
                CellColor.rgb(case.parameter ushr 16, (case.parameter ushr 8) and 255, case.parameter and 255),
                f.buffer.getAttrAt(0, 0)?.underlineColor,
            )
            assertEquals(UnderlineStyle.CURLY, f.buffer.getAttrAt(0, 0)?.underlineStyle)
            assertEquals(CellColor.DEFAULT, f.buffer.getAttrAt(1, 0)?.underlineColor)
        }
    }

    @Test
    fun `ncurses scroll templates respect margins and preserve cursor position`() {
        for (case in expansions("indn")) {
            val f = Fixture()
            assertEquals(case.template, f.queryString("indn"))
            f.write("\u001B[1;1HA\u001B[2;1HB\u001B[3;1HC\u001B[4;1HD\u001B[5;1HE\u001B[2;4r\u001B[3;2H" + case.sequence)
            f.parser.endOfInput()
            assertEquals("A", f.buffer.getLineAsString(0))
            assertEquals("E", f.buffer.getLineAsString(4))
            val region = listOf("B", "C", "D").drop(case.parameter.coerceAtMost(3)) + List(case.parameter.coerceAtMost(3)) { "" }
            assertEquals(region, (1..3).map { f.buffer.getLineAsString(it) })
            assertEquals(1, f.buffer.cursorCol)
            assertEquals(2, f.buffer.cursorRow)
        }
    }

    @Test
    fun `all repeated longest values at the wire limit fit the reply budget`() {
        val f = Fixture()
        val template = assertNotNull(f.queryString("Setulc"))
        val key = "536574756c63"
        val count = (4094 + 1) / (key.length + 1)
        val body = List(count) { key }.joinToString(";")
        f.write("\u001BP+q" + body + "\u001B\\")
        val frame = "\u001BP1+r" + key + "=" + hex(template) + "\u001B\\"
        assertTrue(frame.length * count < 65536)
        assertEquals(frame.repeat(count), f.drain())
    }

    private data class Expansion(
        val parameter: Int,
        val template: String,
        val sequence: String,
    )

    private fun expansions(name: String): List<Expansion> =
        checkNotNull(javaClass.getResource("/xtgettcap/parameters.tsv"))
            .readText()
            .lineSequence()
            .filter { it.startsWith(name + "\t") }
            .map { line ->
                val fields = line.split('\t')
                Expansion(fields[2].toInt(), unhex(fields[1]), unhex(fields[3]))
            }.toList()
            .also { assertTrue(it.isNotEmpty()) }

    private class Fixture(
        policy: HostPolicy = HostPolicy(),
    ) {
        val buffer = TerminalBuffers.create(10, 5)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer, hostPolicy = policy))

        fun write(text: String) = parser.accept(text.encodeToByteArray())

        fun queryString(name: String): String? {
            val key = hex(name)
            write("\u001BP+q" + key + "\u001B\\")
            val response = drain()
            val prefix = "\u001BP1+r" + key
            assertTrue(response.startsWith(prefix), name)
            assertTrue(response.endsWith("\u001B\\"), name)
            val value = response.removePrefix(prefix).removeSuffix("\u001B\\")
            if (value.isEmpty()) return null
            assertTrue(value.startsWith("="))
            return unhex(value.substring(1))
        }

        fun drain(): String {
            val output = ByteArrayOutputStream()
            val scratch = ByteArray(7) // Deliberately split prefixes, hex pairs and terminators.
            while (buffer.pendingResponseBytes != 0) {
                val count = buffer.readResponseBytes(scratch)
                assertTrue(count > 0)
                output.write(scratch, 0, count)
            }
            return output.toString(StandardCharsets.US_ASCII)
        }
    }

    private companion object {
        fun hex(value: String): String = HexFormat.of().formatHex(value.toByteArray(StandardCharsets.US_ASCII))

        fun unhex(value: String): String = HexFormat.of().parseHex(value).toString(StandardCharsets.US_ASCII)
    }
}
