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
package io.github.ketraterm.intellij.services

import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.text.matching.MatchingMode
import io.github.ketraterm.completion.api.TerminalCompletionMatchRanges

/** Prepares one query's IDE matchers for target-name eligibility and display-relative annotation. */
internal class IntellijCompletionPathMatcher(
    prefix: String,
) {
    private val empty = prefix.isEmpty()
    private val fullMatcher = NameUtil.buildMatcher("*$prefix", MatchingMode.IGNORE_CASE)
    private val nameMatcher = NameUtil.buildMatcher("*${prefix.substringAfterLast('/')}", MatchingMode.IGNORE_CASE)
    private val parentMatcher =
        prefix.substringBeforeLast('/', "").takeIf(String::isNotEmpty)?.let {
            NameUtil.buildMatcher("*$it", MatchingMode.IGNORE_CASE)
        }

    /** Whether the query's final component matches the target name, independently of its ancestors. */
    fun matchesName(name: String): Boolean = empty || nameMatcher.match(name) != null

    /** Whether the original qualified query matches a path returned by a broadened IDE search. */
    fun matchesPath(path: String): Boolean = empty || fullMatcher.match(path) != null

    /** Returns display-relative UTF-16 fragments, preferring the basename over unrelated ancestors. */
    fun ranges(path: String): TerminalCompletionMatchRanges {
        if (empty) return TerminalCompletionMatchRanges.EMPTY
        val separator = path.lastIndexOf('/')
        val nameOffset = separator + 1
        val nameMatches = nameMatcher.match(path.substring(nameOffset))
        if (nameMatches.isNullOrEmpty()) {
            val fragments = fullMatcher.match(path).orEmpty()
            return TerminalCompletionMatchRanges.fromPackedOffsets(
                path,
                IntArray(fragments.size * 2) { index ->
                    val fragment = fragments[index / 2]
                    if (index % 2 == 0) fragment.startOffset else fragment.endOffset
                },
            )
        }
        val parentMatches =
            if (parentMatcher == null || separator < 0) {
                emptyList()
            } else {
                parentMatcher.match(path.substring(0, separator)).orEmpty()
            }
        val offsets = IntArray((parentMatches.size + nameMatches.size) * 2)
        var index = 0
        for ((start, end) in parentMatches) {
            offsets[index++] = start
            offsets[index++] = end
        }
        for ((start, end) in nameMatches) {
            offsets[index++] = nameOffset + start
            offsets[index++] = nameOffset + end
        }
        return TerminalCompletionMatchRanges.fromPackedOffsets(path, offsets)
    }
}
