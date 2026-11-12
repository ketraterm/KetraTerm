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

import io.github.ketraterm.session.TerminalCommandOutputText
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.workspace.TerminalProfileKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Detached input for an external provider. Unknown output stays null, with truncation flags intact. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal data class SuggestionContext(
    val commandLine: SwingShellSuggestionRequest,
    val workingDirectoryUri: String?,
    val recentCommands: List<CompletedCommandContext>,
    val shellKind: TerminalProfileKind,
)

internal data class CompletedCommandContext(
    val metadata: TerminalShellIntegrationCommandMetadata,
    val output: TerminalCommandOutputText?,
)

/**
 * Reads at most five completed commands, visiting at most 32 records. Each output is limited to
 * 2,048 UTF-16 units and 32 physical rows. Call on an I/O dispatcher: output reads serialize with
 * terminal mutation. A changed metadata revision discards the sample instead of mixing snapshots.
 */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal suspend fun readSuggestionContext(
    session: TerminalSession,
    request: SwingShellSuggestionRequest,
    shellKind: TerminalProfileKind = TerminalProfileKind.DEFAULT,
): SuggestionContext? {
    val history = session.shellIntegrationState
    val revision = history.revision.value
    val cwd = history.currentWorkingDirectoryUri()
    val completed = ArrayList<CompletedCommandContext>(5)
    var record = history.latestCommandRecordId()
    var visited = 0
    while (record != 0 && completed.size < 5 && visited++ < 32) {
        currentCoroutineContext().ensureActive()
        val metadata = history.commandMetadata(record)
        if (metadata?.finishedAtEpochMillis != null) {
            completed += CompletedCommandContext(metadata, session.commandOutputText(record, maxChars = 2_048, maxRows = 32))
        }
        record = history.previousCommandRecordId(record)
    }
    return if (revision == history.revision.value) SuggestionContext(request, cwd, completed, shellKind) else null
}
