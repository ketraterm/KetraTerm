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
package io.github.ketraterm.app.ui

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow

/** Bounded pane history source. The terminal owns scheduling, dismissal, and request lifetime. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal class InlineHistorySuggestions(
    private val session: TerminalSession,
) : SwingInlineShellSuggestionProvider {
    override fun open(request: SwingInlineShellSuggestionRequest): SwingShellSuggestionSource {
        val cwd = session.shellIntegrationState.currentWorkingDirectoryUri()
        return SwingShellSuggestionSource(
            flow {
                val prefix = request.selectedPopupCommandText ?: request.commandLine.commandText
                val history = session.shellIntegrationState
                var record = history.latestCommandRecordId()
                var remaining = MAX_HISTORY_RECORDS
                while (record != 0 && remaining-- > 0) {
                    currentCoroutineContext().ensureActive()
                    val metadata = history.commandMetadata(record)
                    val text = metadata?.commandText
                    if (metadata?.finishedAtEpochMillis != null &&
                        (cwd == null || metadata.workingDirectoryUri == cwd) &&
                        text != null &&
                        text.length > prefix.length &&
                        text.startsWith(prefix) &&
                        text.none(Char::isISOControl)
                    ) {
                        emit(listOf(SwingShellSuggestion(text, 0, request.commandLine.commandText.length, "history", "COMMAND")))
                        return@flow
                    }
                    record = history.previousCommandRecordId(record)
                }
                emit(emptyList())
            },
        )
    }

    private companion object {
        const val MAX_HISTORY_RECORDS = 200
    }
}
