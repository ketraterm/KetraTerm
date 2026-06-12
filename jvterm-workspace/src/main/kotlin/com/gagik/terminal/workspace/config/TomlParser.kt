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
package com.gagik.terminal.workspace.config

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
        if (value.length >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
