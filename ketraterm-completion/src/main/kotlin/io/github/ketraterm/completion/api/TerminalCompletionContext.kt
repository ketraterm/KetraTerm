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

import io.github.ketraterm.completion.commandline.AttachedOptionValue
import io.github.ketraterm.completion.commandline.ResolvedOptionValue
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.model.*
import java.util.Collections.unmodifiableList
import java.util.List.copyOf

/** Semantic position of the active completion token. */
public enum class TerminalCompletionActivePosition {
    /** Cursor is on a shell operator rather than a command token. */
    OPERATOR,

    /** Cursor is completing the executable. */
    COMMAND,

    /** Cursor is completing a known subcommand. */
    SUBCOMMAND,

    /** Cursor is completing an option name. */
    OPTION_NAME,

    /** Cursor is completing an option value. */
    OPTION_VALUE,

    /** Cursor is completing a positional argument. */
    POSITIONAL_ARGUMENT,
}

/**
 * One parsed, spec-resolved completion context shared by every source.
 *
 * The merged engine constructs this object once per request. Source
 * implementations consume it directly to determine eligibility, prefix matching,
 * replacement ranges, and semantic argument kinds without re-tokenizing the command line.
 *
 * @property activePosition semantic position of the active completion token (e.g. COMMAND, SUBCOMMAND, OPTION_NAME, OPTION_VALUE, POSITIONAL_ARGUMENT, OPERATOR).
 * @property commandTokenIndex token index of the executable in the active command segment.
 * @property command matched root command specification, or `null` for unknown commands.
 * @property commandPath matched root-to-leaf command specification path reflecting the active subcommand hierarchy.
 * @property activeOption specification of the option whose value is being completed, or `null` when not completing an option value.
 * @property activePositionalArgument positional argument specification active at the cursor, or `null` if unspecified or variadic limit reached.
 * @property usedOptionExclusiveGroupIds identifiers of exclusive option groups already supplied before the cursor.
 * @property optionsTerminated whether a `--` token terminated option parsing before the cursor.
 * @property expectedPathKind file-system path kind expected at the cursor (e.g. FILE, DIRECTORY, FILE_OR_DIRECTORY, or NONE).
 * @property expectedHiddenPathPolicy hidden-entry policy expected at the cursor.
 * @property expectedValueDomain dynamic host-provided value domain expected at the cursor (e.g. GIT_BRANCH, ENVIRONMENT_VARIABLE, or NONE).
 * @property subcommandCandidateSource command specification whose subcommands are eligible for completion at the cursor.
 * @property staticValueCandidates static candidate values declared by the active option or positional argument specification.
 * @property activeTokenQuote quote character enclosing the active token (`'` or `"`), or the null character `\u0000` when unquoted.
 * @property activePrefix decoded prefix text that candidates must match, taking attached option value prefixes (`--key=val`) into account.
 * @property replacementStartOffset inclusive UTF-16 replacement start offset in the original request command line.
 * @property replacementEndOffset exclusive UTF-16 replacement end offset in the original request command line.
 * @property currentCommand deepest matched command specification in [commandPath], or `null` when no command spec was matched.
 */
public class TerminalCompletionContext
    internal constructor(
        internal val commandLineContext: TerminalCommandLineContext,
        public val activePosition: TerminalCompletionActivePosition,
        public val commandTokenIndex: Int = 0,
        public val command: TerminalCommandSpec? = null,
        public val commandPath: List<TerminalCommandSpec> = emptyList(),
        public val activeOption: TerminalOptionSpec? = null,
        public val activePositionalArgument: TerminalArgumentSpec? = null,
        public val usedOptionExclusiveGroupIds: Set<String> = emptySet(),
        public val optionsTerminated: Boolean = false,
        public val expectedPathKind: TerminalPathArgumentKind = TerminalPathArgumentKind.NONE,
        public val expectedHiddenPathPolicy: TerminalHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
        public val expectedValueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        public val subcommandCandidateSource: TerminalCommandSpec? = null,
        public val staticValueCandidates: List<String> = emptyList(),
        public val activeTokenQuote: Char = NO_QUOTE,
        internal val attachedOptionValue: AttachedOptionValue? = null,
        optionValuesBeforeCursor: List<ResolvedOptionValue> = emptyList(),
        precedingPositionalArguments: List<String> = emptyList(),
    ) {
        private val optionValuesBeforeCursor = copyOf(optionValuesBeforeCursor)

        /**
         * Decoded words after the executable and strictly before the active token,
         * in input order. Includes subcommands, option names, values, and `--`.
         * Leading environment assignments and the executable are excluded.
         * Available even without a command spec; empty at command/operator positions.
         *
         * Only the cursor's command segment is included, according to the request's
         * shell syntax. Quotes and escapes follow the shared tokenizer; empty quoted
         * words remain empty strings. No variable, glob, or command expansion is performed.
         * The active word (even at its end) and all later words are excluded.
         * This immutable, request-owned list may be retained across suspension or later
         * requests; its size is bounded by the words in the parsed prefix.
         */
        public val precedingArguments: List<String> = precedingArguments()

        /**
         * Decoded preceding positional values in input order, using the matched
         * command spec's analysis. Excludes resolved subcommands, option names,
         * option values, and the option terminator. After `--`, words are positional.
         * Empty when the command is unknown; use [precedingArguments] in that case.
         * Unknown options have no inferred value ownership. The immutable list has
         * the same segment, active-word exclusion, and lifetime as [precedingArguments].
         */
        public val precedingPositionalArguments: List<String> = copyOf(precedingPositionalArguments)

        /**
         * Returns every completed value for a known option in input order, including
         * repeats and empty values. [optionName] may be any declared alias; matching
         * follows command-spec lookup (trimmed and case-insensitive). Separate values
         * and `--name=value` use the same resolved option and decoded value semantics.
         *
         * Only occurrences resolved by the spec at their position are included,
         * including inherited options. Unknown options, valueless flags, pending
         * values, and the active word are excluded. No last-value-wins policy is imposed;
         * callers can select the first, last, or all occurrences. The immutable result
         * has the same segment and lifetime as [precedingArguments].
         *
         * @param optionName declared option name or alias.
         * @return decoded values, or an empty list when no matching value precedes the cursor.
         */
        public fun precedingOptionValues(optionName: String): List<String> {
            val normalizedName = normalizeTerminalCommandToken(optionName)
            var result: ArrayList<String>? = null
            for (entry in optionValuesBeforeCursor) {
                if (entry.option.names.any { normalizeTerminalCommandToken(it) == normalizedName }) {
                    val values = result ?: ArrayList<String>().also { result = it }
                    values += entry.value
                }
            }
            return result?.let(::unmodifiableList) ?: emptyList()
        }

        public val activePrefix: String get() = attachedOptionValue?.prefix ?: commandLineContext.activePrefix

        public val replacementStartOffset: Int
            get() = attachedOptionValue?.replacementStartOffset ?: commandLineContext.replacementStartOffset

        public val replacementEndOffset: Int get() = commandLineContext.replacementEndOffset

        public val currentCommand: TerminalCommandSpec? get() = commandPath.lastOrNull()

        private fun precedingArguments(): List<String> {
            val end = minOf(commandLineContext.activeTokenIndex, commandLineContext.tokens.size)
            val start = commandTokenIndex + 1
            if (start >= end) return emptyList()
            val result = ArrayList<String>(end - start)
            for (index in start until end) result += commandLineContext.tokens[index].text
            return unmodifiableList(result)
        }

        private companion object {
            private const val NO_QUOTE = '\u0000'
        }
    }
