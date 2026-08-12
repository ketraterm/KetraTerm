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
 * implementations consume it directly and must not tokenize the command line
 * again.
 */
class TerminalCompletionContext
    internal constructor(
        internal val commandLineContext: TerminalCommandLineContext,
        /** Semantic position of the active token. */
        val activePosition: TerminalCompletionActivePosition,
        /** Token index of the executable. */
        val commandTokenIndex: Int = 0,
        /** Matched root command, or `null` for unknown commands. */
        val command: TerminalCommandSpec? = null,
        /** Matched root-to-leaf command path. */
        val commandPath: List<TerminalCommandSpec> = emptyList(),
        /** Option whose value is active, or `null`. */
        val activeOption: TerminalOptionSpec? = null,
        /** Positional argument specification at the cursor, or `null`. */
        val activePositionalArgument: TerminalArgumentSpec? = null,
        /** Exclusive option groups already used before the cursor. */
        val usedOptionExclusiveGroupIds: Set<String> = emptySet(),
        /** Whether `--` terminated option parsing before the cursor. */
        val optionsTerminated: Boolean = false,
        /** Expected path kind at the cursor. */
        val expectedPathKind: TerminalPathArgumentKind = TerminalPathArgumentKind.NONE,
        /** Hidden-path policy at the cursor. */
        val expectedHiddenPathPolicy: TerminalHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
        /** Dynamic value domain expected at the cursor. */
        val expectedValueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        /** Command whose subcommands are eligible at the cursor. */
        val subcommandCandidateSource: TerminalCommandSpec? = null,
        /** Static option or positional values eligible at the cursor. */
        val staticValueCandidates: List<String> = emptyList(),
        /** Active token quote, or the null character when unquoted. */
        val activeTokenQuote: Char = NO_QUOTE,
        internal val attachedOptionValue: AttachedOptionValue? = null,
    ) {
        /** Decoded prefix that candidates must match. */
        val activePrefix: String get() = attachedOptionValue?.prefix ?: commandLineContext.activePrefix

        /** Inclusive replacement start offset in the command line. */
        val replacementStartOffset: Int
            get() = attachedOptionValue?.replacementStartOffset ?: commandLineContext.replacementStartOffset

        /** Exclusive replacement end offset in the command line. */
        val replacementEndOffset: Int get() = commandLineContext.replacementEndOffset

        /** Deepest matched command specification. */
        val currentCommand: TerminalCommandSpec? get() = commandPath.lastOrNull()

        private companion object {
            private const val NO_QUOTE = '\u0000'
        }
    }
