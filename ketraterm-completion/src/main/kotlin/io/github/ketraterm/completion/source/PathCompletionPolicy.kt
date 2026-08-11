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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.model.TerminalHiddenPathPolicy
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** Shared allocation-free path eligibility policy for direct and indexed sources. */
internal fun isPathLike(prefix: String): Boolean =
    prefix.startsWith('/') ||
        prefix.startsWith('\\') ||
        prefix.startsWith('.') ||
        prefix.startsWith('~') ||
        prefix.indexOf('/') >= 0 ||
        prefix.indexOf('\\') >= 0

internal fun TerminalPathArgumentKind.acceptsPathEntry(isDirectory: Boolean): Boolean =
    when (this) {
        TerminalPathArgumentKind.NONE,
        TerminalPathArgumentKind.FILE_OR_DIRECTORY,
        -> true

        TerminalPathArgumentKind.DIRECTORY -> isDirectory
        TerminalPathArgumentKind.FILE -> !isDirectory
    }

internal fun TerminalHiddenPathPolicy.acceptsPath(
    path: String,
    activePrefix: String,
): Boolean =
    !path.hasHiddenSegment() ||
        when (this) {
            TerminalHiddenPathPolicy.DEFAULT -> activePrefix.startsWithActiveDot()
            TerminalHiddenPathPolicy.INCLUDE -> true
            TerminalHiddenPathPolicy.EXCLUDE -> false
        }

private fun String.startsWithActiveDot(): Boolean {
    val slash = lastIndexOf('/')
    val backslash = lastIndexOf('\\')
    val activeStart = maxOf(slash, backslash) + 1
    return activeStart < length && this[activeStart] == '.'
}

private fun String.hasHiddenSegment(): Boolean {
    var segmentStart = 0
    while (segmentStart < length) {
        val forwardSeparator = indexOf('/', segmentStart)
        val backwardSeparator = indexOf('\\', segmentStart)
        val segmentEnd =
            when {
                forwardSeparator < 0 -> if (backwardSeparator < 0) length else backwardSeparator
                backwardSeparator < 0 -> forwardSeparator
                else -> minOf(forwardSeparator, backwardSeparator)
            }
        val segmentLength = segmentEnd - segmentStart
        val isNavigationSegment =
            segmentLength == 1 ||
                (segmentLength == 2 && this[segmentStart + 1] == '.')
        if (this[segmentStart] == '.' && !isNavigationSegment) return true
        if (segmentEnd == length) return false
        segmentStart = segmentEnd + 1
    }
    return false
}
