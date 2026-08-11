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
package io.github.ketraterm.intellij.ui

import io.github.ketraterm.completion.api.TerminalLiveCompletionTriggerDecision
import io.github.ketraterm.completion.api.TerminalLiveCompletionTriggerState
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** IntelliJ host policy for debounced live shell-suggestion refreshes. */
internal class IntellijCompletionTriggerController(
    private val activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
    private val requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit,
    private val hideSuggestions: () -> Unit,
    private val rankingContextKey: () -> String?,
    private val suggestionsEnabled: () -> Boolean,
    private val scheduler: IntellijCompletionTriggerScheduler,
    private val commandSpecs: List<TerminalCommandSpec>,
    private val shellCapabilities: TerminalShellCapabilities,
    private val debounceMillis: Int = DEFAULT_DEBOUNCE_MILLIS,
    private val minimumNonWhitespaceCharacters: Int = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
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

    fun scheduleRefresh() {
        scheduler.restart(debounceMillis, ::refreshNow)
    }

    fun sourceSnapshotChanged() {
        scheduler.restart(0) {
            triggerState.invalidate()
            refreshNow()
        }
    }

    fun invalidateLastRequest() {
        triggerState.invalidate()
    }

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

/** Replaceable delayed-action seam for IntelliJ completion refreshes. */
internal interface IntellijCompletionTriggerScheduler {
    fun restart(
        delayMillis: Int,
        action: () -> Unit,
    )

    fun cancel()
}

/** Coroutine-backed scheduler that publishes only its latest generation. */
internal class CoroutineIntellijCompletionTriggerScheduler(
    private val scope: CoroutineScope,
    private val dispatch: ((() -> Unit) -> Unit),
) : IntellijCompletionTriggerScheduler {
    private val generation = AtomicLong()
    private val lock = Any()
    private var job: Job? = null

    override fun restart(
        delayMillis: Int,
        action: () -> Unit,
    ) {
        synchronized(lock) {
            val requestGeneration = generation.incrementAndGet()
            job?.cancel()
            job =
                scope.launch {
                    if (delayMillis > 0) delay(delayMillis.toLong())
                    dispatch {
                        if (generation.get() == requestGeneration) action()
                    }
                }
        }
    }

    override fun cancel() {
        generation.incrementAndGet()
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }
}
