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
package io.github.ketraterm.app.config

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TomlParserTest {
    @TestFactory
    fun `basic strings decode TOML escapes before publication`(): List<DynamicTest> {
        val cases =
            listOf(
                "empty" to ("" to ""),
                "whitespace" to (" \t value  " to " \t value  "),
                "compact escapes" to ("\\b\\t\\n\\f\\r\\\"\\\\" to "\b\t\n\u000C\r\"\\"),
                "Windows path" to ("C:\\\\tools\\\\pwsh.exe" to "C:\\tools\\pwsh.exe"),
                "escaped quote before hash" to ("say \\\"#hello\\\" = true" to "say \"#hello\" = true"),
                "Unicode scalars" to
                    ("\\u0000\\uD7fF\\uE000\\U0001F600\\U0010FFFF" to "\u0000\uD7FF\uE000😀" + String(Character.toChars(0x10FFFF))),
                "escapes decode once" to ("\\\\u0041,\\u0041,\\u0022#\\u005C" to "\\u0041,A,\"#\\"),
            )
        return listOf(1, 3).flatMap { width ->
            val delimiter = "\"".repeat(width)
            cases.map { (name, case) ->
                val (encoded, expected) = case
                DynamicTest.dynamicTest("$width quote basic string: $name") {
                    assertEquals(expected, parseValue("$delimiter$encoded$delimiter \t# ignored \"'\\"))
                }
            }
        }
    }

    @TestFactory
    fun `literal strings preserve escapes and hash characters`(): List<DynamicTest> =
        listOf(1, 3).flatMap { width ->
            val delimiter = "'".repeat(width)
            listOf("", "  \tspace  ", "C:\\tools\\pwsh.exe", "\\uD800\\x\\n\\\"#value", "工程😀\"").mapIndexed { index, value ->
                DynamicTest.dynamicTest("$width quote literal string: $index") {
                    assertEquals(value, parseValue("$delimiter$value$delimiter # ignored ' \""))
                }
            }
        }

    @TestFactory
    fun `backslash parity determines whether a basic quote closes the string`(): List<DynamicTest> =
        listOf(1, 3).flatMap { width ->
            val delimiter = "\"".repeat(width)
            (0..7).map { count ->
                DynamicTest.dynamicTest("$width quote basic string after $count backslashes") {
                    val escapedQuote = if (count % 2 == 1) "\"#inside" else ""
                    val raw = delimiter + "value" + "\\".repeat(count) + escapedQuote + delimiter + " # outside"
                    val expected = "value" + "\\".repeat(count / 2) + escapedQuote
                    assertEquals(expected, parseValue(raw))
                }
            }
        }

    @TestFactory
    fun `triple quoted strings preserve one or two content quotes`(): List<DynamicTest> =
        listOf('\'', '"').flatMap { quote ->
            val delimiter = quote.toString().repeat(3)
            (1..2).map { count ->
                DynamicTest.dynamicTest("$count content quotes in $delimiter") {
                    val quotes = quote.toString().repeat(count)
                    val expected = quotes + "inside" + quotes + "#value" + quotes
                    assertEquals(expected, parseValue("$delimiter$expected$delimiter# comment"))
                }
            }
        }

    @TestFactory
    fun `basic strings reject malformed and reserved escapes`(): List<DynamicTest> =
        listOf(1, 3).flatMap { width ->
            val delimiter = "\"".repeat(width)
            listOf(
                "\\q",
                "\\x41",
                "\\e",
                "\\/",
                "\\'",
                "\\ ",
                "\\u",
                "\\u123",
                "\\U0001234",
                "\\u12g4",
                "\\u+041",
                "\\u-000",
                "\\u１２３４",
                "\\uD800",
                "\\uDFFF",
                "\\uD83D\\uDE00",
                "\\U0000D800",
                "\\U00110000",
                "\\UFFFFFFFF",
            ).mapIndexed { index, escape ->
                DynamicTest.dynamicTest("$width quote invalid escape: $index") {
                    assertFailsWith<IllegalArgumentException> { parseValue(delimiter + escape + delimiter) }
                }
            }
        }

    @TestFactory
    fun `quoted strings reject missing delimiters and trailing syntax`(): List<DynamicTest> =
        listOf("'", "\"", "'''", "\"\"\"").flatMap { delimiter ->
            listOf(
                delimiter,
                delimiter + "unterminated # comment",
                delimiter + "value" + delimiter + " garbage",
                delimiter + "value" + delimiter + " 'second value'",
                delimiter + "value" + delimiter + "; comment",
                delimiter + "value" + delimiter + " = 1",
                delimiter + "first\nsecond" + delimiter,
                delimiter + "first\r\nsecond" + delimiter,
            ).mapIndexed { index, raw ->
                DynamicTest.dynamicTest("$delimiter malformed string: $index") {
                    assertFailsWith<IllegalArgumentException> { parseValue(raw) }
                }
            }
        }

    @Test
    fun `unterminated basic escapes cannot consume a closing delimiter`() {
        for (delimiter in listOf("\"", "\"\"\"")) {
            assertFailsWith<IllegalArgumentException> { parseValue(delimiter + "value\\") }
            assertFailsWith<IllegalArgumentException> { parseValue(delimiter + "value\\" + delimiter) }
        }
    }

    @Test
    fun `triple strings reject excessive closing quote runs`() {
        for (quote in listOf("'", "\"")) {
            assertFailsWith<IllegalArgumentException> { parseValue(quote.repeat(3) + "value" + quote.repeat(6)) }
        }
    }

    @TestFactory
    fun `quoted strings reject unescaped controls`(): List<DynamicTest> =
        listOf("'", "\"", "'''", "\"\"\"").flatMap { delimiter ->
            ((0..31).filter { it != 9 } + 127).map { code ->
                DynamicTest.dynamicTest("$delimiter rejects raw control U+${code.toString(16)}") {
                    assertFailsWith<IllegalArgumentException> { parseValue(delimiter + code.toChar() + delimiter) }
                }
            }
        }

    @Test
    fun `string writer round trips delimiters whitespace and escaped controls on one physical line`() {
        val values =
            listOf(
                "",
                "  space \t ",
                "C:\\tools\\pwsh.exe",
                "#=\\u0022",
                "工程😀",
                "'",
                "''",
                "'''",
                "\"",
                "\"\"",
                "\"\"\"",
                "''''\"\"\"\\#",
                (0..127).map(Int::toChar).joinToString(""),
            )
        for (value in values) {
            val quoted = TomlParser.quoteString(value)
            assertFalse(quoted.any { it == '\r' || it == '\n' })
            assertEquals(value, parseValue("$quoted # saved value"))
        }
    }

    private fun parseValue(raw: String): String = TomlParser.parse("[test]\nvalue = $raw\n").getValue("test").getValue("value")
}
