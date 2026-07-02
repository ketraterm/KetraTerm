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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER

/**
 * Autocomplete source for directory contents and file paths.
 */
internal class PathCompletionSource(
    private val fileSystemProvider: TerminalFileSystemProvider,
) : TerminalCompletionSource {
    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        val workingDir = request.workingDirectoryUri ?: return emptyList()
        val context = TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset)
        val prefix = context.activePrefix

        // Don't autocomplete paths if the active prefix looks like an option flag
        if (prefix.startsWith("-")) return emptyList()

        val commandTokenIndex = context.tokens.firstCommandTokenIndex()
        val isCommandToken = context.activeTokenIndex <= commandTokenIndex

        // If we are in the command position, only complete paths if prefix is explicitly path-like
        if (isCommandToken && !isPathLike(prefix)) return emptyList()

        // Slashes normalized to forward slashes for URL path resolution
        val normalizedPrefix = prefix.replace('\\', '/')
        val lastSlashIndex = normalizedPrefix.lastIndexOf('/')
        val directoryPortion = if (lastSlashIndex >= 0) normalizedPrefix.substring(0, lastSlashIndex + 1) else ""
        val filePrefix = if (lastSlashIndex >= 0) normalizedPrefix.substring(lastSlashIndex + 1) else normalizedPrefix

        val baseUri =
            try {
                val normalizedDir = if (workingDir.endsWith("/")) workingDir else "$workingDir/"
                java.net.URI(normalizedDir)
            } catch (_: Exception) {
                return emptyList()
            }

        val resolvedUri =
            try {
                baseUri.resolve(directoryPortion)
            } catch (_: Exception) {
                return emptyList()
            }

        val resolvedUriString = canonicalizeDirectoryUri(resolvedUri.toString())
        val entries =
            try {
                fileSystemProvider.listDirectory(resolvedUriString)
            } catch (_: Exception) {
                return emptyList()
            }

        val pathSeparator = if (prefix.contains('\\')) '\\' else '/'
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0

        for (entry in entries) {
            if (matchesPrefix(entry.name, filePrefix)) {
                val rawSuffix = if (entry.isDirectory) "$pathSeparator" else ""
                val rawReplacement = directoryPortion + entry.name + rawSuffix
                val replacementText = if (pathSeparator == '\\') rawReplacement.replace('/', '\\') else rawReplacement

                candidates +=
                    TerminalCompletionCandidate(
                        replacementText = replacementText,
                        replacementStartOffset = context.replacementStartOffset,
                        replacementEndOffset = context.replacementEndOffset,
                        displayText = entry.name + (if (entry.isDirectory) "$pathSeparator" else ""),
                        detail = if (entry.isDirectory) "directory" else "file",
                        source = SOURCE_PATH,
                        kind = TerminalCompletionCandidateKind.PATH,
                        score = score(entry.name, filePrefix, PATH_BASE_SCORE, orderIndex++),
                    )
            }
        }

        return candidates.sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER).take(request.maxCandidates)
    }

    private fun isPathLike(prefix: String): Boolean =
        prefix.startsWith("/") ||
            prefix.startsWith("\\") ||
            prefix.startsWith(".") ||
            prefix.startsWith("~") ||
            prefix.contains("/") ||
            prefix.contains("\\")

    private fun matchesPrefix(
        value: String,
        prefix: String,
    ): Boolean = prefix.isEmpty() || value.startsWith(prefix, ignoreCase = true)

    private fun score(
        value: String,
        prefix: String,
        base: Int,
        orderIndex: Int,
    ): Int {
        if (prefix.isEmpty()) return base - orderIndex
        val caseBonus = if (value.startsWith(prefix)) 40 else 20
        val completionLengthPenalty = value.length - prefix.length
        return base + caseBonus - completionLengthPenalty - orderIndex
    }

    private companion object {
        private const val SOURCE_PATH = "path"
        private const val PATH_BASE_SCORE = 200
    }
}
