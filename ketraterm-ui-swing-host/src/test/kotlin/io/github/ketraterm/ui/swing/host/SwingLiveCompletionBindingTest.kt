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
    fun `render publications debounce the latest shell snapshot`() =
        runTest {
            val generations = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, generations, scheduler, { active })

            binding.attach(target)
            runCurrent()
            active = snapshot("git st")
            generations.value = 1L
            runCurrent()
            scheduler.fire()

            assertEquals(2, scheduler.restartCount)
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
            val generations = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            val binding = binding(backgroundScope, generations, scheduler)
            binding.attach(target)
            runCurrent()

            target.loseFocus()
            binding.close()
            val restartsBeforeClosedPublication = scheduler.restartCount
            generations.value = 2L
            binding.scheduleRefresh()
            runCurrent()

            assertEquals(2, target.hideCount)
            assertEquals(1, target.removeFocusListenerCount)
            assertEquals(restartsBeforeClosedPublication, scheduler.restartCount)
        }

    @Test
    fun `render publications cannot reopen suggestions until focus returns`() =
        runTest {
            val generations = MutableStateFlow(-1L)
            val scheduler = RecordingScheduler()
            val target = RecordingTarget()
            val binding = binding(backgroundScope, generations, scheduler)
            binding.attach(target)
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            target.loseFocus()
            generations.value = 1L
            runCurrent()
            scheduler.fire()
            assertEquals(1, target.requests.size)

            target.gainFocus()
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

    private fun binding(
        scope: CoroutineScope,
        generations: MutableStateFlow<Long> = MutableStateFlow(-1L),
        scheduler: RecordingScheduler = RecordingScheduler(),
        activeCommandLine: () -> TerminalShellCommandLineSnapshot? = { snapshot("git s") },
        feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
    ): SwingLiveCompletionBinding =
        SwingLiveCompletionBinding(
            activeCommandLine = activeCommandLine,
            renderGenerations = generations,
            suggestionsEnabled = { true },
            rankingContextKey = { "file:///workspace" },
            feedbackHandler = feedbackHandler,
            scheduler = scheduler,
            observationScope = scope,
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
        private var focused = true

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

        override fun isFocusOwner(): Boolean = focused

        fun loseFocus() {
            focused = false
            focusListener?.focusLost(FocusEvent(JPanel(), FocusEvent.FOCUS_LOST))
        }

        fun gainFocus() {
            focused = true
            focusListener?.focusGained(FocusEvent(JPanel(), FocusEvent.FOCUS_GAINED))
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
}
