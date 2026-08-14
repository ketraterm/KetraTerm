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
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.boundedTo
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import kotlinx.coroutines.CancellationException

/**
 * Autocomplete source for directory contents and file paths.
 *
 * Path completion is conservative for bare argument positions so command and
 * subcommand suggestions are not drowned out by every file in the current
 * directory. Known path-taking commands may request file, directory, or
 * directory-only completion from an empty argument prefix; otherwise callers
 * must type an explicitly path-like prefix such as `./`, `/`, `~`, or a token
 * containing a path separator.
 */
internal class PathCompletionSource(
    private val fileSystemProvider: TerminalFileSystemProvider,
) : TerminalCompletionSource {
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        val workingDir = request.workingDirectoryUri ?: return emptyList()
        val prefix = context.activePrefix

        if (context.activePosition == TerminalCompletionActivePosition.OPERATOR) return emptyList()

        if (context.activePosition == TerminalCompletionActivePosition.OPTION_NAME) return emptyList()

        if (context.activePosition == TerminalCompletionActivePosition.COMMAND && !isPathLike(prefix)) return emptyList()
        if (context.activePosition != TerminalCompletionActivePosition.COMMAND &&
            context.expectedPathKind == TerminalPathArgumentKind.NONE &&
            !isPathLike(prefix)
        ) {
            return emptyList()
        }

        val normalizedPrefix = prefix.replace('\\', '/')
        val pathParts = splitPathPrefix(normalizedPrefix) ?: return emptyList()
        val directoryPortion = pathParts.directoryPrefix
        val filePrefix = pathParts.entryNamePrefix
        val entries =
            try {
                fileSystemProvider.listDirectory(
                    TerminalDirectoryListingRequest(
                        workingDirectoryUri = workingDir,
                        directoryPrefix = directoryPortion,
                        entryNamePrefix = filePrefix,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return emptyList()
            }

        val pathSeparator = if (prefix.contains('\\')) '\\' else '/'
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0

        for ((name, isDirectory) in entries) {
            if (!context.expectedPathKind.acceptsPathEntry(isDirectory)) continue
            if (!context.expectedHiddenPathPolicy.acceptsPath(name, filePrefix)) continue
            if (matchesPrefix(name, filePrefix)) {
                val rawSuffix = if (isDirectory) "$pathSeparator" else ""
                val rawReplacement = directoryPortion + name + rawSuffix
                val replacementText =
                    ShellReplacementText.encode(
                        value = if (pathSeparator == '\\') rawReplacement.replace('/', '\\') else rawReplacement,
                        activeTokenQuote = context.activeTokenQuote,
                        policy = request.shellCapabilities.quoting,
                    ) ?: continue

                candidates +=
                    TerminalCompletionCandidate(
                        replacementText = replacementText,
                        replacementStartOffset = context.replacementStartOffset,
                        replacementEndOffset = context.replacementEndOffset,
                        displayText = name + (if (isDirectory) "$pathSeparator" else ""),
                        detail = if (isDirectory) "directory" else "file",
                        source = SOURCE_PATH,
                        kind = TerminalCompletionCandidateKind.PATH,
                        score = score(name, filePrefix, PATH_BASE_SCORE, orderIndex++),
                    )
            }
        }

        candidates.sortWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
        return candidates.boundedTo(limit)
    }

    private fun splitPathPrefix(prefix: String): PathParts? {
        if (prefix == "~") return PathParts(directoryPrefix = "~/", entryNamePrefix = "")
        if (prefix.startsWith("~") && !prefix.startsWith("~/")) return null
        val lastSlashIndex = prefix.lastIndexOf('/')
        return if (lastSlashIndex >= 0) {
            PathParts(
                directoryPrefix = prefix.substring(0, lastSlashIndex + 1),
                entryNamePrefix = prefix.substring(lastSlashIndex + 1),
            )
        } else {
            PathParts(directoryPrefix = "", entryNamePrefix = prefix)
        }
    }

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

    private data class PathParts(
        val directoryPrefix: String,
        val entryNamePrefix: String,
    )
}
