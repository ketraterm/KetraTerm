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

import io.github.ketraterm.session.TerminalCommandEditContext
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import javax.swing.SwingUtilities

/** Pane-owned completion work shared by the two independently scheduled presentations. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StandaloneCompletionSuggestions(
    private val provider: SwingShellSuggestionProvider,
    private val session: TerminalSession,
    private val terminal: SwingTerminal,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Swing,
) : SwingShellSuggestionProvider,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private var current: Request? = null
    private var closed = false

    val inlineProvider =
        SwingInlineShellSuggestionProvider { request ->
            val shared = openShared(request, inline = true)
            SwingShellSuggestionSource(shared.results().map { it.take(1) }) { feedback ->
                try {
                    // When inline was the only candidate, its duplicate left no visible popup to
                    // dismiss first. Retire that hidden request rather than resurrecting it on Escape.
                    if (feedback.kind == SwingShellSuggestionFeedbackKind.DISMISSED && !terminal.currentShellSuggestionState().visible) {
                        terminal.hideShellSuggestions()
                    }
                } finally {
                    shared.source.feedbackHandler.onSuggestionFeedback(feedback)
                }
            }
        }

    override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
        val shared = openShared(request, inline = false)
        val suggestions =
            flow {
                var omitted: SwingShellSuggestion? = null
                var browsing = false
                emitAll(
                    shared
                        .results()
                        .flatMapLatest { ranked ->
                            if (ranked.isEmpty()) return@flatMapLatest flowOf(emptyList())
                            terminal.inlineShellSuggestionPresentation.map { visible ->
                                withContext(uiDispatcher) {
                                    // Down hides inline text. Keep the omitted row out for this browsing session
                                    // so that disappearance cannot shift the user's next navigation target.
                                    if (omitted != null && visible == null && terminal.currentShellSuggestionState().selectedIndex >= 0) {
                                        browsing = true
                                    }
                                    if (!browsing) {
                                        omitted = visible?.selectedSuggestion?.takeIf { it === ranked.firstOrNull() }
                                    }
                                    ranked.filterNot { it === omitted }
                                }
                            }
                        }.distinctUntilChanged(),
                )
            }
        return SwingShellSuggestionSource(suggestions, shared.source.feedbackHandler)
    }

    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
        flow {
            val source = withContext(uiDispatcher) { open(request) }
            emitAll(source.suggestions)
        }

    private fun openShared(
        request: SwingShellSuggestionRequest,
        inline: Boolean,
    ): Request {
        check(SwingUtilities.isEventDispatchThread())
        check(!closed)
        val previous = current
        if (previous != null && previous.request == request && previous.canJoin(inline)) {
            previous.claim(inline)
            return previous
        }
        val expected = session.captureCommandEdit()
        val workingDirectory = session.currentWorkingDirectoryUri()
        val source =
            try {
                provider.open(request)
            } catch (failure: Exception) {
                expected?.cancel()
                throw failure
            }
        if (closed) {
            expected?.cancel()
            throw CancellationException("Completion owner closed while opening its source")
        }
        previous?.expected?.cancel()
        return Request(request, expected, workingDirectory, source).also {
            it.claim(inline)
            current = it
        }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        closed = true
        current?.expected?.cancel()
        current = null
        scope.cancel()
    }

    private data class Publication(
        val suggestions: List<SwingShellSuggestion>? = null,
        val complete: Boolean = false,
        val failure: Exception? = null,
    )

    private inner class Request(
        val request: SwingShellSuggestionRequest,
        val expected: TerminalCommandEditContext?,
        val workingDirectory: String?,
        val source: SwingShellSuggestionSource,
    ) {
        private var popupClaimed = false
        private var inlineClaimed = false
        private val lock = Any()
        private val publication = MutableStateFlow(Publication())
        private var job: Job? = null
        private var collectors = 0
        private var cancelled = false

        // One request from each surface may share work. Re-requesting the same surface refreshes it.
        fun canJoin(inline: Boolean): Boolean {
            if (workingDirectory != session.currentWorkingDirectoryUri()) return false
            if (expected == null || !session.isCommandEditCurrent(expected)) return false
            return synchronized(lock) {
                !cancelled &&
                    publication.value.failure == null &&
                    !(if (inline) inlineClaimed else popupClaimed)
            }
        }

        fun claim(inline: Boolean) {
            if (inline) inlineClaimed = true else popupClaimed = true
        }

        fun results(): Flow<List<SwingShellSuggestion>> =
            flow {
                val start =
                    synchronized(lock) {
                        if (cancelled || !scope.isActive) throw CancellationException("Completion request retired")
                        collectors++
                        if (job == null) {
                            scope
                                .launch(start = CoroutineStart.LAZY) {
                                    try {
                                        source.suggestions.collect {
                                            currentCoroutineContext().ensureActive()
                                            publication.value = Publication(it.toList())
                                        }
                                        publication.value = Publication(publication.value.suggestions ?: emptyList(), complete = true)
                                    } catch (failure: Exception) {
                                        publication.value = Publication(complete = true, failure = failure)
                                        if (failure is CancellationException) throw failure
                                    }
                                }.also { job = it }
                        } else {
                            null
                        }
                    }
                start?.start()
                try {
                    var previous: List<SwingShellSuggestion>? = null
                    publication.first { next ->
                        next.failure?.let { throw it }
                        val suggestions = next.suggestions
                        if (suggestions != null && suggestions != previous) {
                            emit(suggestions)
                            previous = suggestions
                        }
                        next.complete
                    }
                } finally {
                    val retire =
                        synchronized(lock) {
                            collectors--
                            if (collectors == 0 && !publication.value.complete) {
                                cancelled = true
                                expected?.cancel()
                                job
                            } else {
                                null
                            }
                        }
                    retire?.cancel()
                }
            }
    }
}
