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

/**
 * Spec lookup helper for command-line classification.
 *
 * Keeps alias matching, command-path reconstruction, and inherited option
 * lookup outside the classifier state machine.
 */
internal object CommandSpecResolver {
    fun findSpec(
        specs: List<TerminalCommandSpec>,
        normalizedToken: String,
    ): TerminalCommandSpec? =
        specs.firstOrNull { spec ->
            normalizeTerminalCommandToken(spec.name) == normalizedToken ||
                spec.aliases.any { normalizeTerminalCommandToken(it) == normalizedToken }
        }

    fun optionRequiresSeparateValue(
        optionName: String,
        rootSpec: TerminalCommandSpec,
        subcommands: List<String>,
    ): Boolean {
        val option =
            commandPath(rootSpec, subcommands).asReversed().firstNotNullOfOrNull { spec ->
                spec.options.firstOrNull { option ->
                    option.names.any { normalizeTerminalCommandToken(it) == optionName }
                }
            }
        return option?.requiresValue == true
    }

    private fun commandPath(
        rootSpec: TerminalCommandSpec,
        subcommands: List<String>,
    ): List<TerminalCommandSpec> {
        val path = ArrayList<TerminalCommandSpec>(subcommands.size + 1)
        path += rootSpec
        var current = rootSpec
        for (subcommand in subcommands) {
            val next = findSpec(current.subcommands, subcommand) ?: break
            path += next
            current = next
        }
        return path
    }
}
