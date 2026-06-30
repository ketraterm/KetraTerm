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
package io.github.ketraterm.completion

private fun String.containsLineBreak(): Boolean = indexOf('\n') >= 0 || indexOf('\r') >= 0

/**
 * Privacy-preserving structural shape of one command line.
 *
 * The shape records command family and option usage without retaining raw
 * positional argument values. This lets ranking learn patterns such as
 * `git switch <arg>` or `git log --stat` without storing branch names, paths,
 * URLs, tokens, or other sensitive argument text as aggregate metadata.
 *
 * @property executable normalized executable token, such as `git` or `npm`.
 * @property subcommands normalized non-option tokens immediately after
 * [executable], before the first option or positional argument classified as a
 * value.
 * @property optionNames normalized option names observed in the command line.
 * @property positionalArgumentCount count of positional argument value tokens
 * not stored in this model.
 * @property optionValueCount count of value tokens consumed by options that use
 * a separate argument.
 * @property normalizedShapeKey stable lowercase deduplication key for this
 * structural shape.
 */
data class TerminalCommandLineShape
    @JvmOverloads
    constructor(
        val executable: String,
        val subcommands: List<String> = emptyList(),
        val optionNames: List<String> = emptyList(),
        val positionalArgumentCount: Int = 0,
        val optionValueCount: Int = 0,
        val normalizedShapeKey: String =
            normalizedShapeKey(
                executable = executable,
                subcommands = subcommands,
                optionNames = optionNames,
                positionalArgumentCount = positionalArgumentCount,
                optionValueCount = optionValueCount,
            ),
    ) {
        init {
            require(executable.isNotBlank()) { "executable must not be blank" }
            require(subcommands.none(String::isBlank)) { "subcommands must not contain blank values" }
            require(optionNames.none(String::isBlank)) { "optionNames must not contain blank values" }
            require(positionalArgumentCount >= 0) {
                "positionalArgumentCount must be >= 0, was $positionalArgumentCount"
            }
            require(optionValueCount >= 0) { "optionValueCount must be >= 0, was $optionValueCount" }
            require(normalizedShapeKey.isNotBlank()) { "normalizedShapeKey must not be blank" }
        }

        companion object {
            /**
             * Parses a command line into a privacy-preserving shape.
             *
             * Blank, multi-line, assignment-only, and malformed executable
             * inputs return `null`.
             *
             * @param commandLine command text to classify.
             * @return structural shape, or `null` when no executable is present.
             */
            @JvmStatic
            fun fromCommandLine(commandLine: String): TerminalCommandLineShape? {
                if (commandLine.isBlank() || commandLine.containsLineBreak()) return null
                val tokens = TerminalCommandLineTokenizer.parse(commandLine, commandLine.length).tokens
                var tokenIndex = skipEnvironmentAssignments(tokens)
                if (tokenIndex >= tokens.size) return null
                val executable = normalizeToken(tokens[tokenIndex].text)
                if (executable.isBlank()) return null
                tokenIndex++

                val subcommands = ArrayList<String>(DEFAULT_LIST_CAPACITY)
                val optionNames = ArrayList<String>(DEFAULT_LIST_CAPACITY)
                var positionalArgumentCount = 0
                var optionValueCount = 0
                var expectingOptionValue = false
                var acceptingSubcommands = true

                while (tokenIndex < tokens.size) {
                    val token = tokens[tokenIndex].text
                    val normalized = normalizeToken(token)
                    if (normalized.isBlank()) {
                        tokenIndex++
                        continue
                    }
                    if (expectingOptionValue) {
                        optionValueCount++
                        expectingOptionValue = false
                        acceptingSubcommands = false
                    } else if (normalized == OPTION_TERMINATOR) {
                        acceptingSubcommands = false
                    } else if (normalized.isOptionToken()) {
                        val optionName = normalized.substringBefore("=")
                        optionNames.add(optionName)
                        if (!normalized.contains("=") && optionName.optionUsuallyRequiresValue()) {
                            expectingOptionValue = true
                        }
                        acceptingSubcommands = false
                    } else if (acceptingSubcommands && subcommands.size < MAX_SUBCOMMAND_DEPTH) {
                        subcommands.add(normalized)
                    } else {
                        acceptingSubcommands = false
                        positionalArgumentCount++
                    }
                    tokenIndex++
                }

                return TerminalCommandLineShape(
                    executable = executable,
                    subcommands = subcommands,
                    optionNames = optionNames.sorted(),
                    positionalArgumentCount = positionalArgumentCount,
                    optionValueCount = optionValueCount,
                )
            }

            /**
             * Builds the stable lowercase key for one command-line shape.
             *
             * @param executable executable token.
             * @param subcommands structural subcommand path.
             * @param optionNames option names present in the command.
             * @param positionalArgumentCount count of positional values.
             * @param optionValueCount count of separate option values.
             * @return stable normalized shape key.
             */
            @JvmStatic
            fun normalizedShapeKey(
                executable: String,
                subcommands: List<String>,
                optionNames: List<String>,
                positionalArgumentCount: Int,
                optionValueCount: Int,
            ): String =
                buildString {
                    append(normalizeToken(executable))
                    append('|')
                    append(subcommands.joinToString("/") { normalizeToken(it) })
                    append('|')
                    append(optionNames.map(::normalizeToken).sorted().joinToString(","))
                    append("|p=")
                    append(positionalArgumentCount)
                    append("|ov=")
                    append(optionValueCount)
                }

            private fun skipEnvironmentAssignments(tokens: List<TerminalCommandLineToken>): Int {
                var index = 0
                while (index < tokens.size && tokens[index].text.isEnvironmentAssignment()) index++
                return index
            }

            private fun normalizeToken(token: String): String = token.trim().lowercase()

            private fun String.isEnvironmentAssignment(): Boolean {
                val equalsIndex = indexOf('=')
                if (equalsIndex <= 0) return false
                val name = substring(0, equalsIndex)
                return name.all { it == '_' || it.isLetterOrDigit() } && !name.first().isDigit()
            }

            private fun String.isOptionToken(): Boolean = length > 1 && startsWith("-") && this != "-"

            private fun String.optionUsuallyRequiresValue(): Boolean =
                when (this) {
                    "-c", "-d", "-f", "-m", "-o", "-p", "-u" -> true
                    else -> startsWith("--") && !BOOLEAN_LONG_OPTIONS.contains(this)
                }

            private const val DEFAULT_LIST_CAPACITY = 4
            private const val MAX_SUBCOMMAND_DEPTH = 2
            private const val OPTION_TERMINATOR = "--"
            private val BOOLEAN_LONG_OPTIONS =
                setOf(
                    "--all",
                    "--amend",
                    "--debug",
                    "--dry-run",
                    "--force",
                    "--global",
                    "--help",
                    "--info",
                    "--json",
                    "--offline",
                    "--quiet",
                    "--stat",
                    "--verbose",
                    "--version",
                    "--watch",
                )
        }
    }

