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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import javax.swing.SwingUtilities

/** Component-owned debounce and eligibility state; the component owns session binding and request capture. */
@OptIn(FlowPreview::class)
internal class SwingShellSuggestionScheduler(
    private val observationScope: CoroutineScope,
    private val edtDispatcher: CoroutineDispatcher,
    private val isFocused: () -> Boolean,
    private val isEligible: () -> Boolean,
    private val requestSuggestions: (TerminalShellCommandLineSnapshot, SwingShellSuggestionFeedbackHandler) -> Unit,
    private val hideSuggestions: () -> Unit,
    private val debounceMillis: Int = 75,
    private val minimumNonWhitespaceCharacters: Int = 2,
) {
    private var observationJob: Job? = null
    private var activeCommandLine: (() -> TerminalShellCommandLineSnapshot?)? = null
    private var shellCommandLineRevisions: StateFlow<Long>? = null
    private var invalidatedRevision: Long? = null
    private var invalidatedSnapshot: TerminalShellCommandLineSnapshot? = null
    private val refreshes = MutableStateFlow<Long?>(null)
    private var refreshSequence = 0L
    private var lifecycleRevision = 0L
    private var lastRequest: TerminalShellCommandLineSnapshot? = null

    val feedbackHandler =
        SwingShellSuggestionFeedbackHandler {
            checkEdt()
            lastRequest = null
        }

    fun start(session: TerminalSession) {
        checkEdt()
        if (session.isClosed) {
            stop()
            return
        }
        start(session::activeShellCommandLine, session.activeShellCommandLineRevision)
    }

    internal fun start(
        activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
        shellCommandLineRevisions: StateFlow<Long>,
    ) {
        checkEdt()
        if (observationJob?.isActive == true && this.shellCommandLineRevisions === shellCommandLineRevisions) return
        stop()
        val revision = lifecycleRevision
        this.activeCommandLine = activeCommandLine
        this.shellCommandLineRevisions = shellCommandLineRevisions
        val observation =
            observationScope.launch(edtDispatcher + CoroutineName("swing-shell-suggestions"), start = CoroutineStart.LAZY) {
                try {
                    coroutineScope {
                        var initialRevisionObserved = false
                        launch {
                            refreshes
                                .debounce { if (it == null) 0L else debounceMillis.toLong() }
                                .collect { sequence ->
                                    if (lifecycleRevision == revision && sequence != null && sequence == refreshes.value) refreshNow()
                                }
                        }
                        shellCommandLineRevisions.collect { commandRevision ->
                            if (lifecycleRevision != revision || commandRevision < 0L) return@collect
                            val initialRevision = !initialRevisionObserved
                            initialRevisionObserved = true
                            val invalidation = invalidatedRevision
                            if (commandRevision == invalidation) return@collect
                            if (initialRevision && invalidation != null && invalidation < 0L) {
                                val current = activeCommandLine()
                                if (lifecycleRevision != revision) return@collect
                                if (current == invalidatedSnapshot) {
                                    invalidatedRevision = commandRevision
                                    return@collect
                                }
                            }
                            invalidatedRevision = null
                            invalidatedSnapshot = null
                            if (!initialRevision) cancelAndHide()
                            if (lifecycleRevision == revision) scheduleRefresh()
                        }
                    }
                } finally {
                    if (lifecycleRevision == revision) stop()
                }
            }
        observationJob = observation
        observation.start()
        if (lifecycleRevision == revision) {
            if (observation.isActive) scheduleRefresh() else stop()
        }
    }

    /** Releases session references and delayed work without hiding an independently owned explicit request. */
    fun stop() {
        checkEdt()
        lifecycleRevision++
        val observation = observationJob
        observationJob = null
        activeCommandLine = null
        shellCommandLineRevisions = null
        refreshes.value = null
        invalidatedRevision = null
        invalidatedSnapshot = null
        lastRequest = null
        observation?.cancel()
    }

    /** A changed host context or provider makes equal command text eligible for a fresh request. */
    fun refresh() {
        checkEdt()
        lastRequest = null
        scheduleRefresh()
    }

    fun onFocusGained() {
        checkEdt()
        scheduleRefresh()
    }

    fun onFocusLost() {
        checkEdt()
        if (observationJob != null) cancelAndHide()
    }

    fun onInvalidated() {
        checkEdt()
        val revisions = shellCommandLineRevisions ?: return
        val revision = lifecycleRevision
        val invalidation = revisions.value
        val snapshot = if (invalidation < 0L) activeCommandLine?.invoke() else null
        if (lifecycleRevision != revision) return
        invalidatedRevision = invalidation
        invalidatedSnapshot = snapshot
        cancelAndHide()
    }

    fun onEligibilityChanged(eligible: Boolean) {
        checkEdt()
        if (observationJob == null) return
        if (eligible && invalidatedRevision == null && isEligibleNow()) {
            lastRequest = null
            scheduleRefresh()
        } else {
            cancelAndHide()
        }
    }

    internal fun refreshNow() {
        checkEdt()
        if (observationJob == null) return
        if (invalidatedRevision != null || !isEligibleNow()) {
            cancelAndHide()
            return
        }
        val revision = lifecycleRevision
        val snapshot = activeCommandLine?.invoke()
        if (lifecycleRevision != revision) return
        if (snapshot == null || !shouldRequest(snapshot)) {
            cancelAndHide()
            return
        }
        if (snapshot == lastRequest) return
        lastRequest = snapshot
        requestSuggestions(snapshot, feedbackHandler)
    }

    private fun scheduleRefresh() {
        if (observationJob?.isActive == true && invalidatedRevision == null && isEligibleNow()) {
            refreshes.value = ++refreshSequence
        }
    }

    private fun cancelAndHide() {
        refreshes.value = null
        lastRequest = null
        hideSuggestions()
    }

    private fun isEligibleNow(): Boolean = isFocused() && isEligible()

    private fun shouldRequest(snapshot: TerminalShellCommandLineSnapshot): Boolean {
        val text = snapshot.commandText
        val cursorOffset = snapshot.cursorOffset
        var nonWhitespaceCharacters = 0
        for (index in 0 until cursorOffset) {
            if (!text[index].isWhitespace()) nonWhitespaceCharacters++
        }
        if (nonWhitespaceCharacters == 0) return false
        if (nonWhitespaceCharacters >= minimumNonWhitespaceCharacters) return true
        return when (text.getOrNull(cursorOffset - 1)) {
            '-', '/', '\\', '$', '=' -> true
            ' ' -> nonWhitespaceCharacters >= 2 && text.getOrNull(cursorOffset - 2)?.isWhitespace() == false
            else -> false
        }
    }

    private fun checkEdt() = check(SwingUtilities.isEventDispatchThread()) { "shell suggestion scheduling must run on the EDT" }
}
