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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalPathArgumentKind
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCompletionKind

/**
 * Adapts a session's installed native shell completer to the completion engine.
 * The session owns request admission, correlation, cancellation, and stale-edit validation.
 * Native replacement text is already escaped and is preserved verbatim.
 */
@ExperimentalTerminalCompletionApi
public class SessionShellCompletionProvider(
    private val session: TerminalSession,
) : TerminalCompletionShellProvider {
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate>? {
        require(limit > 0)
        val syntax = request.shellCapabilities.syntax
        if (syntax == TerminalShellSyntax.POSIX) {
            if (context.activePosition != TerminalCompletionActivePosition.COMMAND || '/' in context.activePrefix) return null
            val commands = session.shellCompletionCommands.value ?: return null
            return commands
                .asSequence()
                .filter { it.displayText.startsWith(context.activePrefix) }
                .take(limit)
                .map { item ->
                    TerminalCompletionCandidate(
                        replacementText = item.replacementText,
                        replacementStartOffset = context.replacementStartOffset,
                        replacementEndOffset = context.replacementEndOffset,
                        displayText = item.displayText,
                        source = "bash",
                        kind = TerminalCompletionCandidateKind.COMMAND,
                    )
                }.toList()
        }
        if (syntax != TerminalShellSyntax.POWERSHELL) return null
        if (!session.shellCompletionAvailable) return null
        val result = session.requestShellCompletions(request.commandLine, request.cursorOffset) ?: return emptyList()
        return result.candidates
            .asSequence()
            .filter { item ->
                item.replacementText.isNotEmpty() &&
                    item.displayText.isNotEmpty() &&
                    (context.expectedPathKind != TerminalPathArgumentKind.DIRECTORY || item.kind != TerminalShellCompletionKind.FILE)
            }.take(limit)
            .mapIndexed { index, item ->
                TerminalCompletionCandidate(
                    replacementText = item.replacementText,
                    replacementStartOffset = result.replacementStartOffset,
                    replacementEndOffset = result.replacementEndOffset,
                    displayText = item.displayText,
                    detail = item.description,
                    source = "powershell",
                    kind =
                        when (item.kind) {
                            TerminalShellCompletionKind.COMMAND ->
                                if (context.activePosition == TerminalCompletionActivePosition.COMMAND) {
                                    TerminalCompletionCandidateKind.COMMAND
                                } else {
                                    TerminalCompletionCandidateKind.ARGUMENT
                                }
                            TerminalShellCompletionKind.OPTION -> TerminalCompletionCandidateKind.OPTION
                            TerminalShellCompletionKind.FILE, TerminalShellCompletionKind.DIRECTORY -> TerminalCompletionCandidateKind.PATH
                            TerminalShellCompletionKind.ARGUMENT -> TerminalCompletionCandidateKind.ARGUMENT
                        },
                    score = result.candidates.size - index,
                    valueDomain = context.expectedValueDomain,
                )
            }.toList()
    }
}
