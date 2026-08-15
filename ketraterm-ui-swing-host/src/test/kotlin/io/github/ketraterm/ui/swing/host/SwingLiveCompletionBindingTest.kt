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
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class SwingLiveCompletionBindingTest {
    @Test
    fun `shell edit revisions debounce the latest shell snapshot`() =
        runTest {
            val revisions = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, revisions, scheduler, { active })

            binding.attach(target)
            runCurrent()
            active = snapshot("git st")
            revisions.value = 1L
            runCurrent()
            scheduler.fire()

            assertEquals(1, scheduler.restartCount)
            assertEquals(listOf(snapshot("git st")), target.requests)
            binding.close()
        }

    @Test
    fun `feedback invalidates request deduplication before reaching the host`() =
        runTest {
            val forwarded = ArrayList<SwingShellSuggestionFeedback>()
            val target = RecordingTarget()
            val binding =
                binding(
                    scope = backgroundScope,
                    feedbackHandler = SwingShellSuggestionFeedbackHandler { forwarded += it },
                )
            binding.attach(target)

            binding.refreshNow()
            binding.refreshNow()
            val feedback = feedback()
            binding.suggestionFeedbackHandler.onSuggestionFeedback(feedback)
            binding.refreshNow()

            assertEquals(2, target.requests.size)
            assertEquals(1, forwarded.size)
            assertSame(feedback, forwarded.single())
            binding.close()
        }

    @Test
    fun `focus loss hides suggestions and close removes all lifecycle wiring`() =
        runTest {
            val revisions = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions, scheduler)
            binding.attach(target)
            runCurrent()

            target.loseFocus()
            binding.close()
            val restartsBeforeClosedPublication = scheduler.restartCount
            revisions.value = 2L
            binding.scheduleRefresh()
            runCurrent()

            assertEquals(2, target.hideCount)
            assertEquals(1, target.removeFocusListenerCount)
            assertEquals(restartsBeforeClosedPublication, scheduler.restartCount)
        }

    @Test
    fun `shell edit revisions cannot reopen suggestions until focus returns`() =
        runTest {
            val revisions = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions, scheduler)
            binding.attach(target)
            revisions.value = 0L
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            target.loseFocus()
            revisions.value = 1L
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            target.gainFocus()
            scheduler.fire()
            assertEquals(2, target.requests.size)
            binding.close()
        }

    @Test
    fun `input invalidation rejects unchanged command snapshots until command changes`() =
        runTest {
            val revisions = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, revisions, scheduler, { active })
            binding.attach(target)
            revisions.value = 0L
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            target.invalidateSuggestions()
            val restartsBeforeFocusCycle = scheduler.restartCount
            target.loseFocus()
            target.gainFocus()
            assertEquals(restartsBeforeFocusCycle, scheduler.restartCount)
            revisions.value = 0L
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            active = TerminalShellCommandLineSnapshot("git s", 5, 18, cursorRow = 9)
            revisions.value = 0L
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            active = snapshot("git st")
            revisions.value = 1L
            runCurrent()
            scheduler.fire()
            assertEquals(listOf(snapshot("git s"), snapshot("git st")), target.requests)
            binding.close()
        }

    @Test
    fun `changed shell snapshot hides old popup before debounce fires`() =
        runTest {
            val revisions = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, revisions, scheduler, { active })
            binding.attach(target)
            revisions.value = 0L
            runCurrent()
            scheduler.fire()
            val hidesBeforeChange = target.hideCount

            active = snapshot("git st")
            revisions.value = 1L
            runCurrent()

            assertEquals(hidesBeforeChange + 1, target.hideCount)
            assertEquals(1, target.requests.size)
            scheduler.fire()
            assertEquals(2, target.requests.size)
            binding.close()
        }

    @Test
    fun `binding rejects duplicate attachment and attachment after close`() =
        runTest {
            val binding = binding(backgroundScope)
            binding.attach(RecordingTarget())

            assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget()) }
            binding.close()
            assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget()) }
        }

    @Test
    fun `failed attachment rolls back target ownership`() =
        runTest {
            val binding = binding(backgroundScope)

            assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget(failOnAttach = true)) }
            binding.attach(RecordingTarget())

            binding.close()
        }

    @Test
    fun `ineligible viewport cancels automatic trigger and live viewport reschedules`() =
        runTest {
            val revisions = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions, scheduler)
            binding.attach(target)
            revisions.value = 0L
            runCurrent()

            target.setAutomaticSuggestionEligible(false)
            scheduler.fire()
            assertEquals(0, target.requests.size)

            target.setAutomaticSuggestionEligible(true)
            scheduler.fire()
            assertEquals(1, target.requests.size)
            binding.close()
        }

    @Test
    fun `cheap trigger characters bypass the normal length threshold`() =
        runTest {
            val target = RecordingTarget()
            var active = snapshot("-")
            val binding =
                binding(
                    scope = backgroundScope,
                    activeCommandLine = { active },
                    minimumNonWhitespaceCharacters = 99,
                )
            binding.attach(target)

            listOf("-", "/", "\\", "$", "=", "go ").forEach { command ->
                active = snapshot(command)
                binding.refreshNow()
            }

            assertEquals(listOf("-", "/", "\\", "$", "=", "go "), target.requests.map { it.commandText })
            binding.close()
        }

    @Test
    fun `short ordinary text is hidden`() =
        runTest {
            val target = RecordingTarget()
            val binding = binding(scope = backgroundScope, activeCommandLine = { snapshot("g") })
            binding.attach(target)

            binding.refreshNow()

            assertEquals(emptyList(), target.requests)
            assertEquals(1, target.hideCount)
            binding.close()
        }

    @Test
    fun `ranking context change invalidates request deduplication`() =
        runTest {
            val target = RecordingTarget()
            val active = snapshot("git s")
            var directory = "file:///one"
            val binding =
                SwingLiveCompletionBinding(
                    activeCommandLine = { active },
                    shellCommandLineRevisions = MutableStateFlow(-1L),
                    suggestionsEnabled = { true },
                    rankingContextKey = { directory },
                    feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
                    scheduler = RecordingScheduler(),
                    observationScope = backgroundScope,
                    edtDispatcher = ImmediateEdtDispatcher,
                )
            binding.attach(target)

            binding.refreshNow()
            binding.refreshNow()
            directory = "file:///two"
            binding.refreshNow()

            assertEquals(listOf(active, active), target.requests)
            binding.close()
        }

    private fun binding(
        scope: CoroutineScope,
        revisions: MutableStateFlow<Long> = MutableStateFlow(-1L),
        scheduler: RecordingScheduler = RecordingScheduler(),
        activeCommandLine: () -> TerminalShellCommandLineSnapshot? = { snapshot("git s") },
        feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
        minimumNonWhitespaceCharacters: Int = 2,
    ): SwingLiveCompletionBinding =
        SwingLiveCompletionBinding(
            activeCommandLine = activeCommandLine,
            shellCommandLineRevisions = revisions,
            suggestionsEnabled = { true },
            rankingContextKey = { "file:///workspace" },
            feedbackHandler = feedbackHandler,
            scheduler = scheduler,
            observationScope = scope,
            edtDispatcher = ImmediateEdtDispatcher,
            minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
        )

    private fun snapshot(command: String): TerminalShellCommandLineSnapshot =
        TerminalShellCommandLineSnapshot(command, command.length, command.length, cursorRow = 2)

    private fun feedback(): SwingShellSuggestionFeedback {
        val request = SwingShellSuggestionRequest("git s", 5, 5, 2)
        return SwingShellSuggestionFeedback(
            kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
            suggestion =
                SwingShellSuggestion(
                    replacementText = "status",
                    replacementStartOffset = 4,
                    replacementEndOffset = 5,
                    source = "spec",
                    kind = "SUBCOMMAND",
                ),
            index = 0,
            request = request,
        )
    }

    private class RecordingTarget(
        private val failOnAttach: Boolean = false,
    ) : SwingLiveCompletionTarget {
        val requests = ArrayList<TerminalShellCommandLineSnapshot>()
        var hideCount = 0
        var removeFocusListenerCount = 0
        private var focusListener: FocusListener? = null
        private var invalidationListener: SwingShellSuggestionInvalidationListener? = null
        private var eligibilityListener: SwingShellSuggestionEligibilityListener? = null
        private var focused = true
        private var automaticSuggestionEligible = true

        override fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot) {
            requests += snapshot
        }

        override fun hideSuggestions() {
            hideCount++
        }

        override fun addFocusListener(listener: FocusListener) {
            check(!failOnAttach) { "attachment failed" }
            focusListener = listener
        }

        override fun removeFocusListener(listener: FocusListener) {
            if (focusListener === listener) focusListener = null
            removeFocusListenerCount++
        }

        override fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            invalidationListener = listener
        }

        override fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            if (invalidationListener === listener) invalidationListener = null
        }

        override fun addEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            eligibilityListener = listener
        }

        override fun removeEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            if (eligibilityListener === listener) eligibilityListener = null
        }

        override fun isFocusOwner(): Boolean = focused

        override fun isAutomaticSuggestionEligible(): Boolean = automaticSuggestionEligible

        fun loseFocus() {
            focused = false
            focusListener?.focusLost(FocusEvent(JPanel(), FocusEvent.FOCUS_LOST))
        }

        fun gainFocus() {
            focused = true
            focusListener?.focusGained(FocusEvent(JPanel(), FocusEvent.FOCUS_GAINED))
        }

        fun invalidateSuggestions() {
            invalidationListener?.onShellSuggestionsInvalidated()
        }

        fun setAutomaticSuggestionEligible(eligible: Boolean) {
            automaticSuggestionEligible = eligible
            eligibilityListener?.onAutomaticShellSuggestionEligibilityChanged(eligible)
        }
    }

    private class RecordingScheduler : SwingLiveCompletionScheduler {
        var restartCount = 0
        private var pending: (() -> Unit)? = null

        override fun restart(
            delayMillis: Int,
            action: () -> Unit,
        ) {
            restartCount++
            pending = action
        }

        override fun cancel() {
            pending = null
        }

        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
        }
    }

    private object ImmediateEdtDispatcher : SwingLiveCompletionEdtDispatcher {
        override fun isDispatchThread(): Boolean = true

        override fun dispatch(task: Runnable) = task.run()
    }
}
