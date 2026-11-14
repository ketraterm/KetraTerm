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
package io.github.ketraterm.session

/** Semantic role reported by a native shell completer. */
public enum class TerminalShellCompletionKind { COMMAND, OPTION, FILE, DIRECTORY, ARGUMENT }

/**
 * One native candidate. [replacementText] is already escaped by the shell.
 * [displayText] and [description] are untrusted presentation text; consumers must
 * sanitize controls for their display context while preserving supported formatting.
 * [aliasExpansion] is a literal shell alias definition for a command inventory entry;
 * null means not an alias, while empty means an empty or unavailable definition.
 * Consumers must never execute this metadata.
 */
public data class TerminalShellCompletionCandidate
    @JvmOverloads
    constructor(
        val replacementText: String,
        val displayText: String,
        val description: String,
        val kind: TerminalShellCompletionKind,
        val aliasExpansion: String? = null,
    )

/**
 * Native shell completion for one exact command buffer and UTF-16 cursor.
 * Replacement bounds are inclusive at the start and exclusive at the end.
 * The producer must validate bounds, Unicode, text sizes, and controls in edit/context
 * fields before publication. Candidate display text and descriptions remain presentation data.
 * [candidates] is copied at construction and cannot be mutated.
 * [externalCommandName] identifies the resolved application after following aliases, or is null
 * for shell commands, functions, scripts, unresolved invocations, or command-name completion.
 * It is argument-completion metadata, never code.
 */
public class TerminalShellCompletionResult
    @JvmOverloads
    constructor(
        public val commandLine: String,
        public val cursorOffset: Int,
        public val replacementStartOffset: Int,
        public val replacementEndOffset: Int,
        candidates: List<TerminalShellCompletionCandidate>,
        public val externalCommandName: String? = null,
    ) {
        public val candidates: List<TerminalShellCompletionCandidate> = java.util.List.copyOf(candidates)
    }
