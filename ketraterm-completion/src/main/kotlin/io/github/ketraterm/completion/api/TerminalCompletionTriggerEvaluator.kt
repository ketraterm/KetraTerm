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
package io.github.ketraterm.completion.api

/**
 * Pure evaluation engine that determines if a command line should trigger completions.
 *
 * This evaluator is shared between all hosts (standalone app and IDE plugins)
 * to ensure consistent completion triggering policy across environments without
 * depending on Swing timers or session execution states.
 */
object TerminalCompletionTriggerEvaluator {
    /**
     * Returns whether the typed command line at the specified cursor offset should trigger suggestions.
     *
     * @param commandLine visible command text.
     * @param cursorOffset UTF-16 cursor position.
     * @param minimumNonWhitespaceCharacters minimum characters required for default typing.
     * @return `true` if suggestions should be requested.
     */
    fun shouldTrigger(
        commandLine: String,
        cursorOffset: Int,
        minimumNonWhitespaceCharacters: Int,
    ): Boolean {
        if (isLiveTrigger(commandLine, cursorOffset)) return true
        return nonWhitespaceCount(commandLine) >= minimumNonWhitespaceCharacters
    }

    /**
     * Returns whether the cursor is positioned after a specific trigger token.
     *
     * Trigger tokens bypass character length checks, instantly requesting suggestions.
     *
     * @param commandLine visible command text.
     * @param cursorOffset UTF-16 cursor position.
     * @return `true` if cursor is immediately after a live trigger token.
     */
    fun isLiveTrigger(
        commandLine: String,
        cursorOffset: Int,
    ): Boolean {
        if (cursorOffset <= 0) return false

        val lastChar = commandLine.getOrNull(cursorOffset - 1) ?: return false

        // 1. Hyphen option trigger (e.g. '-')
        if (lastChar == '-') return true

        // 2. Path separator trigger (e.g. '/' or '\')
        if (lastChar == '/' || lastChar == '\\') return true

        // 3. Environment variable trigger (e.g. '$')
        if (lastChar == '$') return true

        // 4. Finished-word space trigger (single space after a non-space character of at least 2 chars)
        if (lastChar == ' ') {
            val prevChar = commandLine.getOrNull(cursorOffset - 2)
            if (prevChar != null && prevChar != ' ') {
                val wordStart = commandLine.substring(0, cursorOffset - 1).lastIndexOf(' ')
                val word = commandLine.substring(wordStart + 1, cursorOffset - 1)
                return word.length >= 2
            }
        }

        return false
    }

    private fun nonWhitespaceCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            if (!text[index].isWhitespace()) count++
            index++
        }
        return count
    }
}
