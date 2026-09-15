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
package io.github.ketraterm.workspace

/**
 * Host-selected environment applied at process launch and again after supported
 * interactive shells finish their startup files. This lets a product select a
 * project toolchain even when shell startup files select a different default.
 *
 * With disabled or unavailable shell integration, only the initial process
 * environment is changed. Values are passed as data, never evaluated as shell code.
 *
 * @property variables exported shell variables overriding both inherited values
 * and explicit [TerminalProfile.environment] entries.
 * @property pathPrefix one native directory prepended to PATH, or `null` for no
 * addition. It is applied after [variables], using the shell's path syntax.
 */
data class TerminalShellEnvironment(
    val variables: Map<String, String> = emptyMap(),
    val pathPrefix: String? = null,
) {
    init {
        for ((name, value) in variables) {
            require(
                name.isNotEmpty() &&
                    (name[0] == '_' || name[0].isLetter()) &&
                    name.all { it == '_' || it.isLetterOrDigit() } &&
                    name.all { it.code < 128 },
            ) {
                "shell environment variable names must be ASCII shell identifiers"
            }
            require(!name.startsWith("_KetraTerm_")) { "shell environment variable name is reserved" }
            require('\u0000' !in value) { "shell environment values must not contain NUL" }
        }
        require(pathPrefix == null || (pathPrefix.isNotEmpty() && '\u0000' !in pathPrefix)) {
            "shell PATH prefix must be a nonempty directory without NUL"
        }
    }

    companion object {
        /** Shared empty environment for ordinary launch profiles. */
        val Empty: TerminalShellEnvironment = TerminalShellEnvironment()
    }
}
