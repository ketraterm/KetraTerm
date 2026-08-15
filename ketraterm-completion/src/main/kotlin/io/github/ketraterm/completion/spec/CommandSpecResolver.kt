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
package io.github.ketraterm.completion.spec

import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec

/**
 * Spec lookup helper for command-line classification.
 *
 * Keeps alias matching, command-path reconstruction, and inherited option
 * lookup outside the classifier state machine.
 */
internal fun findCommandSpec(
    specs: List<TerminalCommandSpec>,
    normalizedToken: String,
): TerminalCommandSpec? =
    specs.firstOrNull { spec ->
        normalizeTerminalCommandToken(spec.name) == normalizedToken ||
            spec.aliases.any { normalizeTerminalCommandToken(it) == normalizedToken }
    }

/** Finds an option on the current command or an inherited parent command. */
internal fun findOptionSpec(
    commandPath: List<TerminalCommandSpec>,
    token: String,
): TerminalOptionSpec? {
    val normalized = normalizeTerminalCommandToken(token.substringBefore(OPTION_VALUE_SEPARATOR))
    return commandPath.asReversed().firstNotNullOfOrNull { command ->
        command.options.firstOrNull { option ->
            option.names.any { normalizeTerminalCommandToken(it) == normalized }
        }
    }
}

/** Finds a direct or repeatable subcommand from the current command path. */
internal fun findNextCommandSpec(
    commandPath: List<TerminalCommandSpec>,
    normalizedToken: String,
): TerminalCommandSpec? {
    val current = commandPath.last()
    return findCommandSpec(current.subcommands, normalizedToken)
        ?: commandPath
            .asReversed()
            .firstOrNull { it.repeatableSubcommands }
            ?.let { findCommandSpec(it.subcommands, normalizedToken) }
}

private const val OPTION_VALUE_SEPARATOR = '='
