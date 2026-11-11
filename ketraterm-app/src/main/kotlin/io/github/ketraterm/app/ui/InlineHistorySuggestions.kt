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
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.swing.Swing
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import kotlin.time.Duration.Companion.milliseconds

/** Pane-owned policy: replay recent commands from this session without another history store. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal class InlineHistorySuggestions(
    private val terminal: SwingTerminal,
    private val session: TerminalSession,
    private val enabled: () -> Boolean,
    edtDispatcher: CoroutineDispatcher = Dispatchers.Swing,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + edtDispatcher)
    private var observation: Job? = null
    private var pending: Job? = null
    private var interaction: SwingShellSuggestionInteraction? = null
    private var lastCommand: String? = null
    private var dismissed = false
    private var closed = false
    private val focusListener =
        object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = schedule()
        }
    private val provider =
        SwingInlineShellSuggestionProvider { context ->
            val cwd = session.shellIntegrationState.currentWorkingDirectoryUri()
            SwingShellSuggestionSource(
                flow {
                    val prefix = context.selectedPopupCommandText ?: context.commandLine.commandText
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
                            emit(listOf(SwingShellSuggestion(text, 0, context.commandLine.commandText.length, "history", "COMMAND")))
                            return@flow
                        }
                        record = history.previousCommandRecordId(record)
                    }
                    emit(emptyList())
                },
            )
        }

    init {
        terminal.addFocusListener(focusListener)
        reloadSettings()
    }

    fun reloadSettings() {
        if (closed) return
        if (!enabled()) {
            observation?.cancel()
            observation = null
            pending?.cancel()
            pending = null
            interaction?.close()
            interaction = null
        } else if (observation == null) {
            observation = scope.launch { session.commandEditChanges.collect { schedule() } }
            schedule()
        }
    }

    private fun schedule() {
        if (closed || !enabled()) return
        val command = session.activeShellCommandLine()?.commandText
        if (command != lastCommand) {
            lastCommand = command
            dismissed = false
        }
        pending?.cancel()
        pending =
            scope.launch {
                delay(REQUEST_DELAY_MILLIS.milliseconds)
                val line = session.activeShellCommandLine() ?: return@launch
                if (line.commandText.isBlank() || dismissed || interaction?.isActive == true) return@launch
                val next =
                    terminal.beginInlineShellSuggestionInteraction { feedback ->
                        if (feedback.kind == SwingShellSuggestionFeedbackKind.DISMISSED) dismissed = true
                    } ?: return@launch
                interaction = next
                terminal.requestInlineShellSuggestions(next, provider)
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        terminal.removeFocusListener(focusListener)
        scope.cancel()
        interaction?.close()
        interaction = null
    }

    private companion object {
        const val REQUEST_DELAY_MILLIS = 150L
        const val MAX_HISTORY_RECORDS = 200
    }
}
