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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionEligibilityListener
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * Binds host-neutral live-completion behavior to one Swing terminal.
 *
 * The binding observes dedicated shell-edit revisions, applies the shared debounce policy,
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
        private val shellCommandLineRevisions: Flow<Long>,
        private val suggestionsEnabled: () -> Boolean,
        rankingContextKey: () -> String?,
        feedbackHandler: SwingShellSuggestionFeedbackHandler,
        scheduler: SwingLiveCompletionScheduler,
        private val observationScope: CoroutineScope,
        private val edtDispatcher: SwingLiveCompletionEdtDispatcher = SwingLiveCompletionSwingEdtDispatcher,
    ) : AutoCloseable {
        private var target: SwingLiveCompletionTarget? = null
        private var observationJob: Job? = null
        private var invalidatedRevision = NO_SHELL_COMMAND_LINE_REVISION
        private var awaitingShellCommandLineRevision = false
        private val latestRevision = AtomicLong(NO_SHELL_COMMAND_LINE_REVISION)
        private val revisionDispatchQueued = AtomicBoolean()

        @Volatile
        private var closed = false

        private val revisionDrain = Runnable(::drainShellCommandLineRevisionOnEdt)
        private val scheduleRefreshTask = Runnable { scheduleRefreshOnEdt() }
        private val cancelAndHideTask = Runnable { cancelAndHideOnEdt() }

        private val triggerController =
            SwingLiveCompletionTriggerController(
                activeCommandLine = activeCommandLine,
                requestSuggestions = { snapshot -> target?.requestSuggestions(snapshot) },
                hideSuggestions = { target?.hideSuggestions() },
                rankingContextKey = rankingContextKey,
                suggestionsEnabled = ::isEligibleOnEdt,
                scheduler = scheduler,
                debounceMillis = DEFAULT_DEBOUNCE_MILLIS,
                minimumNonWhitespaceCharacters = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
            )

        private val focusListener =
            object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    scheduleRefreshOnEdt()
                }

                override fun focusLost(event: FocusEvent) {
                    cancelAndHideOnEdt()
                }
            }

        private val invalidationListener =
            SwingShellSuggestionInvalidationListener {
                check(edtDispatcher.isDispatchThread()) { "shell suggestion invalidation must run on the EDT" }
                if (closed) return@SwingShellSuggestionInvalidationListener
                invalidatedRevision = latestRevision.get()
                awaitingShellCommandLineRevision = true
                triggerController.cancelAndHide()
            }

        private val eligibilityListener =
            SwingShellSuggestionEligibilityListener { eligible ->
                check(edtDispatcher.isDispatchThread()) { "shell suggestion eligibility must run on the EDT" }
                if (closed) return@SwingShellSuggestionEligibilityListener
                if (eligible && isEligibleOnEdt() && !awaitingShellCommandLineRevision) {
                    triggerController.invalidateLastRequest()
                    triggerController.scheduleRefresh()
                } else {
                    // SwingTerminal synchronously cancels and hides only the
                    // ineligible automatic presentation before publishing this
                    // callback. Do not hide an explicitly requested popup when
                    // settings disable automatic completion.
                    triggerController.automaticEligibilityLost()
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
                check(edtDispatcher.isDispatchThread()) { "shell suggestion feedback must run on the EDT" }
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
            shellCommandLineRevisions = session.activeShellCommandLineRevision,
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
            check(edtDispatcher.isDispatchThread()) { "live completion must be attached on the EDT" }
            check(!closed) { "live completion binding is closed" }
            check(this.target == null) { "live completion binding is already attached" }
            this.target = target
            var focusListenerAttached = false
            try {
                target.addFocusListener(focusListener)
                focusListenerAttached = true
                target.addInvalidationListener(invalidationListener)
                target.addEligibilityListener(eligibilityListener)
                observationJob =
                    observationScope.launch(CoroutineName("swing-live-completion")) {
                        shellCommandLineRevisions.collect { revision ->
                            if (revision >= 0L) enqueueShellCommandLineRevision(revision)
                        }
                    }
            } catch (failure: Throwable) {
                observationJob?.cancel()
                observationJob = null
                if (focusListenerAttached) runCatching { target.removeFocusListener(focusListener) }
                runCatching { target.removeInvalidationListener(invalidationListener) }
                runCatching { target.removeEligibilityListener(eligibilityListener) }
                this.target = null
                throw failure
            }
        }

        /** Re-evaluates live completion after the shared debounce interval. */
        fun scheduleRefresh() {
            edtDispatcher.dispatch(scheduleRefreshTask)
        }

        /** Cancels pending live completion and hides the current popup. */
        fun cancelAndHide() {
            edtDispatcher.dispatch(cancelAndHideTask)
        }

        internal fun refreshNow() {
            check(edtDispatcher.isDispatchThread()) { "live completion must refresh on the EDT" }
            if (!closed && isEligibleOnEdt()) triggerController.refreshNow()
        }

        /** Stops observation, removes focus wiring, and hides suggestions. */
        override fun close() {
            check(edtDispatcher.isDispatchThread()) { "live completion must be closed on the EDT" }
            if (closed) return
            closed = true
            observationJob?.cancel()
            observationJob = null
            triggerController.cancelAndHide()
            target?.removeFocusListener(focusListener)
            target?.removeInvalidationListener(invalidationListener)
            target?.removeEligibilityListener(eligibilityListener)
            target = null
        }

        private fun enqueueShellCommandLineRevision(revision: Long) {
            latestRevision.set(revision)
            if (revisionDispatchQueued.compareAndSet(false, true)) {
                edtDispatcher.dispatch(revisionDrain)
            }
        }

        private fun drainShellCommandLineRevisionOnEdt() {
            check(edtDispatcher.isDispatchThread()) { "shell command-line revisions must drain on the EDT" }
            while (true) {
                val revision = latestRevision.get()
                onShellCommandLineRevisionOnEdt(revision)
                revisionDispatchQueued.set(false)
                if (latestRevision.get() == revision || !revisionDispatchQueued.compareAndSet(false, true)) return
            }
        }

        private fun onShellCommandLineRevisionOnEdt(revision: Long) {
            if (closed) return
            if (awaitingShellCommandLineRevision) {
                if (revision == invalidatedRevision) return
                awaitingShellCommandLineRevision = false
                invalidatedRevision = NO_SHELL_COMMAND_LINE_REVISION
            }
            if (!isEligibleOnEdt()) {
                triggerController.cancelAndHide()
                return
            }
            triggerController.commandLineChanged()
            triggerController.scheduleRefresh()
        }

        private fun scheduleRefreshOnEdt() {
            if (!closed && !awaitingShellCommandLineRevision && isEligibleOnEdt()) {
                triggerController.scheduleRefresh()
            }
        }

        private fun cancelAndHideOnEdt() {
            if (!closed) triggerController.cancelAndHide()
        }

        private fun isEligibleOnEdt(): Boolean =
            !closed &&
                target?.isFocusOwner() == true &&
                target?.isAutomaticSuggestionEligible() == true &&
                suggestionsEnabled()

        private companion object {
            private const val DEFAULT_DEBOUNCE_MILLIS = 75
            private const val DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS = 2
            private const val NO_SHELL_COMMAND_LINE_REVISION = -1L
        }
    }

