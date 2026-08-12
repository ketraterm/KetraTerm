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

import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Shared Swing policy for debounced live completion requests.
 *
 * The controller owns only popup trigger state. Candidate generation, source
 * loading, learning, and ranking remain in their completion layers.
 *
 * Its cheap text-only predicate avoids parsing or resolving command specs. The
 * completion engine remains the authoritative semantic eligibility check and
 * returns an empty result when no source applies.
 */
class SwingLiveCompletionTriggerController
    internal constructor(
        private val activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
        private val requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit,
        private val hideSuggestions: () -> Unit,
        private val rankingContextKey: () -> String?,
        private val suggestionsEnabled: () -> Boolean,
        private val scheduler: SwingLiveCompletionScheduler,
        private val debounceMillis: Int,
        private val minimumNonWhitespaceCharacters: Int,
    ) {
        private var lastRequest: RequestKey? = null

        init {
            require(debounceMillis >= 0) { "debounceMillis must be >= 0, was $debounceMillis" }
            require(minimumNonWhitespaceCharacters >= 0) {
                "minimumNonWhitespaceCharacters must be >= 0, was $minimumNonWhitespaceCharacters"
            }
        }

        /**
         * Creates a controller backed by a one-shot Swing timer.
         *
         * @param activeCommandLine latest shell-integration command snapshot.
         * @param requestSuggestions requests suggestions for an eligible snapshot.
         * @param hideSuggestions cancels and hides the current popup through the host.
         * @param rankingContextKey host context that can alter ranking for equal text.
         * @param suggestionsEnabled whether live suggestions are enabled.
         * @param debounceMillis non-negative quiet period before a refresh.
         * @param minimumNonWhitespaceCharacters normal minimum typed-character count.
         */
        @JvmOverloads
        constructor(
            activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
            requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit,
            hideSuggestions: () -> Unit,
            rankingContextKey: () -> String? = { null },
            suggestionsEnabled: () -> Boolean,
            debounceMillis: Int = DEFAULT_DEBOUNCE_MILLIS,
            minimumNonWhitespaceCharacters: Int = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
        ) : this(
            activeCommandLine = activeCommandLine,
            requestSuggestions = requestSuggestions,
            hideSuggestions = hideSuggestions,
            rankingContextKey = rankingContextKey,
            suggestionsEnabled = suggestionsEnabled,
            scheduler = SwingTimerLiveCompletionScheduler(),
            debounceMillis = debounceMillis,
            minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
        )

        /** Replaces any pending refresh with one based on the latest snapshot. */
        fun scheduleRefresh() {
            scheduler.restart(debounceMillis, ::refreshNow)
        }

        /** Allows the same visible command to be requested after context changes. */
        fun invalidateLastRequest() {
            lastRequest = null
        }

        /** Applies cheap UX gating and requests completion for the latest snapshot. */
        internal fun refreshNow() {
            if (!suggestionsEnabled()) {
                cancelAndHide()
                return
            }
            val snapshot = activeCommandLine()
            if (snapshot == null || !shouldRequest(snapshot)) {
                invalidateLastRequest()
                hideSuggestions()
                return
            }
            val request = RequestKey(snapshot, rankingContextKey())
            if (request == lastRequest) return
            lastRequest = request
            requestSuggestions(snapshot)
        }

        /** Cancels a pending refresh, resets trigger state, and hides the popup. */
        fun cancelAndHide() {
            scheduler.cancel()
            invalidateLastRequest()
            hideSuggestions()
        }

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
                ' ' ->
                    nonWhitespaceCharacters >= MINIMUM_SPACE_TRIGGER_CHARACTERS &&
                        text.getOrNull(cursorOffset - 2)?.isWhitespace() == false
                else -> false
            }
        }

        private data class RequestKey(
            val commandText: String,
            val cursorOffset: Int,
            val cursorColumn: Int,
            val cursorRow: Int,
            val rankingContextKey: String?,
        ) {
            constructor(snapshot: TerminalShellCommandLineSnapshot, rankingContextKey: String?) : this(
                commandText = snapshot.commandText,
                cursorOffset = snapshot.cursorOffset,
                cursorColumn = snapshot.cursorColumn,
                cursorRow = snapshot.cursorRow,
                rankingContextKey = rankingContextKey,
            )
        }

        private companion object {
            private const val DEFAULT_DEBOUNCE_MILLIS = 75
            private const val DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS = 2
            private const val MINIMUM_SPACE_TRIGGER_CHARACTERS = 2
        }
    }

/** Replaceable delayed-action seam for Swing live completion refreshes. */
internal interface SwingLiveCompletionScheduler {
    /** Replaces pending work and invokes [action] after [delayMillis]. */
    fun restart(
        delayMillis: Int,
        action: () -> Unit,
    )

    /** Cancels the pending action, if present. */
    fun cancel()
}

/** One-shot Swing timer scheduler; all state is confined to the EDT. */
internal class SwingTimerLiveCompletionScheduler : SwingLiveCompletionScheduler {
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
