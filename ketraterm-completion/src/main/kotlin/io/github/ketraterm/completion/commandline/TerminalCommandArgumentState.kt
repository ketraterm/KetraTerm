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
package io.github.ketraterm.completion.commandline

internal enum class TerminalCommandArgumentKind {
    POSITIONAL,
    OPTION_VALUE,
    OPTION_TERMINATED_POSITIONAL,
}

/**
 * Semantic state for one completed command argument.
 *
 * Raw positional values are deliberately absent. Completion context needs the argument category and, for option
 * values, the option name whose value is being completed; retaining arbitrary values would expose command content to
 * unrelated completion internals.
 */
internal data class TerminalCommandArgumentState(
    val kind: TerminalCommandArgumentKind,
    val optionName: String? = null,
)
