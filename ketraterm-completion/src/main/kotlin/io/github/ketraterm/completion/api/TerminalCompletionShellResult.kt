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

/**
 * One live shell answer, including the identity used to interpret catalog options.
 *
 * @param candidates shell-escaped edits for the exact requested buffer; copied into an immutable list.
 *   An empty answer is authoritative at shell-owned positions.
 * @property externalCommandName resolved application name after following aliases, for example `git.exe`.
 *   Null denotes a shell command, function, script, or unresolved invocation. The engine uses this
 *   identity to retain catalog options only for a compatible external command when the shell returns
 *   generic paths or no candidates. This metadata must never be executed.
 */
@ExperimentalTerminalCompletionApi
public class TerminalCompletionShellResult
    @JvmOverloads
    constructor(
        candidates: List<TerminalCompletionCandidate>,
        public val externalCommandName: String? = null,
    ) {
        /** Immutable snapshot of the supplied shell candidates. */
        public val candidates: List<TerminalCompletionCandidate> = java.util.List.copyOf(candidates)
    }