/**
 * Aggregated statistics for one privacy-preserving command-line shape.
 *
 * @property shape structural command-line shape being measured.
 * @property profileId optional host profile id associated with this row.
 * @property workingDirectoryUri optional working directory URI associated with
 * this row.
 * @property useCount number of observed command executions with this shape.
 * @property successCount number of successful executions with this shape.
 * @property failureCount number of failed executions with this shape.
 * @property acceptedCount number of accepted suggestions with this shape.
 * @property dismissedCount number of explicitly dismissed suggestions with this shape.
 * @property lastUsedEpochMillis host timestamp for the newest represented event.
 */
data class TerminalCommandShapeStats
    @JvmOverloads
    constructor(
        val shape: TerminalCommandLineShape,
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val useCount: Int = 0,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val acceptedCount: Int = 0,
        val dismissedCount: Int = 0,
        val lastUsedEpochMillis: Long = 0L,
    ) {
        init {
            require(useCount >= 0) { "useCount must be >= 0, was $useCount" }
            require(successCount >= 0) { "successCount must be >= 0, was $successCount" }
            require(failureCount >= 0) { "failureCount must be >= 0, was $failureCount" }
            require(acceptedCount >= 0) { "acceptedCount must be >= 0, was $acceptedCount" }
            require(dismissedCount >= 0) { "dismissedCount must be >= 0, was $dismissedCount" }
            require(lastUsedEpochMillis >= 0L) { "lastUsedEpochMillis must be >= 0, was $lastUsedEpochMillis" }
        }
    }

/**
 * Complete snapshot of exact command and structural shape statistics.
 *
 * @property commandStats exact command-line rows used for concrete suggestions.
 * @property shapeStats privacy-preserving command-shape rows used for ranking
 * analytics and future shape-aware suggestion ranking.
 */
data class TerminalCommandCompletionStatsSnapshot(
    val commandStats: List<TerminalCommandCompletionStats>,
    val shapeStats: List<TerminalCommandShapeStats>,
)
