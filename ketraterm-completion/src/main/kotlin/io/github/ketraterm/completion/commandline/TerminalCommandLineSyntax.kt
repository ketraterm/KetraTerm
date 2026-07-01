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
package io.github.ketraterm.completion.commandline

internal const val TERMINAL_COMMAND_LIST_CAPACITY: Int = 4
internal const val TERMINAL_COMMAND_OPTION_TERMINATOR: String = "--"

internal fun normalizeTerminalCommandToken(token: String): String = token.trim().lowercase()

internal fun List<TerminalCommandLineToken>.firstCommandTokenIndex(): Int {
    var index = 0
    while (index < size && this[index].text.isTerminalEnvironmentAssignment()) index++
    return index
}

internal fun String.isTerminalEnvironmentAssignment(): Boolean {
    val equalsIndex = indexOf('=')
    if (equalsIndex <= 0) return false
    val name = substring(0, equalsIndex)
    return name.all { it == '_' || it.isLetterOrDigit() } && !name.first().isDigit()
}

internal fun String.isTerminalOptionToken(): Boolean = length > 1 && startsWith("-") && this != "-"
