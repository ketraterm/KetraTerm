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
package io.github.ketraterm.workspace.config

/**
 * Lightweight and robust parser for section-based TOML configuration files.
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
            val trimmed = line.trim()
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
                    val rawValue = trimmed.substring(equalsIdx + 1).trim()
                    val valueWithoutComments = stripComment(rawValue)
                    val finalValue = stripQuotes(valueWithoutComments)
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

    private fun stripComment(value: String): String {
        if (value.startsWith("'''") || value.startsWith("\"\"\"")) {
            val end = value.indexOf(value.substring(0, 3), startIndex = 3)
            require(end >= 0) { "Unterminated literal string" }
            return value.substring(0, end + 3)
        }
        var inDoubleQuotes = false
        var inSingleQuotes = false
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
            } else if (char == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
            } else if (char == '#' && !inDoubleQuotes && !inSingleQuotes) {
                break
            }
            sb.append(char)
            i++
        }
        return sb.toString().trim()
    }

    private fun stripQuotes(value: String): String {
        if (value.startsWith("'''") && value.endsWith("'''") && value.length >= 6) {
            return value.substring(3, value.length - 3)
        }
        if (value.startsWith("\"\"\"") && value.endsWith("\"\"\"") && value.length >= 6) {
            return decodeBasicString(value.substring(3, value.length - 3))
        }
        if (value.length >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }

    /** Quotes one physical line without interpreting shell backslashes, quotes, or comment characters. */
    fun quoteSingleLineString(value: String): String {
        require('\n' !in value && '\r' !in value) { "Startup command must be one line" }
        if ("'''" !in value && !value.endsWith('\'')) return "'''$value'''"
        // Unicode escapes avoid ambiguous quote runs at the closing delimiter.
        val escaped = value.replace("\\", "\\u005c").replace("\"", "\\u0022")
        return "\"\"\"$escaped\"\"\""
    }

    private fun decodeBasicString(value: String): String =
        buildString {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character != '\\') {
                    append(character)
                    continue
                }
                require(index < value.length) { "Incomplete string escape" }
                when (val escape = value[index++]) {
                    '\\', '"' -> append(escape)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'u', 'U' -> {
                        val digits = if (escape == 'u') 4 else 8
                        require(index + digits <= value.length) { "Incomplete Unicode escape" }
                        val codepoint = value.substring(index, index + digits).toIntOrNull(16)
                        require(codepoint != null && codepoint in 0..0x10FFFF && codepoint !in 0xD800..0xDFFF) { "Invalid Unicode escape" }
                        appendCodePoint(codepoint)
                        index += digits
                    }
                    else -> throw IllegalArgumentException("Unsupported string escape")
                }
            }
        }
}
