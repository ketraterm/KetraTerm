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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingLiveCompletionTriggerControllerTest {
    @Test
    fun `debounce replaces pending work and evaluates the latest snapshot`() {
        val scheduler = RecordingScheduler()
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var active = snapshot("git s")
        val controller = controller(scheduler, { active }, requested::add)

        controller.scheduleRefresh()
        active = snapshot("git st")
        controller.scheduleRefresh()
        scheduler.fire()

        assertEquals(2, scheduler.restartCount)
        assertEquals(listOf(snapshot("git st")), requested)
    }

    @Test
    fun `missing or blank command hides suggestions`() {
        val hidden = Counter()
        var active: TerminalShellCommandLineSnapshot? = null
        val controller = controller(activeCommandLine = { active }, hideSuggestions = hidden::increment)

        controller.refreshNow()
        active = snapshot("  ")
        controller.refreshNow()

        assertEquals(2, hidden.count)
    }

    @Test
    fun `same request is deduplicated until text position or ranking context changes`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val active = snapshot("git s")
        var directory = "file:///one"
        val controller =
            controller(
                activeCommandLine = { active },
                requestSuggestions = requested::add,
                rankingContextKey = { directory },
            )

        controller.refreshNow()
        controller.refreshNow()
        directory = "file:///two"
        controller.refreshNow()
        controller.invalidateLastRequest()
        controller.refreshNow()

        assertEquals(listOf(active, active, active), requested)
    }

    @Test
    fun `cheap trigger characters bypass the normal length threshold`() {
        val requested = ArrayList<String>()
        var active = snapshot("-")
        val controller =
            controller(
                activeCommandLine = { active },
                requestSuggestions = { requested += it.commandText },
                minimumNonWhitespaceCharacters = 99,
            )

        listOf("-", "/", "\\", "$", "=", "go ").forEach { command ->
            active = snapshot(command)
            controller.refreshNow()
        }

        assertEquals(listOf("-", "/", "\\", "$", "=", "go "), requested)
    }

    @Test
    fun `trigger policy does not parse command semantics`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val active = snapshot("git status && ")
        val controller = controller(activeCommandLine = { active }, requestSuggestions = requested::add)

        controller.refreshNow()

        assertEquals(listOf(active), requested)
    }

    @Test
    fun `short ordinary text is hidden`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val hidden = Counter()
        val controller =
            controller(
                activeCommandLine = { snapshot("g") },
                requestSuggestions = requested::add,
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(emptyList(), requested)
        assertEquals(1, hidden.count)
    }

    @Test
    fun `disabled suggestions cancel pending work and hide`() {
        val scheduler = RecordingScheduler()
        val hidden = Counter()
        val controller =
            controller(
                scheduler = scheduler,
                suggestionsEnabled = { false },
                hideSuggestions = hidden::increment,
            )

        controller.scheduleRefresh()
        scheduler.fire()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, hidden.count)
        assertNull(scheduler.pending)
    }

    @Test
    fun `cancel and hide clears pending work`() {
        val scheduler = RecordingScheduler()
        val hidden = Counter()
        val controller = controller(scheduler = scheduler, hideSuggestions = hidden::increment)

        controller.scheduleRefresh()
        controller.cancelAndHide()
        scheduler.fire()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, hidden.count)
    }

    @Test
    fun `timer rejects restart outside the EDT`() {
        val scheduler = SwingTimerLiveCompletionScheduler()
        var failure: Throwable? = null
        val background = Thread { failure = runCatching { scheduler.restart(0) {} }.exceptionOrNull() }
        background.start()
        background.join()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `EDT cancellation followed by restart runs only the newer action`() {
        val scheduler = SwingTimerLiveCompletionScheduler()
        val actionFired = CountDownLatch(1)
        var cancelledActionFired = false

        SwingUtilities.invokeAndWait {
            scheduler.restart(0) { cancelledActionFired = true }
            scheduler.cancel()
            scheduler.restart(0, actionFired::countDown)
        }

        assertTrue(actionFired.await(2, TimeUnit.SECONDS))
        assertEquals(false, cancelledActionFired)
    }

    private fun controller(
        scheduler: RecordingScheduler = RecordingScheduler(),
        activeCommandLine: () -> TerminalShellCommandLineSnapshot? = { snapshot("git s") },
        requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit = {},
        hideSuggestions: () -> Unit = {},
        rankingContextKey: () -> String? = { null },
        suggestionsEnabled: () -> Boolean = { true },
        minimumNonWhitespaceCharacters: Int = 2,
    ): SwingLiveCompletionTriggerController =
        SwingLiveCompletionTriggerController(
            activeCommandLine = activeCommandLine,
            requestSuggestions = requestSuggestions,
            hideSuggestions = hideSuggestions,
            rankingContextKey = rankingContextKey,
            suggestionsEnabled = suggestionsEnabled,
            scheduler = scheduler,
            debounceMillis = 50,
            minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
        )

    private fun snapshot(command: String): TerminalShellCommandLineSnapshot =
        TerminalShellCommandLineSnapshot(command, command.length, command.length, cursorRow = 2)

    private class RecordingScheduler : SwingLiveCompletionScheduler {
        var pending: (() -> Unit)? = null
        var restartCount = 0
        var cancelCount = 0

        override fun restart(
            delayMillis: Int,
            action: () -> Unit,
        ) {
            restartCount++
            pending = action
        }

        override fun cancel() {
            cancelCount++
            pending = null
        }

        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
        }
    }

    private class Counter {
        var count = 0

        fun increment() {
            count++
        }
    }
}
