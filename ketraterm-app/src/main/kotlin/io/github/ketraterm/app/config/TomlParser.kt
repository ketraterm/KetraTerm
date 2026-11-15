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

/**
 * Parser for the app's section-based TOML subset, with string values confined to one physical line.
 */
internal object TomlParser {
    /**
     * Parses a TOML string into a nested map structure: section -> key -> value.
     * Keys without a section header are placed under an empty section key ("").
     */
    fun parse(content: String): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, MutableMap<String, String>>()
        var currentSection = ""

        content.lineSequence().forEach { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                return@forEach
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                if (currentSection.isEmpty()) {
                    throw IllegalArgumentException("Section header must not be empty")
                }
            } else {
                val equalsIdx = trimmed.indexOf('=')
                if (equalsIdx != -1) {
                    val key = trimmed.substring(0, equalsIdx).trim()
                    val rawValue = trimmed.substring(equalsIdx + 1).trimStart(' ', '\t')
                    val finalValue = parseValue(rawValue)
                    if (key.isNotEmpty()) {
                        result.getOrPut(currentSection) { LinkedHashMap() }[key] = finalValue
                    }
                } else {
                    throw IllegalArgumentException("Malformed TOML line: $line")
                }
            }
        }
        return result
    }

    private fun parseValue(value: String): String {
        val quote = value.firstOrNull()
        if (quote != '"' && quote != '\'') return value.substringBefore('#').trimEnd(' ', '\t')

        val delimiterLength = if (value.startsWith(quote.toString().repeat(3))) 3 else 1
        val decoded = StringBuilder()
        var index = delimiterLength
        while (index < value.length) {
            val character = value[index++]
            if (character == '\\' && quote == '"') {
                index = decodeEscape(value, index, decoded)
                continue
            }
            if (character == quote) {
                var quotes = 1
                if (delimiterLength == 3) {
                    while (index < value.length && value[index] == quote) {
                        quotes++
                        index++
                    }
                }
                if (quotes < delimiterLength) {
                    repeat(quotes) { decoded.append(quote) }
                    continue
                }
                // Triple delimiters permit one or two content quotes immediately before closing.
                require(quotes <= delimiterLength + 2) { "Too many quotes at end of string" }
                repeat(quotes - delimiterLength) { decoded.append(quote) }
                while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
                require(index == value.length || value[index] == '#') { "Unexpected text after string" }
                return decoded.toString()
            }
            require(character == '\t' || character >= ' ' && character != '\u007F') { "Unescaped control character in string" }
            decoded.append(character)
        }
        throw IllegalArgumentException("Unterminated string")
    }

    /** Encodes a value on one physical TOML line, preserving controls and significant whitespace. */
    fun quoteString(value: String): String {
        if ("'''" !in value && !value.endsWith('\'') && value.none { it < ' ' || it == '\u007F' }) return "'''$value'''"
        return buildString {
            append("\"\"\"")
            for (character in value) {
                when {
                    // Escape quotes so the parser cannot mistake a quote run for the closing delimiter.
                    character == '"' -> append("\\u0022")
                    character == '\\' -> append("\\\\")
                    character < ' ' || character == '\u007F' -> {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    }
                    else -> append(character)
                }
            }
            append("\"\"\"")
        }
    }

    private fun decodeEscape(
        value: String,
        startIndex: Int,
        decoded: StringBuilder,
    ): Int {
        require(startIndex < value.length) { "Incomplete string escape" }
        var index = startIndex
        when (val escape = value[index++]) {
            '\\', '"' -> decoded.append(escape)
            'n' -> decoded.append('\n')
            'r' -> decoded.append('\r')
            't' -> decoded.append('\t')
            'b' -> decoded.append('\b')
            'f' -> decoded.append('\u000C')
            'u', 'U' -> {
                val digits = if (escape == 'u') 4 else 8
                require(value.length - index >= digits) { "Incomplete Unicode escape" }
                var codepoint = 0L
                repeat(digits) {
                    val digit =
                        when (val character = value[index++]) {
                            in '0'..'9' -> character - '0'
                            in 'a'..'f' -> character - 'a' + 10
                            in 'A'..'F' -> character - 'A' + 10
                            else -> throw IllegalArgumentException("Invalid Unicode escape")
                        }
                    codepoint = codepoint * 16 + digit
                }
                require(codepoint <= 0x10FFFF && codepoint !in 0xD800..0xDFFF) { "Invalid Unicode escape" }
                decoded.appendCodePoint(codepoint.toInt())
            }
            else -> throw IllegalArgumentException("Unsupported string escape")
        }
        return index
    }
}
