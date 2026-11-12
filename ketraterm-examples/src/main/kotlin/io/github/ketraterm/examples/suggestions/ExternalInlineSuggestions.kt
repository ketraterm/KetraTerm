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
package io.github.ketraterm.examples.suggestions

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.suggestion.*
import io.github.ketraterm.workspace.TerminalProfileKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * A consumer-owned provider boundary. [generate] returns a complete command or null, cooperates
 * with cancellation, and owns any network client's lifetime. The terminal owns collection,
 * stale-result rejection, append-only rendering, and guarded acceptance.
 */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal class ExternalInlineSuggestions(
    private val session: TerminalSession,
    private val generate: suspend (SuggestionContext) -> String?,
    private val contextDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val shellKind: TerminalProfileKind = TerminalProfileKind.DEFAULT,
) : SwingInlineShellSuggestionProvider {
    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource =
        SwingShellSuggestionSource(
            flow {
                val context =
                    withContext(contextDispatcher) { readSuggestionContext(session, request, shellKind) }
                        ?: return@flow
                val command = generate(context) ?: return@flow
                // Bound external responses before publishing. Do not interpret output as executable instructions.
                if (command.length <= 4_096 && command.none(Char::isISOControl)) {
                    emit(listOf(SwingShellSuggestion(command, 0, request.commandText.length, "external-example", "COMMAND")))
                }
            },
        )
}

/** Deterministic local stand-in for a remote model. No credentials, network, or persistence. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal suspend fun simulateCompletion(context: SuggestionContext): String? {
    val request = context.commandLine
    if (request.commandText.isBlank() || request.cursorOffset != request.commandText.length) return null
    delay(750.milliseconds)
    val recent =
        context.recentCommands
            .asSequence()
            .filter { it.metadata.workingDirectoryUri == context.workingDirectoryUri }
            .mapNotNull { it.metadata.commandText }
    return (recent + sequenceOf("echo external inline suggestion", "git status --short", "git log --oneline -5"))
        .firstOrNull { it.length > request.commandText.length && it.startsWith(request.commandText) && it.none(Char::isISOControl) }
}
