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
import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

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
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
) : ContextAwareCompletionSource {
    private val commandSpecs = commandSpecs.toList()

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset, request.shellCapabilities.syntax),
        )

    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        val workingDir = request.workingDirectoryUri ?: return emptyList()
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = request.commandLine,
                lineContext = commandLineContext,
                commandSpecs = commandSpecs,
            )
        val prefix = context.activePrefix

        if (context.activePosition == TerminalCompletionActivePosition.OPERATOR) return emptyList()

        // Don't autocomplete paths if the active prefix looks like an option flag
        if (context.activePosition == TerminalCompletionActivePosition.OPTION_NAME) return emptyList()

        // If we are in the command position, only complete paths if prefix is explicitly path-like
        if (context.activePosition == TerminalCompletionActivePosition.COMMAND && !isPathLike(prefix)) return emptyList()
        if (context.activePosition != TerminalCompletionActivePosition.COMMAND &&
            context.expectedPathKind == TerminalPathArgumentKind.NONE &&
            !isPathLike(prefix)
        ) {
            return emptyList()
        }

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
            if (!context.expectedPathKind.accepts(entry)) continue
            if (filePrefix.isEmpty() && entry.name.startsWith(".")) continue
            if (matchesPrefix(entry.name, filePrefix)) {
                val rawSuffix = if (entry.isDirectory) "$pathSeparator" else ""
                val rawReplacement = directoryPortion + entry.name + rawSuffix
                val replacementText =
                    replacementText(
                        request = request,
                        activeTokenQuote = context.activeTokenQuote,
                        rawReplacement = rawReplacement,
                        pathSeparator = pathSeparator,
                    ) ?: continue

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

    private fun replacementText(
        request: TerminalCompletionRequest,
        activeTokenQuote: Char,
        rawReplacement: String,
        pathSeparator: Char,
    ): String? {
        val normalizedReplacement = if (pathSeparator == '\\') rawReplacement.replace('/', '\\') else rawReplacement
        val policy = request.shellCapabilities.quoting
        return when (activeTokenQuote) {
            SINGLE_QUOTE -> quoteSingle(normalizedReplacement, policy)
            DOUBLE_QUOTE -> quoteDouble(normalizedReplacement, policy)
            else -> unquotedReplacement(normalizedReplacement, policy)
        }
    }

    private fun unquotedReplacement(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): String? {
        if (!needsEscaping(value, policy)) return value
        return when (policy) {
            TerminalShellQuotingPolicy.CONSERVATIVE -> null
            TerminalShellQuotingPolicy.POSIX -> escapePosixUnquoted(value)
            TerminalShellQuotingPolicy.POWERSHELL -> quoteSingle(value, policy)
        }
    }

    private fun quoteSingle(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): String? =
        when (policy) {
            TerminalShellQuotingPolicy.CONSERVATIVE ->
                if (value.contains(SINGLE_QUOTE)) null else "'$value'"
            TerminalShellQuotingPolicy.POWERSHELL -> "'${value.replace("'", "''")}'"
            TerminalShellQuotingPolicy.POSIX,
            -> "'${value.replace("'", "'\\''")}'"
        }

    private fun quoteDouble(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): String? =
        when (policy) {
            TerminalShellQuotingPolicy.CONSERVATIVE ->
                if (value.any { it == DOUBLE_QUOTE || it == DOLLAR || it == BACKTICK }) null else "\"$value\""
            TerminalShellQuotingPolicy.POWERSHELL ->
                buildString(value.length + 2) {
                    append(DOUBLE_QUOTE)
                    for (ch in value) {
                        if (ch == DOUBLE_QUOTE || ch == BACKTICK || ch == DOLLAR) append(BACKTICK)
                        append(ch)
                    }
                    append(DOUBLE_QUOTE)
                }
            TerminalShellQuotingPolicy.POSIX,
            ->
                buildString(value.length + 2) {
                    append(DOUBLE_QUOTE)
                    for (ch in value) {
                        if (ch == DOUBLE_QUOTE || ch == BACKSLASH || ch == DOLLAR || ch == BACKTICK) append(BACKSLASH)
                        append(ch)
                    }
                    append(DOUBLE_QUOTE)
                }
        }

    private fun escapePosixUnquoted(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                if (ch.needsPosixUnquotedEscape()) append(BACKSLASH)
                append(ch)
            }
        }

    private fun needsEscaping(
        value: String,
        policy: TerminalShellQuotingPolicy,
    ): Boolean =
        when (policy) {
            TerminalShellQuotingPolicy.POSIX -> value.any { it.needsPosixUnquotedEscape() }
            TerminalShellQuotingPolicy.POWERSHELL -> value.any { it.needsPowerShellUnquotedEscape() }
            TerminalShellQuotingPolicy.CONSERVATIVE ->
                value.any { it.needsPosixUnquotedEscape() || it.needsPowerShellUnquotedEscape() }
        }

    private fun Char.needsPosixUnquotedEscape(): Boolean =
        isShellWhitespace() ||
            this == SINGLE_QUOTE ||
            this == DOUBLE_QUOTE ||
            this == BACKSLASH ||
            this == DOLLAR ||
            this == BACKTICK ||
            this == SEMICOLON ||
            this == AMPERSAND ||
            this == LEFT_PAREN ||
            this == RIGHT_PAREN

    private fun Char.needsPowerShellUnquotedEscape(): Boolean =
        isShellWhitespace() ||
            this == BACKTICK ||
            this == DOLLAR ||
            this == SINGLE_QUOTE ||
            this == DOUBLE_QUOTE ||
            this == LEFT_PAREN ||
            this == RIGHT_PAREN ||
            this == LEFT_BRACE ||
            this == RIGHT_BRACE ||
            this == LEFT_BRACKET ||
            this == RIGHT_BRACKET ||
            this == LESS_THAN ||
            this == GREATER_THAN ||
            this == PIPE ||
            this == AMPERSAND ||
            this == SEMICOLON ||
            this == COMMA ||
            this == AT ||
            this == HASH

    private fun Char.isShellWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

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
        private const val SINGLE_QUOTE = '\''
        private const val DOUBLE_QUOTE = '"'
        private const val BACKSLASH = '\\'
        private const val BACKTICK = '`'
        private const val DOLLAR = '$'
        private const val SEMICOLON = ';'
        private const val AMPERSAND = '&'
        private const val LEFT_PAREN = '('
        private const val RIGHT_PAREN = ')'
        private const val LEFT_BRACE = '{'
        private const val RIGHT_BRACE = '}'
        private const val LEFT_BRACKET = '['
        private const val RIGHT_BRACKET = ']'
        private const val LESS_THAN = '<'
        private const val GREATER_THAN = '>'
        private const val PIPE = '|'
        private const val COMMA = ','
        private const val AT = '@'
        private const val HASH = '#'
    }

    private fun TerminalPathArgumentKind.accepts(entry: TerminalFileEntry): Boolean =
        when (this) {
            TerminalPathArgumentKind.NONE,
            TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            -> true
            TerminalPathArgumentKind.DIRECTORY -> entry.isDirectory
            TerminalPathArgumentKind.FILE -> !entry.isDirectory
        }
}
