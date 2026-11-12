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
package io.github.ketraterm.ui.swing.suggestion

/** Includes leading whitespace and one non-whitespace run, without interpreting shell syntax. */
internal fun nextInlineWordEnd(
    command: String,
    start: Int,
): Int {
    val clusters = EXTENDED_GRAPHEME_CLUSTER.matcher(command)
    var inWord = false
    while (clusters.find()) {
        if (clusters.end() <= start) continue
        val codePoint = command.codePointAt(clusters.start())
        val whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        if (inWord && whitespace) return clusters.start()
        if (!whitespace) inWord = true
    }
    return command.length
}
