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
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.model.*

/** Semantic position of the active completion token. */
enum class TerminalCompletionActivePosition {
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
class TerminalCompletionContext
    internal constructor(
        internal val commandLineContext: TerminalCommandLineContext,
        val activePosition: TerminalCompletionActivePosition,
        val commandTokenIndex: Int = 0,
        val command: TerminalCommandSpec? = null,
        val commandPath: List<TerminalCommandSpec> = emptyList(),
        val activeOption: TerminalOptionSpec? = null,
        val activePositionalArgument: TerminalArgumentSpec? = null,
        val usedOptionExclusiveGroupIds: Set<String> = emptySet(),
        val optionsTerminated: Boolean = false,
        val expectedPathKind: TerminalPathArgumentKind = TerminalPathArgumentKind.NONE,
        val expectedHiddenPathPolicy: TerminalHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
        val expectedValueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        val subcommandCandidateSource: TerminalCommandSpec? = null,
        val staticValueCandidates: List<String> = emptyList(),
        val activeTokenQuote: Char = NO_QUOTE,
        internal val attachedOptionValue: AttachedOptionValue? = null,
    ) {
        val activePrefix: String get() = attachedOptionValue?.prefix ?: commandLineContext.activePrefix

        val replacementStartOffset: Int
            get() = attachedOptionValue?.replacementStartOffset ?: commandLineContext.replacementStartOffset

        val replacementEndOffset: Int get() = commandLineContext.replacementEndOffset

        val currentCommand: TerminalCommandSpec? get() = commandPath.lastOrNull()

        private companion object {
            private const val NO_QUOTE = '\u0000'
        }
    }
