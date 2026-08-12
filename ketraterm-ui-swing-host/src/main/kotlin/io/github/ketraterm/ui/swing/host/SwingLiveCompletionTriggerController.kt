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

import io.github.ketraterm.completion.api.TerminalLiveCompletionTriggerDecision
import io.github.ketraterm.completion.api.TerminalLiveCompletionTriggerState
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Shared Swing policy for debounced live completion requests.
 *
 * The controller owns only popup trigger state. Candidate generation, source
 * loading, learning, and ranking remain in their completion layers.
 *
 * @param activeCommandLine latest shell-integration command snapshot.
 * @param requestSuggestions displays suggestions for an eligible snapshot.
 * @param hideSuggestions hides the current popup.
 * @param rankingContextKey host context that can alter ranking for equal text.
 * @param suggestionsEnabled whether live suggestions are enabled.
 * @param scheduler replaceable Swing debounce scheduler.
 * @param commandSpecs specs shared with the completion engine.
 * @param shellCapabilities active shell syntax and replacement policy.
 * @param debounceMillis non-negative quiet period before a refresh.
 * @param minimumNonWhitespaceCharacters normal minimum command prefix length.
 */
class SwingLiveCompletionTriggerController
    @JvmOverloads
    constructor(
        private val activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
        private val requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit,
        private val hideSuggestions: () -> Unit,
        private val rankingContextKey: () -> String? = { null },
        private val suggestionsEnabled: () -> Boolean,
        private val scheduler: SwingLiveCompletionScheduler = SwingTimerLiveCompletionScheduler(),
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
        private val debounceMillis: Int = DEFAULT_DEBOUNCE_MILLIS,
        minimumNonWhitespaceCharacters: Int = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
    ) {
        private val triggerState =
            TerminalLiveCompletionTriggerState(
                minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
                commandSpecs = commandSpecs,
                shellCapabilities = shellCapabilities,
            )

        init {
            require(debounceMillis >= 0) { "debounceMillis must be >= 0, was $debounceMillis" }
        }

        /** Replaces any pending refresh with one based on the latest snapshot. */
        fun scheduleRefresh() {
            scheduler.restart(debounceMillis, ::refreshNow)
        }

        /** Allows the same visible command to be requested after context changes. */
        fun invalidateLastRequest() {
            triggerState.invalidate()
        }

        /** Evaluates and applies the trigger decision for the latest snapshot. */
        fun refreshNow() {
            if (!suggestionsEnabled()) {
                cancelAndHide()
                return
            }
            val snapshot = activeCommandLine()
            when (
                triggerState.evaluate(
                    commandLine = snapshot?.commandText,
                    cursorOffset = snapshot?.cursorOffset ?: 0,
                    cursorColumn = snapshot?.cursorColumn ?: 0,
                    cursorRow = snapshot?.cursorRow ?: 0,
                    rankingContextKey = rankingContextKey(),
                )
            ) {
                TerminalLiveCompletionTriggerDecision.HIDE -> hideSuggestions()
                TerminalLiveCompletionTriggerDecision.KEEP -> Unit
                TerminalLiveCompletionTriggerDecision.REQUEST -> requestSuggestions(checkNotNull(snapshot))
            }
        }

        /** Cancels a pending refresh, resets trigger state, and hides the popup. */
        fun cancelAndHide() {
            scheduler.cancel()
            triggerState.invalidate()
            hideSuggestions()
        }

        private companion object {
            private const val DEFAULT_DEBOUNCE_MILLIS = 75
            private const val DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS = 2
        }
    }

/** Replaceable delayed-action seam for Swing live completion refreshes. */
interface SwingLiveCompletionScheduler {
    /** Replaces pending work and invokes [action] after [delayMillis]. */
    fun restart(
        delayMillis: Int,
        action: () -> Unit,
    )

    /** Cancels the pending action, if present. */
    fun cancel()
}

/** One-shot Swing timer scheduler; all state is confined to the EDT. */
class SwingTimerLiveCompletionScheduler : SwingLiveCompletionScheduler {
    private var timer: Timer? = null

    /** Replaces the current one-shot timer. */
    override fun restart(
        delayMillis: Int,
        action: () -> Unit,
    ) {
        runOnEdt {
            timer?.stop()
            timer =
                Timer(delayMillis) {
                    timer = null
                    action()
                }.apply {
                    isRepeats = false
                    start()
                }
        }
    }

    /** Stops and releases the current timer. */
    override fun cancel() {
        runOnEdt {
            timer?.stop()
            timer = null
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }
}
