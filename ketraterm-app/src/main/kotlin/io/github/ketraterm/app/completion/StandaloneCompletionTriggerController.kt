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
package io.github.ketraterm.app.completion

import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Standalone host policy for live shell suggestion refreshes.
 *
 * The controller is deliberately host-owned: it decides when the standalone
 * app asks the reusable Swing terminal to show suggestions, while the shared
 * completion engine remains independent of Swing timers, PTY lifecycle, and
 * product UX thresholds.
 *
 * @param activeCommandLine returns the latest session-owned active command
 * snapshot, or `null` when shell integration cannot prove one.
 * @param requestSuggestions displays suggestions for a validated command
 * snapshot.
 * @param hideSuggestions hides the current standalone popup.
 * @param suggestionsEnabled returns whether shell suggestions are currently
 * enabled in standalone settings.
 * @param scheduler debounce scheduler used to coalesce render/input churn.
 * @param debounceMillis delay before refreshing suggestions after terminal
 * output changes.
 * @param minimumNonWhitespaceCharacters minimum typed non-whitespace command
 * characters required before the popup is allowed to open.
 */
internal class StandaloneCompletionTriggerController(
    private val activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
    private val requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit,
    private val hideSuggestions: () -> Unit,
    private val suggestionsEnabled: () -> Boolean,
    private val scheduler: StandaloneCompletionTriggerScheduler = SwingStandaloneCompletionTriggerScheduler(),
    private val debounceMillis: Int = DEFAULT_DEBOUNCE_MILLIS,
    private val minimumNonWhitespaceCharacters: Int = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
) {
    init {
        require(debounceMillis >= 0) { "debounceMillis must be >= 0, was $debounceMillis" }
        require(minimumNonWhitespaceCharacters >= 0) {
            "minimumNonWhitespaceCharacters must be >= 0, was $minimumNonWhitespaceCharacters"
        }
    }

    /**
     * Schedules a debounced refresh from the latest session command snapshot.
     *
     * Calls are cheap enough to issue from render-dirty notifications. The
     * scheduler coalesces rapid host-output bursts and invokes [refreshNow]
     * once the terminal has been quiet for [debounceMillis]. Settings are
     * checked by [refreshNow] so callers do not need to run on the Event
     * Dispatch Thread.
     */
    fun scheduleRefresh() {
        scheduler.restart(debounceMillis) {
            refreshNow()
        }
    }

    /**
     * Immediately refreshes suggestions from the latest command snapshot.
     *
     * This method is useful for deterministic tests and for future explicit
     * user actions. It hides the popup instead of requesting completions when
     * settings are disabled, shell integration has no active command snapshot,
     * or the typed command prefix is too short for useful suggestions.
     */
    fun refreshNow() {
        if (!suggestionsEnabled()) {
            cancelAndHide()
            return
        }
        val snapshot = activeCommandLine()
        if (snapshot == null || snapshot.nonWhitespaceCount() < minimumNonWhitespaceCharacters) {
            hideSuggestions()
            return
        }
        requestSuggestions(snapshot)
    }

    /**
     * Cancels any pending refresh and hides the current popup.
     */
    fun cancelAndHide() {
        scheduler.cancel()
        hideSuggestions()
    }

    private fun TerminalShellCommandLineSnapshot.nonWhitespaceCount(): Int {
        var count = 0
        var index = 0
        while (index < commandText.length) {
            if (!commandText[index].isWhitespace()) count++
            index++
        }
        return count
    }

    private companion object {
        private const val DEFAULT_DEBOUNCE_MILLIS = 75
        private const val DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS = 2
    }
}

/**
 * Debounce primitive used by [StandaloneCompletionTriggerController].
 */
internal interface StandaloneCompletionTriggerScheduler {
    /**
     * Replaces any pending action with [action] and runs it after [delayMillis].
     *
     * @param delayMillis non-negative debounce delay in milliseconds.
     * @param action action to run after the debounce delay.
     */
    fun restart(
        delayMillis: Int,
        action: () -> Unit,
    )

    /**
     * Cancels the currently pending action, if one exists.
     */
    fun cancel()
}

/**
 * Swing timer backed debounce scheduler for standalone completion popups.
 *
 * All timer mutation is marshalled onto the Event Dispatch Thread so callers
 * may safely schedule from session render workers or other host callbacks.
 */
internal class SwingStandaloneCompletionTriggerScheduler : StandaloneCompletionTriggerScheduler {
    private var timer: Timer? = null

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

    override fun cancel() {
        runOnEdt {
            timer?.stop()
            timer = null
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }
}