internal interface SwingLiveCompletionTarget {
    fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot)

    fun hideSuggestions()

    fun addFocusListener(listener: FocusListener)

    fun removeFocusListener(listener: FocusListener)

    fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener)

    fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener)

    fun addEligibilityListener(listener: SwingShellSuggestionEligibilityListener)

    fun removeEligibilityListener(listener: SwingShellSuggestionEligibilityListener)

    fun isFocusOwner(): Boolean

    fun isAutomaticSuggestionEligible(): Boolean
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

    override fun addEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
        terminal.addShellSuggestionEligibilityListener(listener)
    }

    override fun removeEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
        terminal.removeShellSuggestionEligibilityListener(listener)
    }

    override fun isFocusOwner(): Boolean = terminal.isFocusOwner

    override fun isAutomaticSuggestionEligible(): Boolean = terminal.isAutomaticShellSuggestionEligible()
}

internal interface SwingLiveCompletionEdtDispatcher {
    fun isDispatchThread(): Boolean

    fun dispatch(task: Runnable)
}

private object SwingLiveCompletionSwingEdtDispatcher : SwingLiveCompletionEdtDispatcher {
    override fun isDispatchThread(): Boolean = SwingUtilities.isEventDispatchThread()

    override fun dispatch(task: Runnable) {
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeLater(task)
    }
}
