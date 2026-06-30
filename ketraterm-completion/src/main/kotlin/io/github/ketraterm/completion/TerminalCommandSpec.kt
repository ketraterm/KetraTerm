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

/**
 * Declarative command specification consumed by the in-process completion
 * engine.
 *
 * This is KetraTerm's stable internal model. External formats, including
 * Fig-style specs, should be imported into this representation instead of being
 * exposed to UI modules.
 *
 * @property name canonical command or subcommand token.
 * @property description short human-readable description.
 * @property aliases additional tokens that resolve to this command.
 * @property subcommands nested subcommands available after this command.
 * @property options options available at this command level.
 */
data class TerminalCommandSpec
    @JvmOverloads
    constructor(
        val name: String,
        val description: String = "",
        val aliases: List<String> = emptyList(),
        val subcommands: List<TerminalCommandSpec> = emptyList(),
        val options: List<TerminalOptionSpec> = emptyList(),
    ) {
        init {
            require(name.isNotBlank()) { "name must not be blank" }
            require(aliases.none(String::isBlank)) { "aliases must not contain blank values" }
        }
    }

/**
 * Declarative option or flag specification.
 *
 * @property names accepted option tokens, such as `--help` and `-h`.
 * @property description short human-readable description.
 * @property requiresValue whether this option consumes the following token as a
 * value.
 */
data class TerminalOptionSpec
    @JvmOverloads
    constructor(
        val names: List<String>,
        val description: String = "",
        val requiresValue: Boolean = false,
    ) {
        init {
            require(names.isNotEmpty()) { "names must not be empty" }
            require(names.none(String::isBlank)) { "names must not contain blank values" }
        }
    }
