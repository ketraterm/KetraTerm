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
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.commandline.isTerminalOptionToken
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import io.github.ketraterm.completion.spec.CommandSpecResolver.findSpec

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
) : TerminalCompletionSource {
    private val commandSpecs = commandSpecs.toList()

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        val workingDir = request.workingDirectoryUri ?: return emptyList()
        val context = TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset)
        val prefix = context.activePrefix

        // Don't autocomplete paths if the active prefix looks like an option flag
        if (prefix.startsWith("-")) return emptyList()

        val commandTokenIndex = context.tokens.firstCommandTokenIndex()
        val isCommandToken = context.activeTokenIndex <= commandTokenIndex
        val pathKind = pathKind(context, commandTokenIndex, isCommandToken)

        // If we are in the command position, only complete paths if prefix is explicitly path-like
        if (isCommandToken && !isPathLike(prefix)) return emptyList()
        if (!isCommandToken && pathKind == TerminalPathArgumentKind.NONE && !isPathLike(prefix)) return emptyList()

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
            if (!pathKind.accepts(entry)) continue
            if (filePrefix.isEmpty() && entry.name.startsWith(".")) continue
            if (matchesPrefix(entry.name, filePrefix)) {
                val rawSuffix = if (entry.isDirectory) "$pathSeparator" else ""
                val rawReplacement = directoryPortion + entry.name + rawSuffix
                val replacementText =
                    replacementText(
                        commandLine = request.commandLine,
                        replacementStartOffset = context.replacementStartOffset,
                        rawReplacement = rawReplacement,
                        pathSeparator = pathSeparator,
                    )

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
        commandLine: String,
        replacementStartOffset: Int,
        rawReplacement: String,
        pathSeparator: Char,
    ): String {
        val normalizedReplacement = if (pathSeparator == '\\') rawReplacement.replace('/', '\\') else rawReplacement
        val quote = commandLine.getOrNull(replacementStartOffset)
        return when (quote) {
            SINGLE_QUOTE -> quoteSingle(normalizedReplacement)
            DOUBLE_QUOTE -> quoteDouble(normalizedReplacement)
            else -> normalizedReplacement
        }
    }

    private fun quoteSingle(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun quoteDouble(value: String): String =
        buildString(value.length + 2) {
            append(DOUBLE_QUOTE)
            for (ch in value) {
                if (ch == DOUBLE_QUOTE || ch == BACKSLASH) append(BACKSLASH)
                append(ch)
            }
            append(DOUBLE_QUOTE)
        }

    private fun isPathLike(prefix: String): Boolean =
        prefix.startsWith("/") ||
            prefix.startsWith("\\") ||
            prefix.startsWith(".") ||
            prefix.startsWith("~") ||
            prefix.contains("/") ||
            prefix.contains("\\")

    private fun pathKind(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
        isCommandToken: Boolean,
    ): TerminalPathArgumentKind {
        if (isCommandToken) return TerminalPathArgumentKind.FILE_OR_DIRECTORY
        val commandToken = context.tokens.getOrNull(commandTokenIndex) ?: return TerminalPathArgumentKind.NONE
        val root = findSpec(commandSpecs, normalizeTerminalCommandToken(commandToken.text)) ?: return TerminalPathArgumentKind.NONE
        val resolved = resolveCommandPath(context, commandTokenIndex, root)
        optionBeforeActiveValue(context, commandTokenIndex, resolved.commands)?.let { option ->
            if (option.valuePathKind != TerminalPathArgumentKind.NONE) return option.valuePathKind
        }
        return resolved.current.positionalArgumentPathKind
    }

    private fun resolveCommandPath(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
        root: TerminalCommandSpec,
    ): ResolvedCommandPath {
        val commands = ArrayList<TerminalCommandSpec>()
        commands += root
        var current = root
        var index = commandTokenIndex + 1
        while (index < context.activeTokenIndex) {
            val token = context.tokens[index].text
            if (token.isTerminalOptionToken()) {
                val option = findOption(commands, token)
                if (option?.requiresValue == true) index++
                index++
                continue
            }

            val next = findSpec(current.subcommands, normalizeTerminalCommandToken(token)) ?: break
            current = next
            commands += current
            index++
        }
        return ResolvedCommandPath(commands)
    }

    private fun optionBeforeActiveValue(
        context: TerminalCommandLineContext,
        commandTokenIndex: Int,
        commands: List<TerminalCommandSpec>,
    ): TerminalOptionSpec? {
        val optionIndex = context.activeTokenIndex - 1
        if (optionIndex <= commandTokenIndex) return null
        val optionToken = context.tokens.getOrNull(optionIndex) ?: return null
        if (!optionToken.text.isTerminalOptionToken()) return null
        val option = findOption(commands, optionToken.text) ?: return null
        return if (option.requiresValue) option else null
    }

    private fun findOption(
        commands: List<TerminalCommandSpec>,
        token: String,
    ): TerminalOptionSpec? {
        val normalized = normalizeTerminalCommandToken(token)
        return commands.asReversed().firstNotNullOfOrNull { command ->
            command.options.firstOrNull { option ->
                option.names.any { name -> normalizeTerminalCommandToken(name) == normalized }
            }
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
        private const val SINGLE_QUOTE = '\''
        private const val DOUBLE_QUOTE = '"'
        private const val BACKSLASH = '\\'
    }

    private data class ResolvedCommandPath(
        val commands: List<TerminalCommandSpec>,
    ) {
        val current: TerminalCommandSpec get() = commands.last()
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
