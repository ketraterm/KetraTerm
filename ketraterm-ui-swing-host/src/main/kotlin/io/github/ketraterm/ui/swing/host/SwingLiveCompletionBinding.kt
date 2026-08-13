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

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionInvalidationListener
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.FocusListener

/**
 * Binds host-neutral live-completion behavior to one Swing terminal.
 *
 * The binding observes render publications, applies the shared debounce policy,
 * adapts shell snapshots to Swing requests, hides suggestions when focus leaves
 * the terminal, and invalidates request deduplication after user feedback. It
 * owns only its observation job; completion providers, enablement, ranking
 * context, and feedback persistence remain host supplied.
 *
 * Construct the binding before the terminal so [suggestionFeedbackHandler] can
 * be passed through [io.github.ketraterm.ui.swing.api.SwingHostServices]. After
 * binding the terminal to the session, call [attach]. Call [close] from the EDT
 * before disposing the terminal.
 */
class SwingLiveCompletionBinding
    internal constructor(
        private val activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
        private val renderGenerations: Flow<Long>,
        suggestionsEnabled: () -> Boolean,
        rankingContextKey: () -> String?,
        feedbackHandler: SwingShellSuggestionFeedbackHandler,
        scheduler: SwingLiveCompletionScheduler,
        private val observationScope: CoroutineScope,
    ) : AutoCloseable {
        private var target: SwingLiveCompletionTarget? = null
        private var observationJob: Job? = null
        private var invalidatedCommand: CommandSnapshotIdentity? = null
        private var awaitingSnapshotChange = false
        private val lifecycleLock = Any()

        @Volatile
        private var closed = false

        private val triggerController =
            SwingLiveCompletionTriggerController(
                activeCommandLine = activeCommandLine,
                requestSuggestions = { snapshot -> target?.requestSuggestions(snapshot) },
                hideSuggestions = { target?.hideSuggestions() },
                rankingContextKey = rankingContextKey,
                suggestionsEnabled = { !closed && target?.isFocusOwner() == true && suggestionsEnabled() },
                scheduler = scheduler,
                debounceMillis = DEFAULT_DEBOUNCE_MILLIS,
                minimumNonWhitespaceCharacters = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
            )

        private val focusListener =
            object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    scheduleRefresh()
                }

                override fun focusLost(event: FocusEvent) {
                    triggerController.cancelAndHide()
                }
            }

        private val invalidationListener =
            SwingShellSuggestionInvalidationListener {
                synchronized(lifecycleLock) {
                    if (closed) return@SwingShellSuggestionInvalidationListener
                    invalidatedCommand = activeCommandLine()?.identity()
                    awaitingSnapshotChange = true
                    triggerController.cancelAndHide()
                }
            }

        /**
         * Feedback callback to install in the terminal's host services.
         *
         * It makes an unchanged command eligible for a fresh ranking request
         * before forwarding the event to the host callback.
         */
        val suggestionFeedbackHandler =
            SwingShellSuggestionFeedbackHandler { feedback ->
                triggerController.invalidateLastRequest()
                feedbackHandler.onSuggestionFeedback(feedback)
            }

        /**
         * Creates a live-completion binding for [session].
         *
         * @param session terminal session that supplies shell and render state.
         * @param coroutineScope caller-owned lifecycle scope used for observation.
         * @param suggestionsEnabled returns whether automatic suggestions are enabled.
         * @param rankingContextKey host context that can change ranking for equal text.
         * @param feedbackHandler host callback for suggestion feedback.
         */
        constructor(
            session: TerminalSession,
            coroutineScope: CoroutineScope,
            suggestionsEnabled: () -> Boolean,
            rankingContextKey: () -> String? = { null },
            feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
        ) : this(
            activeCommandLine = session::activeShellCommandLine,
            renderGenerations = session.renderGeneration,
            suggestionsEnabled = suggestionsEnabled,
            rankingContextKey = rankingContextKey,
            feedbackHandler = feedbackHandler,
            scheduler = SwingTimerLiveCompletionScheduler(),
            observationScope = coroutineScope,
        )

        /**
         * Attaches this binding to [terminal] and starts render observation.
         *
         * A binding may be attached exactly once. The terminal must already be
         * bound to the session supplied to the constructor.
         *
         * @param terminal Swing terminal that presents suggestions.
         */
        fun attach(terminal: SwingTerminal) {
            attach(SwingTerminalLiveCompletionTarget(terminal))
        }

        internal fun attach(target: SwingLiveCompletionTarget) {
            check(!closed) { "live completion binding is closed" }
            check(this.target == null) { "live completion binding is already attached" }
            this.target = target
            var focusListenerAttached = false
            try {
                target.addFocusListener(focusListener)
                focusListenerAttached = true
                target.addInvalidationListener(invalidationListener)
                observationJob =
                    observationScope.launch(CoroutineName("swing-live-completion")) {
                        renderGenerations.collect {
                            onRenderPublication()
                        }
                    }
            } catch (failure: Throwable) {
                observationJob?.cancel()
                observationJob = null
                if (focusListenerAttached) runCatching { target.removeFocusListener(focusListener) }
                runCatching { target.removeInvalidationListener(invalidationListener) }
                this.target = null
                throw failure
            }
        }

        /** Re-evaluates live completion after the shared debounce interval. */
        fun scheduleRefresh() {
            synchronized(lifecycleLock) {
                if (!closed) triggerController.scheduleRefresh()
            }
        }

        /** Cancels pending live completion and hides the current popup. */
        fun cancelAndHide() {
            synchronized(lifecycleLock) {
                if (!closed) triggerController.cancelAndHide()
            }
        }

        internal fun refreshNow() {
            if (!closed) triggerController.refreshNow()
        }

        /** Stops observation, removes focus wiring, and hides suggestions. */
        override fun close() {
            synchronized(lifecycleLock) {
                if (closed) return
                closed = true
                observationJob?.cancel()
                observationJob = null
                triggerController.cancelAndHide()
                target?.removeFocusListener(focusListener)
                target?.removeInvalidationListener(invalidationListener)
                target = null
            }
        }

        private fun onRenderPublication() {
            synchronized(lifecycleLock) {
                if (closed) return
                if (awaitingSnapshotChange) {
                    val current = activeCommandLine()?.identity()
                    if (current == invalidatedCommand) return
                    invalidatedCommand = null
                    awaitingSnapshotChange = false
                }
                if (triggerController.hasRequestedSnapshotChanged()) {
                    target?.hideSuggestions()
                }
                triggerController.scheduleRefresh()
            }
        }

        private companion object {
            private const val DEFAULT_DEBOUNCE_MILLIS = 75
            private const val DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS = 2
        }

        private fun TerminalShellCommandLineSnapshot.identity(): CommandSnapshotIdentity =
            CommandSnapshotIdentity(commandText, cursorOffset)

        private data class CommandSnapshotIdentity(
            val commandText: String,
            val cursorOffset: Int,
        )
    }

internal interface SwingLiveCompletionTarget {
    fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot)

    fun hideSuggestions()

    fun addFocusListener(listener: FocusListener)

    fun removeFocusListener(listener: FocusListener)

    fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener)

    fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener)

    fun isFocusOwner(): Boolean
}

private class SwingTerminalLiveCompletionTarget(
    private val terminal: SwingTerminal,
) : SwingLiveCompletionTarget {
    override fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot) {
        terminal.requestShellSuggestions(
            commandText = snapshot.commandText,
            cursorOffset = snapshot.cursorOffset,
            anchorColumn = snapshot.cursorColumn,
            anchorRow = snapshot.cursorRow,
        )
    }

    override fun hideSuggestions() {
        terminal.hideShellSuggestions()
    }

    override fun addFocusListener(listener: FocusListener) {
        terminal.addFocusListener(listener)
    }

    override fun removeFocusListener(listener: FocusListener) {
        terminal.removeFocusListener(listener)
    }

    override fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
        terminal.addShellSuggestionInvalidationListener(listener)
    }

    override fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
        terminal.removeShellSuggestionInvalidationListener(listener)
    }

    override fun isFocusOwner(): Boolean = terminal.isFocusOwner
}
