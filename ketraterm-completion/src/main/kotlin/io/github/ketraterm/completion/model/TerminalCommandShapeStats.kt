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
package io.github.ketraterm.completion.model

import io.github.ketraterm.completion.commandline.GenericCommandLineShapeClassifier
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken

/**
 * Privacy-preserving structural shape of one command line.
 *
 * The shape records command family and option usage without retaining raw
 * positional argument values. This lets ranking learn patterns such as
 * `git switch <arg>` or `git log --stat` without storing branch names, paths,
 * URLs, tokens, or other sensitive argument text as aggregate metadata.
 *
 * TODO(completion-ranking): Add command-family aggregate stats independent of
 * trailing arguments, for example `git checkout <arg>` and `npm run <arg>`,
 * without retaining private argument values.
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
            fun fromCommandLine(commandLine: String): TerminalCommandLineShape? = GenericCommandLineShapeClassifier.classify(commandLine)

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
                    append(normalizeTerminalCommandToken(executable))
                    append('|')
                    append(subcommands.joinToString("/") { normalizeTerminalCommandToken(it) })
                    append('|')
                    append(optionNames.map(::normalizeTerminalCommandToken).sorted().joinToString(","))
                    append("|p=")
                    append(positionalArgumentCount)
                    append("|ov=")
                    append(optionValueCount)
                }
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
